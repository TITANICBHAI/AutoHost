package app.autohost.capabilities.browser

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * P10-02: the `browser.webview` capability (plan §6.1 — T0, ADR-070). A Playwright-like subset
 * over a *visible* in-app WebView: open, navigate, click, type, extract, cookies, clear_cookies,
 * pages, close. All Android work happens behind [Host] (implemented by the app's
 * BrowserController); this file is pure JVM and enforces the gate chain:
 *
 *  1. permission (`browser.open` to act / `browser.read` to read — BrowserPolicy.permissionFor);
 *  2. URL policy (domain allowlist grant scope + the `task:` bundle-file scheme, no `file:`);
 *  3. page cap (MAX_PAGES per run) and timeout bounds;
 *  4. challenge detection on every settled load → the result carries a `challenge` object. The
 *     SDK raises NeedsUser(SECURITY_CHALLENGE) from it by default, which routes the run to
 *     WAITING_USER (never bypassed — there is no bypass path in the module). Scripts that want
 *     to observe the wall themselves pass `challenge:"report"` and get the plain dict.
 *
 * Input honesty: every act result carries `input_mode` ("real" for injected MotionEvents /
 * key events, "synthetic" for the JS fallback the S25 spike may force on old WebViews). The
 * provider never claims a synthetic input was trusted.
 */
class WebViewBrowserProvider(
    private val host: Host,
    private val newPageId: () -> String,
) : CapabilityProvider {

    /** App-side host (BrowserController). All calls are blocking; the provider bounds them. */
    interface Host {
        fun open(runId: String, profile: String, target: BrowserPolicy.UrlVerdict.Allowed, timeoutMs: Long): HostPage
        fun navigate(runId: String, pageId: String, target: BrowserPolicy.UrlVerdict.Allowed, timeoutMs: Long): HostPage
        fun click(runId: String, pageId: String, selector: String, timeoutMs: Long): HostAct
        fun type(runId: String, pageId: String, selector: String, text: String, timeoutMs: Long): HostAct
        fun extract(runId: String, pageId: String, selector: String, attribute: String): HostExtract
        fun cookies(runId: String, pageId: String): List<HostCookie>
        fun clearCookies(runId: String, pageId: String)
        fun pages(runId: String): List<WebPage>
        fun close(runId: String, pageId: String)
    }

    /** Host page snapshot handed back after open/navigate (facts the provider turns into JSON). */
    data class HostPage(
        val pageId: String,
        val url: String,
        val title: String?,
        val loadState: String,
        val profile: String,
        val inputMode: String,
        val bodySample: String?,
    )

    class HostException(val code: ErrorCode, message: String, val reason: String? = null) : Exception(message) {
        fun envelope(): Envelope = Envelope.err(code, message ?: code.name, reason = reason)
    }

    override val capabilityId: String = "browser.webview"
    override val providerId: String = "browser.webview"
    override val tier: String = "T0"

    @Volatile
    private var hostReady: Boolean = true

    /** The app wiring flips this when the device has no WebView provider at all. */
    fun setHostReady(ready: Boolean) {
        hostReady = ready
    }

    override fun availability(env: DeviceEnv): Availability = when {
        hostReady -> Availability.Available
        else -> Availability.NeedsSetup(reason = "WEBVIEW_MISSING", fixAction = "OPEN_SETUP")
    }

    override fun permissionFor(method: String, args: JsonObject): String? = BrowserPolicy.permissionFor(method)

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "open" -> open(ctx, args)
        "navigate" -> navigate(ctx, args)
        "click" -> click(ctx, args)
        "type" -> type(ctx, args)
        "extract" -> extract(ctx, args)
        "cookies" -> cookies(ctx, args)
        "clear_cookies" -> clearCookies(ctx, args)
        "pages" -> pages(ctx)
        "close" -> close(ctx, args)
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown browser.webview method '$method'")
    }

    /** The domains scope rides on either permission id of the capability (single scope object). */
    private fun grantOf(ctx: CallContext): JsonObject? = ctx.grants["browser.open"] ?: ctx.grants["browser.read"]

    private fun urlGate(ctx: CallContext, rawUrl: String): Envelope? = when (val verdict = BrowserPolicy.urlVerdict(grantOf(ctx), rawUrl)) {
        is BrowserPolicy.UrlVerdict.Allowed -> null
        is BrowserPolicy.UrlVerdict.Denied -> Envelope.err(
            ErrorCode.SCOPE_VIOLATION,
            verdict.detail,
            fixAction = "OPEN_PERMISSION_REVIEW",
        )
        BrowserPolicy.UrlVerdict.NoGrant -> Envelope.err(
            ErrorCode.SCOPE_VIOLATION,
            "the browser.open grant carries no domains scope object",
            fixAction = "OPEN_PERMISSION_REVIEW",
        )
    }

    private fun pageEnvelope(page: HostPage): Envelope {
        val challenge = ChallengeDetector.detect(page.url, page.title, page.bodySample)
        return Envelope.ok(
            buildJsonObject {
                put("page_id", page.pageId)
                put("url", page.url)
                if (page.title != null) put("title", page.title)
                put("load_state", page.loadState)
                put("profile", page.profile)
                put("input_mode", page.inputMode)
                if (challenge != null) {
                    put("challenge", buildJsonObject {
                        put("kind", challenge.kind.name.lowercase())
                        put("matched", challenge.matched)
                    })
                }
            },
        )
    }

    private fun open(ctx: CallContext, args: JsonObject): Envelope {
        val url = (args["url"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"url\" is required")
        urlGate(ctx, url)?.let { return it }
        val timeoutMs = BrowserPolicy.timeoutMs((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull())
        val profile = (args["profile"] as? JsonPrimitive)?.content ?: ctx.runId
        if (host.pages(ctx.runId).size >= BrowserPolicy.MAX_PAGES) {
            return Envelope.err(ErrorCode.SCOPE_VIOLATION, "at most ${BrowserPolicy.MAX_PAGES} pages per run")
        }
        val verdict = BrowserPolicy.urlVerdict(grantOf(ctx), url) as BrowserPolicy.UrlVerdict.Allowed
        return try {
            pageEnvelope(host.open(ctx.runId, profile, verdict, timeoutMs))
        } catch (e: HostException) {
            e.envelope()
        }
    }

    private fun navigate(ctx: CallContext, args: JsonObject): Envelope {
        val pageId = (args["page_id"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"page_id\" is required")
        val url = (args["url"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"url\" is required")
        urlGate(ctx, url)?.let { return it }
        val timeoutMs = BrowserPolicy.timeoutMs((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull())
        val verdict = BrowserPolicy.urlVerdict(grantOf(ctx), url) as BrowserPolicy.UrlVerdict.Allowed
        return try {
            pageEnvelope(host.navigate(ctx.runId, pageId, verdict, timeoutMs))
        } catch (e: HostException) {
            e.envelope()
        }
    }

    private fun click(ctx: CallContext, args: JsonObject): Envelope {
        val pageId = (args["page_id"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"page_id\" is required")
        val selector = (args["selector"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"selector\" is required")
        val timeoutMs = BrowserPolicy.timeoutMs((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull())
        return try {
            val act = host.click(ctx.runId, pageId, selector, timeoutMs)
            Envelope.ok(
                buildJsonObject {
                    put("clicked", act.performed)
                    put("found", act.found)
                    put("input_mode", act.inputMode)
                },
            )
        } catch (e: HostException) {
            e.envelope()
        }
    }

    private fun type(ctx: CallContext, args: JsonObject): Envelope {
        val pageId = (args["page_id"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"page_id\" is required")
        val selector = (args["selector"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"selector\" is required")
        val text = (args["text"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"text\" is required")
        val timeoutMs = BrowserPolicy.timeoutMs((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull())
        return try {
            val act = host.type(ctx.runId, pageId, selector, text, timeoutMs)
            Envelope.ok(
                buildJsonObject {
                    put("typed", act.performed)
                    put("found", act.found)
                    put("input_mode", act.inputMode)
                    // The text itself is NEVER echoed back: it may be a credential (redaction rule).
                },
            )
        } catch (e: HostException) {
            e.envelope()
        }
    }

    private fun extract(ctx: CallContext, args: JsonObject): Envelope {
        val pageId = (args["page_id"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"page_id\" is required")
        val selector = (args["selector"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"selector\" is required")
        val attribute = (args["attribute"] as? JsonPrimitive)?.content ?: "text"
        return try {
            val result = host.extract(ctx.runId, pageId, selector, attribute)
            Envelope.ok(
                buildJsonObject {
                    putJsonArray("values") { result.values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                    put("count", result.values.size)
                    put("truncated", result.truncated)
                },
            )
        } catch (e: HostException) {
            e.envelope()
        }
    }

    private fun cookies(ctx: CallContext, args: JsonObject): Envelope {
        val pageId = (args["page_id"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"page_id\" is required")
        return try {
            val list = host.cookies(ctx.runId, pageId)
            Envelope.ok(
                buildJsonObject {
                    putJsonArray("cookies") {
                        list.forEach { cookie ->
                            add(
                                buildJsonObject {
                                    put("name", cookie.name)
                                    put("value", cookie.value)
                                    if (cookie.domain != null) put("domain", cookie.domain)
                                    if (cookie.path != null) put("path", cookie.path)
                                    if (cookie.expiresAtMs != null) put("expires_at_ms", cookie.expiresAtMs)
                                },
                            )
                        }
                    }
                },
            )
        } catch (e: HostException) {
            e.envelope()
        }
    }

    private fun clearCookies(ctx: CallContext, args: JsonObject): Envelope {
        val pageId = (args["page_id"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"page_id\" is required")
        return try {
            host.clearCookies(ctx.runId, pageId)
            Envelope.ok(buildJsonObject { put("cleared", true) })
        } catch (e: HostException) {
            e.envelope()
        }
    }

    private fun pages(ctx: CallContext): Envelope = Envelope.ok(
        buildJsonObject {
            putJsonArray("pages") {
                host.pages(ctx.runId).forEach { page ->
                    add(
                        buildJsonObject {
                            put("page_id", page.pageId)
                            put("url", page.url)
                            if (page.title != null) put("title", page.title)
                            put("load_state", page.loadState)
                            put("profile", page.profile)
                            put("provider", page.provider)
                        },
                    )
                }
            }
        },
    )

    private fun close(ctx: CallContext, args: JsonObject): Envelope {
        val pageId = (args["page_id"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"page_id\" is required")
        host.close(ctx.runId, pageId)
        return Envelope.ok(buildJsonObject { put("closed", true) })
    }
}
