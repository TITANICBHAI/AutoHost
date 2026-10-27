package app.autohost.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.autohost.capabilities.browser.BrowserPolicy
import app.autohost.capabilities.browser.HostAct
import app.autohost.capabilities.browser.HostCookie
import app.autohost.capabilities.browser.HostExtract
import app.autohost.capabilities.browser.WebPage
import app.autohost.capabilities.browser.WebViewBrowserProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * P10-02 (ADR-070): the app-side [WebViewBrowserProvider.Host]. Owns the run-scoped page
 * registry, launches the *visible* [BrowserActivity] for every page, and serializes profiles:
 *
 * `CookieManager`/`setDataDirectorySuffix` state is process-global, so pages of DIFFERENT
 * profiles take turns under [profileLock] (a documented, honest limitation — parallel
 * cross-profile browsing is never silently shared). Pages inside one profile may run
 * concurrently.
 *
 * `task:` targets resolve against the calling task's own extracted bundle directory
 * (canonical-path containment checked); nothing else on the device is readable this way.
 */
class BrowserController(
    private val appContext: Context,
    private val taskBundleDir: (taskId: String) -> File?,
    private val newPageId: () -> String,
) : WebViewBrowserProvider.Host {

    class PageSession(
        val pageId: String,
        val runId: String,
        val profile: String,
        val provider: String = "browser.webview",
    ) {
        @Volatile
        var url: String = ""

        @Volatile
        var title: String? = null

        @Volatile
        var loadState: String = "new"

        @Volatile
        var inputMode: String = "real"

        @Volatile
        var bodySample: String? = null

        @Volatile
        var activity: BrowserActivity? = null
    }

    private val runs = ConcurrentHashMap<String, MutableMap<String, PageSession>>()
    private val profileLock = ReentrantLock()

    /** Latch the activity completes when a load settles; keyed by pageId. */
    private val loadLatches = ConcurrentHashMap<String, CountDownLatch>()

    /** WebView-debugging flag for the CDP route (S27); toggled from the devtools settings. */
    @Volatile
    var webViewDebuggingEnabled: Boolean = false

    // ---- WebViewBrowserProvider.Host -----------------------------------------

    override fun open(runId: String, profile: String, target: BrowserPolicy.UrlVerdict.Allowed, timeoutMs: Long): WebViewBrowserProvider.HostPage {
        val pages = runs.computeIfAbsent(runId) { ConcurrentHashMap() }
        if (pages.size >= BrowserPolicy.MAX_PAGES) {
            throw WebViewBrowserProvider.HostException(app.autohost.core.model.ErrorCode.SCOPE_VIOLATION, "at most ${BrowserPolicy.MAX_PAGES} pages per run")
        }
        val session = PageSession(newPageId(), runId, profile)
        val resolved = resolve(session, target)
        pages[session.pageId] = session
        return launchAndAwait(pages, session, resolved, timeoutMs, recordUrl = true)
    }

    override fun navigate(runId: String, pageId: String, target: BrowserPolicy.UrlVerdict.Allowed, timeoutMs: Long): WebViewBrowserProvider.HostPage {
        val session = pageSessions(runId)[pageId]
            ?: throw WebViewBrowserProvider.HostException(app.autohost.core.model.ErrorCode.NOT_FOUND, "unknown page_id '$pageId'")
        val resolved = resolve(session, target)
        return launchAndAwait(pageSessions(runId), session, resolved, timeoutMs, recordUrl = true)
    }

    override fun click(runId: String, pageId: String, selector: String, timeoutMs: Long): HostAct =
        withActivity(runId, pageId) { it.click(selector, timeoutMs) }

    override fun type(runId: String, pageId: String, selector: String, text: String, timeoutMs: Long): HostAct =
        withActivity(runId, pageId) { it.type(selector, text, timeoutMs) }

    override fun extract(runId: String, pageId: String, selector: String, attribute: String): HostExtract =
        withActivity(runId, pageId) { it.extract(selector, attribute) }

    override fun cookies(runId: String, pageId: String): List<HostCookie> =
        withActivity(runId, pageId) { it.cookies() }

    override fun clearCookies(runId: String, pageId: String) {
        withActivity(runId, pageId) { activity ->
            activity.clearCookies()
            Unit
        }
    }

    override fun pages(runId: String): List<WebPage> =
        (runs[runId] ?: emptyMap()).values.map { s ->
            WebPage(
                pageId = s.pageId,
                url = s.url,
                title = s.title,
                loadState = s.loadState,
                profile = s.profile,
                provider = s.provider,
                inputMode = s.inputMode,
            )
        }

    override fun close(runId: String, pageId: String) {
        pageSessions(runId).remove(pageId)?.let { session ->
            profileLock.withLock { session.activity?.finishFromHost() }
            loadLatches.remove(pageId)
        }
    }

    // ---- lifecycle ------------------------------------------------------------

    /** Closes a page by id alone (the activity only ever knows its pageId). Idempotent. */
    fun closeByPage(pageId: String) {
        runs.values.firstOrNull { it.containsKey(pageId) }?.let { pagesOfRun ->
            pagesOfRun.remove(pageId)?.let { session ->
                profileLock.withLock { runCatching { session.activity?.finishFromHost() } }
                loadLatches.remove(pageId)
            }
        }
    }

    /** Run-terminal teardown: every page of the run finishes, latches released. Idempotent. */
    fun closeAll(runId: String) {
        runs.remove(runId)?.values?.forEach { session ->
            profileLock.withLock { runCatching { session.activity?.finishFromHost() } }
            loadLatches.remove(session.pageId)
        }
    }

    fun pageSession(runId: String, pageId: String): PageSession? = pageSessions(runId)[pageId]

    /** The activity calls back here when a load settles (any thread). */
    fun onLoadSettled(pageId: String, url: String, title: String?, loadState: String, bodySample: String?, inputMode: String) {
        runs.values.firstOrNull { it.containsKey(pageId) }?.get(pageId)?.let { session ->
            session.url = url
            session.title = title
            session.loadState = loadState
            session.bodySample = bodySample
            if (inputMode != "real") session.inputMode = inputMode
        }
        loadLatches[pageId]?.countDown()
    }

    fun registerLatch(pageId: String): CountDownLatch {
        val latch = CountDownLatch(1)
        loadLatches[pageId] = latch
        return latch
    }

    private fun pageSessions(runId: String): MutableMap<String, PageSession> = runs.computeIfAbsent(runId) { ConcurrentHashMap() }

    /** profileLock is held for the whole activity launch — the WebView must be created under the suffix. */
    fun acquireProfile(profile: String): AutoCloseable {
        profileLock.lock()
        return AutoCloseable { profileLock.unlock() }
    }

    private fun resolve(session: PageSession, target: BrowserPolicy.UrlVerdict.Allowed): ResolvedTarget = when (target.kind) {
        BrowserPolicy.UrlVerdict.Allowed.Kind.WEB -> ResolvedTarget.Web(target.target)
        BrowserPolicy.UrlVerdict.Allowed.Kind.TASK_FILE -> {
            val dir = taskBundleDir(taskIdFor(session.runId, session.pageId))
                ?: throw WebViewBrowserProvider.HostException(app.autohost.core.model.ErrorCode.INVALID_ARGS, "task bundle directory not found for this run")
            val file = File(dir, target.target)
            val canonical = runCatching { file.canonicalFile }.getOrDefault(file)
            if (!canonical.path.startsWith(dir.canonicalFile.path + File.separator)) {
                throw WebViewBrowserProvider.HostException(app.autohost.core.model.ErrorCode.SCOPE_VIOLATION, "task: path escaped the bundle directory")
            }
            if (!canonical.isFile) {
                throw WebViewBrowserProvider.HostException(app.autohost.core.model.ErrorCode.NOT_FOUND, "task file '${target.target}' is not in the bundle")
            }
            ResolvedTarget.TaskFile(canonical)
        }
    }

    private val taskIds = ConcurrentHashMap<String, String>()

    /** The wiring records taskId → runId at run start so `task:` files resolve. */
    fun bindTask(runId: String, taskId: String) {
        taskIds[runId] = taskId
    }

    private fun taskIdFor(runId: String, pageId: String): String = taskIds[runId] ?: ""

    private fun launchAndAwait(
        pages: MutableMap<String, PageSession>,
        session: PageSession,
        resolved: ResolvedTarget,
        timeoutMs: Long,
        recordUrl: Boolean,
    ): WebViewBrowserProvider.HostPage {
        val latch = registerLatch(session.pageId)
        try {
            profileLock.withLock {
                val intent = Intent(appContext, BrowserActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(BrowserActivity.EXTRA_PAGE_ID, session.pageId)
                    putExtra(BrowserActivity.EXTRA_PROFILE, session.profile)
                    when (resolved) {
                        is ResolvedTarget.Web -> putExtra(BrowserActivity.EXTRA_URL, resolved.url)
                        is ResolvedTarget.TaskFile -> putExtra(BrowserActivity.EXTRA_TASK_FILE, resolved.file.absolutePath)
                    }
                }
                appContext.startActivity(intent)
            }
            val settled = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            val state = if (settled) session.loadState else "timeout"
            if (!settled) {
                throw WebViewBrowserProvider.HostException(app.autohost.core.model.ErrorCode.TIMEOUT, "page did not settle within ${timeoutMs}ms")
            }
            return WebViewBrowserProvider.HostPage(
                pageId = session.pageId,
                url = session.url,
                title = session.title,
                loadState = state,
                profile = session.profile,
                inputMode = session.inputMode,
                bodySample = session.bodySample,
            )
        } finally {
            loadLatches.remove(session.pageId)
        }
    }

    private fun <T> withActivity(runId: String, pageId: String, block: (BrowserActivity) -> T): T {
        val session = pageSessions(runId)[pageId]
            ?: throw WebViewBrowserProvider.HostException(app.autohost.core.model.ErrorCode.NOT_FOUND, "unknown page_id '$pageId'")
        val activity = session.activity
            ?: throw WebViewBrowserProvider.HostException(app.autohost.core.model.ErrorCode.CAPABILITY_UNAVAILABLE, "the browser surface is not open (open a page first)", reason = "BROWSER_SURFACE_CLOSED")
        return block(activity)
    }

    /** Called by the activity as soon as its WebView exists. */
    fun attachActivity(pageId: String, activity: BrowserActivity) {
        runs.values.firstOrNull { it.containsKey(pageId) }?.get(pageId)?.activity = activity
    }

    fun detachActivity(pageId: String) {
        runs.values.firstOrNull { it.containsKey(pageId) }?.get(pageId)?.activity = null
    }

    sealed class ResolvedTarget {
        data class Web(val url: String) : ResolvedTarget()
        data class TaskFile(val file: File) : ResolvedTarget()
    }
}
