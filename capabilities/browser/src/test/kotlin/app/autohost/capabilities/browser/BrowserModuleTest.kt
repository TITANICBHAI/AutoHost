package app.autohost.capabilities.browser

import app.autohost.core.broker.CallContext
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P10-02..04 JVM tests: BrowserPolicy gate chain, ChallengeDetector verdicts, the webview
 * provider over a fake host (challenge routing, permission split, page cap), the a11y link's
 * honest limits, and the CDP provider's seams. Pure JVM — no Android imports.
 */
class BrowserPolicyTest {

    private val grant = buildJsonObject {
        put("domains", JsonArray(listOf(JsonPrimitive("example.com"), JsonPrimitive("*.example.org"))))
    }

    @Test fun `http urls inside the allowlist pass`() {
        val verdict = BrowserPolicy.urlVerdict(grant, "https://example.com/page")
        assertTrue(verdict is BrowserPolicy.UrlVerdict.Allowed)
        assertEquals(BrowserPolicy.UrlVerdict.Allowed.Kind.WEB, (verdict as BrowserPolicy.UrlVerdict.Allowed).kind)
        val deeper = BrowserPolicy.urlVerdict(grant, "http://a.b.example.org/x")
        assertTrue(deeper is BrowserPolicy.UrlVerdict.Allowed)
    }

    @Test fun `bare suffix and lookalike hosts are denied`() {
        // *.example.org covers subdomains; example.org itself is not covered by the wildcard.
        val bare = BrowserPolicy.urlVerdict(
            buildJsonObject { put("domains", JsonArray(listOf(JsonPrimitive("*.example.org")))) },
            "https://example.org/",
        )
        assertTrue(bare is BrowserPolicy.UrlVerdict.Denied)
        assertTrue(BrowserPolicy.urlVerdict(grant, "https://evilexample.org/") is BrowserPolicy.UrlVerdict.Denied)
    }

    @Test fun `empty allowlist denies and null grant allows`() {
        val empty = buildJsonObject { put("domains", JsonArray(emptyList())) }
        assertTrue(BrowserPolicy.urlVerdict(empty, "https://example.com/") is BrowserPolicy.UrlVerdict.Denied)
        assertTrue(BrowserPolicy.urlVerdict(null, "https://anything.example/") is BrowserPolicy.UrlVerdict.Allowed)
    }

    @Test fun `task paths stay inside the bundle`() {
        val ok = BrowserPolicy.urlVerdict(null, "task:web/login.html")
        assertEquals(BrowserPolicy.UrlVerdict.Allowed.Kind.TASK_FILE, (ok as BrowserPolicy.UrlVerdict.Allowed).kind)
        assertEquals("web/login.html", ok.target)
        for (bad in listOf("task:../escape", "task:a/../b", "task://", "task:", "task:a\\b")) {
            assertTrue(bad, BrowserPolicy.urlVerdict(null, bad) is BrowserPolicy.UrlVerdict.Denied)
        }
    }

    @Test fun `file and javascript schemes are refused`() {
        for (url in listOf("file:///sdcard/x.txt", "javascript:alert(1)", "data:text/html,x", "about:blank")) {
            assertTrue(url, BrowserPolicy.urlVerdict(null, url) is BrowserPolicy.UrlVerdict.Denied)
        }
    }

    @Test fun `timeouts are clamped to the window`() {
        assertEquals(BrowserPolicy.DEFAULT_TIMEOUT_MS, BrowserPolicy.timeoutMs(null))
        assertEquals(1L, BrowserPolicy.timeoutMs(0))
        assertEquals(BrowserPolicy.MAX_TIMEOUT_MS, BrowserPolicy.timeoutMs(999_999))
    }

    @Test fun `permission split is open versus read`() {
        assertEquals("browser.open", BrowserPolicy.permissionFor("open"))
        assertEquals("browser.open", BrowserPolicy.permissionFor("click"))
        assertEquals("browser.read", BrowserPolicy.permissionFor("extract"))
        assertEquals("browser.read", BrowserPolicy.permissionFor("cookies"))
        assertNull(BrowserPolicy.permissionFor("nope"))
    }
}

class ChallengeDetectorTest {

    @Test fun `captcha urls are detected as captcha`() {
        val challenge = ChallengeDetector.detect("https://example.com/recaptcha/api", null, null)
        assertEquals(ChallengeDetector.Kind.CAPTCHA, challenge?.kind)
    }

    @Test fun `cloudflare style interstitials are anti-bot`() {
        assertEquals(
            ChallengeDetector.Kind.ANTI_BOT,
            ChallengeDetector.detect("https://x.com/", "Just a moment...", "Checking your browser before accessing")?.kind,
        )
        assertEquals(
            ChallengeDetector.Kind.ANTI_BOT,
            ChallengeDetector.detect("https://x.com/", null, "Verify you are human to continue")?.kind,
        )
    }

    @Test fun `rate limit and denial walls are their own kinds`() {
        assertEquals(
            ChallengeDetector.Kind.RATE_LIMIT,
            ChallengeDetector.detect("https://x.com/", null, "Unusual traffic from your computer network")?.kind,
        )
        assertEquals(
            ChallengeDetector.Kind.DENIED,
            ChallengeDetector.detect("https://x.com/", null, "Access denied | example.com")?.kind,
        )
    }

    @Test fun `ordinary login pages are NOT challenges`() {
        assertNull(ChallengeDetector.detect("https://example.com/login", "Sign in", "Username Password Log in"))
    }

    @Test fun `clean pages return null`() {
        assertNull(ChallengeDetector.detect("https://example.com/", "Welcome", "Hello world content"))
    }
}

/** Fake host recording calls; deterministic page facts. */
private class FakeHost : WebViewBrowserProvider.Host {
    val pages = mutableListOf<WebViewBrowserProvider.HostPage>()
    var nextUrl: String = "task:x.html"
    var challenge: ChallengeDetector.Challenge? = null
    val clicks = mutableListOf<String>()
    val types = mutableListOf<Pair<String, String>>()
    val closed = mutableListOf<String>()
    var cookieJar: List<HostCookie> = emptyList()

    private fun page(pageId: String) = WebViewBrowserProvider.HostPage(
        pageId, nextUrl, "Title", "loaded", "run", "real",
        if (challenge != null) "Verify you are human" else "plain body",
    )

    override fun open(runId: String, profile: String, target: BrowserPolicy.UrlVerdict.Allowed, timeoutMs: Long) =
        page("pg-${pages.size + 1}").also { pages.add(it) }

    override fun navigate(runId: String, pageId: String, target: BrowserPolicy.UrlVerdict.Allowed, timeoutMs: Long) =
        page(pageId).also { pages.add(it) }

    override fun click(runId: String, pageId: String, selector: String, timeoutMs: Long): HostAct {
        clicks.add(selector)
        return HostAct(true, true, "real")
    }

    override fun type(runId: String, pageId: String, selector: String, text: String, timeoutMs: Long): HostAct {
        types.add(selector to text)
        return HostAct(true, true, "real")
    }

    override fun extract(runId: String, pageId: String, selector: String, attribute: String) =
        HostExtract(listOf("v1", "v2"), false)

    override fun cookies(runId: String, pageId: String) = cookieJar

    override fun clearCookies(runId: String, pageId: String) {}

    override fun pages(runId: String): List<WebPage> = pages.map {
        WebPage(it.pageId, it.url, it.title, it.loadState, it.profile, "browser.webview")
    }

    override fun close(runId: String, pageId: String) {
        closed.add(pageId)
    }
}

private fun ctx(runId: String = "r1", grants: Map<String, JsonObject> = emptyMap()) =
    CallContext(runId, "task-1", "p1", grants, nowMs = 0)

class WebViewBrowserProviderTest {

    private fun granted(vararg domains: String): Map<String, JsonObject> = mapOf(
        "browser.open" to buildJsonObject { put("domains", JsonArray(domains.map { JsonPrimitive(it) })) },
        "browser.read" to buildJsonObject { put("domains", JsonArray(domains.map { JsonPrimitive(it) })) },
    )

    @Test fun `open returns the page envelope`() = runBlocking {
        val host = FakeHost()
        host.nextUrl = "task:web/login.html"
        val provider = WebViewBrowserProvider(host, { "pg-x" })
        val envelope = provider.invoke(ctx(grants = granted()), "open", buildJsonObject {
            put("url", JsonPrimitive("task:web/login.html"))
        })
        assertTrue(envelope.ok)
        val result = envelope.result!!.jsonObject
        assertEquals("pg-1", result["page_id"]!!.jsonPrimitive.content)
        assertEquals("loaded", result["load_state"]!!.jsonPrimitive.content)
        assertEquals("real", result["input_mode"]!!.jsonPrimitive.content)
    }

    @Test fun `challenge pages surface in the result`() = runBlocking {
        val host = FakeHost()
        host.nextUrl = "https://example.com/protected"
        val provider = WebViewBrowserProvider(host, { "pg-x" })
        val envelope = provider.invoke(ctx(grants = granted()), "open", buildJsonObject {
            put("url", JsonPrimitive("https://example.com/protected"))
        })
        assertTrue(envelope.ok)
        val challenge = envelope.result!!.jsonObject["challenge"]!!.jsonObject
        assertEquals("captcha", challenge["kind"]!!.jsonPrimitive.content)
    }

    @Test fun `disallowed url is a scope violation and never reaches the host`() = runBlocking {
        val host = FakeHost()
        val provider = WebViewBrowserProvider(host, { "pg-x" })
        val envelope = provider.invoke(ctx(grants = granted("example.com")), "open", buildJsonObject {
            put("url", JsonPrimitive("https://evil.org/"))
        })
        assertFalse(envelope.ok)
        assertEquals(ErrorCode.SCOPE_VIOLATION, ErrorCode.valueOf(envelope.error!!.code))
        assertTrue(host.pages.isEmpty())
    }

    @Test fun `page cap is enforced per run`() = runBlocking {
        val host = FakeHost()
        val provider = WebViewBrowserProvider(host, { "pg-x" })
        val grants = granted()
        repeat(4) { i ->
            assertTrue(
                provider.invoke(ctx(grants = grants), "open", buildJsonObject {
                    put("url", JsonPrimitive("task:p$i.html"))
                }).ok,
            )
        }
        val fifth = provider.invoke(ctx(grants = grants), "open", buildJsonObject {
            put("url", JsonPrimitive("task:p4.html"))
        })
        assertEquals(ErrorCode.SCOPE_VIOLATION, ErrorCode.valueOf(fifth.error!!.code))
    }

    @Test fun `extract needs the read permission`() = runBlocking {
        val host = FakeHost()
        val provider = WebViewBrowserProvider(host, { "pg-x" })
        val envelope = provider.invoke(
            ctx(grants = mapOf("browser.open" to buildJsonObject { })),
            "extract",
            buildJsonObject { put("page_id", JsonPrimitive("pg-1")); put("selector", JsonPrimitive("#x")) },
        )
        assertFalse(envelope.ok)
        assertEquals(ErrorCode.PERMISSION_DENIED, ErrorCode.valueOf(envelope.error!!.code))
    }

    @Test fun `type never echoes the text`() = runBlocking {
        val host = FakeHost()
        val provider = WebViewBrowserProvider(host, { "pg-x" })
        val envelope = provider.invoke(ctx(grants = granted()), "type", buildJsonObject {
            put("page_id", JsonPrimitive("pg-1"))
            put("selector", JsonPrimitive("#user"))
            put("text", JsonPrimitive("hunter2"))
        })
        assertTrue(envelope.ok)
        assertFalse(envelope.result.toString().contains("hunter2"))
        assertEquals("hunter2", host.types.single().second) // reached the host though
    }

    @Test fun `unknown page is not found`() = runBlocking {
        val provider = WebViewBrowserProvider(FakeHost(), { "pg-x" })
        val envelope = provider.invoke(ctx(grants = granted()), "close", buildJsonObject {
            put("page_id", JsonPrimitive("pg-none"))
        })
        assertEquals(ErrorCode.NOT_FOUND, ErrorCode.valueOf(envelope.error!!.code))
    }
}

/** Fake a11y link — deterministic, records what the provider asked for. */
private class FakeA11yLink : A11yBrowserProvider.Link {
    var ready: Boolean = true
    var lines: List<String> = listOf("Welcome", "Sign in")
    var lastClick: String? = null
    var backPressed: Boolean = false

    override fun waitForeground(browserPackage: String?, timeoutMs: Long) = true
    override fun clickText(text: String, timeoutMs: Long): HostAct {
        lastClick = text
        val found = lines.any { it.contains(text, ignoreCase = true) }
        return HostAct(found, found, "a11y")
    }
    override fun typeIntoFocused(text: String, timeoutMs: Long) = HostAct(true, true, "a11y")
    override fun visibleText(maxChars: Int): String? = lines.joinToString("\n").take(maxChars)
    override fun currentPackage(): String? = "com.android.chrome"
    override fun pressBack(): Boolean {
        backPressed = true
        return true
    }
}

class A11yBrowserProviderTest {

    private fun provider(link: FakeA11yLink) = A11yBrowserProvider({ true }, link)

    @Test fun `open resolves with package and a11y input mode`() = runBlocking {
        val envelope = provider(FakeA11yLink()).invoke(ctx(), "open", buildJsonObject {
            put("url", JsonPrimitive("https://example.com/"))
        })
        assertTrue(envelope.ok)
        val result = envelope.result!!.jsonObject
        assertEquals("com.android.chrome", result["package"]!!.jsonPrimitive.content)
        assertEquals("a11y", result["page"]!!.jsonObject["input_mode"]!!.jsonPrimitive.content)
    }

    @Test fun `cookies are refused with the honest reason`() = runBlocking {
        val envelope = provider(FakeA11yLink()).invoke(ctx(), "cookies", buildJsonObject { })
        assertFalse(envelope.ok)
        assertEquals(ErrorCode.CAPABILITY_UNAVAILABLE, ErrorCode.valueOf(envelope.error!!.code))
        assertEquals("COOKIES_UNSUPPORTED_LINK", envelope.error!!.reason)
    }

    @Test fun `click matches visible text only`() = runBlocking {
        val link = FakeA11yLink()
        val envelope = provider(link).invoke(ctx(), "click", buildJsonObject {
            put("selector", JsonPrimitive("sign in"))
        })
        assertTrue(envelope.ok)
        assertTrue(envelope.result!!.jsonObject["clicked"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("sign in", link.lastClick)
    }

    @Test fun `close presses back`() = runBlocking {
        val link = FakeA11yLink()
        provider(link).invoke(ctx(), "close", buildJsonObject { })
        assertTrue(link.backPressed)
    }

    @Test fun `availability follows the link readiness`() {
        val link = FakeA11yLink()
        val provider = provider(link)
        provider.setLinkReady(true)
        assertTrue(provider.availability(app.autohost.core.broker.DeviceEnv()) is app.autohost.core.model.Availability.Available)
        provider.setLinkReady(false)
        val availability = provider.availability(app.autohost.core.broker.DeviceEnv())
        assertTrue(availability is app.autohost.core.model.Availability.NeedsSetup)
        assertEquals("ACCESSIBILITY_SERVICE_OFF", (availability as app.autohost.core.model.Availability.NeedsSetup).reason)
    }
}

/** Fake CDP transport: canned targets + a scripted command log. */
private class FakeCdpTransport : CdpClient.Transport {
    val sent = mutableListOf<String>()
    var reply: String = "{\"id\":1,\"result\":{}}"
    var targets: List<CdpClient.Transport.Target> = listOf(
        object : CdpClient.Transport.Target {
            override val id = "t1"
            override val url = "https://example.com/"
            override val title = "Example"
            override val type = "page"
            override val wsPath = "/devtools/page/t1"
        },
    )

    override fun listTargets(port: Int, timeoutMs: Long) = targets
    override fun connect(port: Int, wsPath: String, timeoutMs: Long): CdpClient.Connection =
        object : CdpClient.Connection {
            override fun send(text: String) {
                sent.add(text)
            }
            override fun receive(timeoutMs: Long): String? = reply
            override fun close() {}
        }
    override fun close() {}
}

class CdpBrowserProviderTest {

    private fun provider(transport: FakeCdpTransport, shellLive: Boolean = true, exitCode: Int? = 0) =
        CdpBrowserProvider(
            shellProbe = { shellLive },
            shellExec = { _, _ -> CdpBrowserProvider.ShellExecResult(exitCode, "", if (exitCode == 0) "" else "forward failed") },
            transportFactory = { transport },
        )

    @Test fun `status reports the honest draft`() = runBlocking {
        val envelope = provider(FakeCdpTransport()).invoke(ctx(), "status", buildJsonObject { })
        assertTrue(envelope.ok)
        val result = envelope.result!!.jsonObject
        assertEquals(false, result["verified_on_hardware"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result["note"]!!.jsonPrimitive.content.contains("S27"))
    }

    @Test fun `no shell transport is needs setup`() {
        val availability = provider(FakeCdpTransport(), shellLive = false)
            .availability(app.autohost.core.broker.DeviceEnv())
        assertEquals("SHELL_TIER_NOT_PAIRED", (availability as app.autohost.core.model.Availability.NeedsSetup).reason)
    }

    @Test fun `navigate forwards through the shell then evaluates the page`() = runBlocking {
        val transport = FakeCdpTransport()
        val forwards = mutableListOf<String>()
        val p = CdpBrowserProvider(
            shellProbe = { true },
            shellExec = { command, _ ->
                forwards.add(command)
                CdpBrowserProvider.ShellExecResult(0, "", "")
            },
            transportFactory = { transport },
        )
        val envelope = p.invoke(ctx(), "navigate", buildJsonObject { put("url", JsonPrimitive("https://example.com/")) })
        assertTrue(envelope.ok)
        assertEquals(listOf("adb forward tcp:49201 localabstract:webview_devtools_remote_app.autohost"), forwards)
        assertEquals("cdp", envelope.result!!.jsonObject["input_mode"]!!.jsonPrimitive.content)
        assertTrue(transport.sent.first().contains("Page.navigate"))
    }

    @Test fun `failed forward surfaces as capability unavailable`() = runBlocking {
        val envelope = provider(FakeCdpTransport(), exitCode = 1).invoke(ctx(), "navigate", buildJsonObject {
            put("url", JsonPrimitive("https://example.com/"))
        })
        assertFalse(envelope.ok)
        assertEquals(ErrorCode.CAPABILITY_UNAVAILABLE, ErrorCode.valueOf(envelope.error!!.code))
    }

    @Test fun `close removes the forward`() = runBlocking {
        val forwards = mutableListOf<String>()
        val p = CdpBrowserProvider(
            shellProbe = { true },
            shellExec = { command, _ ->
                forwards.add(command)
                CdpBrowserProvider.ShellExecResult(0, "", "")
            },
            transportFactory = { FakeCdpTransport() },
        )
        p.invoke(ctx(), "navigate", buildJsonObject { put("url", JsonPrimitive("https://example.com/")) })
        p.closeForRun()
        assertTrue(forwards.last().startsWith("adb forward --remove tcp:"))
    }

    @Test fun `permission split follows the browser policy`() {
        val p = provider(FakeCdpTransport())
        assertEquals("browser.open", p.permissionFor("navigate", buildJsonObject { }))
        assertEquals("browser.read", p.permissionFor("screenshot", buildJsonObject { }))
    }
}
