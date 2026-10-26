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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * P10-04: the `browser.cdp` capability (plan §18 P10 "browser.cdp if viable" — ADR-071). T3:
 * talks Chrome DevTools Protocol to a debugging endpoint reached through the paired shell tier:
 *
 *   ShellProvider.run("adb forward tcp:<p> localabstract:<socket>")   ← via the shell transport
 *   → plain HTTP GET 127.0.0.1:<p>/json/list                          ← target discovery
 *   → RFC 6455 WebSocket to ws://127.0.0.1:<p>/devtools/page/<id>     ← CDP session
 *
 * The pure [CdpClient] knows the protocol; this provider owns the route and the gates:
 *  - the shell tier must be live (otherwise NEEDS_SETUP(SHELL_TIER_NOT_PAIRED));
 *  - every URL still passes the same BrowserPolicy domain allowlist as the other links;
 *  - `status` reports the honest draft verdict: UNVERIFIED-on-hardware until spike S27 measures
 *    the route (mirrors the WirelessPairingTransport draft discipline from P9-03).
 *
 * Sockets are run-scoped: [closeForRun] tears the connection + the adb forward down; the app
 * wiring calls it from the run-terminal collector.
 */
class CdpBrowserProvider(
    private val shellProbe: () -> Boolean,
    private val shellExec: (command: String, timeoutMs: Long) -> ShellExecResult,
    private val transportFactory: () -> CdpClient.Transport,
) : CapabilityProvider {

    data class ShellExecResult(val exitCode: Int?, val stdout: String, val stderr: String)

    override val capabilityId: String = "browser.cdp"
    override val providerId: String = "browser.cdp"
    override val tier: String = "T3"

    private var client: CdpClient? = null
    private var port: Int = -1
    private var wsPath: String? = null
    private var forwardActive = false

    override fun availability(env: DeviceEnv): Availability = when {
        shellProbe() -> Availability.Available
        else -> Availability.NeedsSetup(reason = "SHELL_TIER_NOT_PAIRED", fixAction = "OPEN_SHELL_SETUP")
    }

    override fun permissionFor(method: String, args: JsonObject): String? = when (method) {
        "navigate", "evaluate", "close" -> "browser.open"
        "status", "screenshot", "cookies" -> "browser.read"
        else -> null
    }

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = try {
        when (method) {
            "status" -> status(ctx)
            "navigate" -> navigate(ctx, args)
            "evaluate" -> evaluate(ctx, args)
            "screenshot" -> screenshot(ctx)
            "cookies" -> cookies(ctx)
            "close" -> close(ctx)
            else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown browser.cdp method '$method'")
        }
    } catch (e: CdpClient.CdpException) {
        Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, e.message ?: "CDP failed", reason = "CDP_SESSION_FAILED")
    }

    /** Honest route status — the draft flag mirrors the wireless-pairing discipline (P9-03). */
    private fun status(ctx: CallContext): Envelope {
        val shellLive = shellProbe()
        return Envelope.ok(
            buildJsonObject {
                put("shell_live", shellLive)
                put("route", if (shellLive) "adb.forward.localabstract" else "unavailable")
                put("verified_on_hardware", false)
                put("note", "draft: UNVERIFIED-on-hardware until spike S27 measures the route")
            },
        )
    }

    private fun ensureForward(): Int {
        if (forwardActive && port > 0) return port
        val newPort = PORT_COUNTER.incrementAndGet()
        val socket = DEVTOOLS_SOCKET_DEFAULT
        val result = shellExec("adb forward tcp:$newPort localabstract:$socket", FORWARD_TIMEOUT_MS)
        if (result.exitCode != 0) {
            throw CdpClient.CdpException("adb forward failed (exit ${result.exitCode}): ${result.stderr.take(200)}")
        }
        port = newPort
        forwardActive = true
        return port
    }

    private fun ensureClient(timeoutMs: Long): Pair<CdpClient, Int> {
        val p = ensureForward()
        val c = client ?: CdpClient(transportFactory()).also { client = it }
        if (wsPath == null) {
            val targets = c.targets(p, timeoutMs)
            val page = targets.firstOrNull { it.type == "page" }
                ?: throw CdpClient.CdpException("no debuggable page target on $DEVTOOLS_SOCKET_DEFAULT (is WebView debugging enabled?)")
            wsPath = page.wsPath
        }
        c.attach(p, wsPath ?: throw CdpClient.CdpException("attach failed"), timeoutMs)
        return c to p
    }

    private fun navigate(ctx: CallContext, args: JsonObject): Envelope {
        val url = (args["url"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"url\" is required")
        val grant = ctx.grants["browser.open"]
        when (val verdict = BrowserPolicy.urlVerdict(grant, url)) {
            is BrowserPolicy.UrlVerdict.Allowed -> if (verdict.kind == BrowserPolicy.UrlVerdict.Allowed.Kind.TASK_FILE) {
                return Envelope.err(ErrorCode.SCOPE_VIOLATION, "task: URLs do not resolve over CDP; use browser.webview")
            }
            is BrowserPolicy.UrlVerdict.Denied -> return Envelope.err(ErrorCode.SCOPE_VIOLATION, verdict.detail, fixAction = "OPEN_PERMISSION_REVIEW")
            BrowserPolicy.UrlVerdict.NoGrant -> return Envelope.err(ErrorCode.SCOPE_VIOLATION, "the browser.open grant carries no domains scope object", fixAction = "OPEN_PERMISSION_REVIEW")
        }
        val timeoutMs = BrowserPolicy.timeoutMs((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull())
        val (c, _) = ensureClient(timeoutMs)
        c.navigate(url, timeoutMs)
        val title = runCatching {
            c.evaluate("document.title", 5_000)["result"]?.let { r ->
                (r as? JsonObject)?.get("value")?.jsonPrimitive?.content
            }
        }.getOrNull()
        val sample = runCatching {
            c.evaluate("(document.body?document.body.innerText:'').slice(0,4096)", 5_000)["result"]?.let { r ->
                (r as? JsonObject)?.get("value")?.jsonPrimitive?.content
            }
        }.getOrNull()
        val challenge = ChallengeDetector.detect(url, title, sample)
        return Envelope.ok(
            buildJsonObject {
                put("url", url)
                if (title != null) put("title", title)
                put("input_mode", "cdp")
                if (challenge != null) {
                    put("challenge", buildJsonObject { put("kind", challenge.kind.name.lowercase()); put("matched", challenge.matched) })
                }
            },
        )
    }

    private fun evaluate(ctx: CallContext, args: JsonObject): Envelope {
        val expression = (args["expression"] as? JsonPrimitive)?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"expression\" is required")
        val timeoutMs = BrowserPolicy.timeoutMs((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull())
        val (c, _) = ensureClient(timeoutMs)
        val result = c.evaluate(expression, timeoutMs)
        return Envelope.ok(buildJsonObject { put("result", result["result"]) })
    }

    private fun screenshot(ctx: CallContext): Envelope {
        val (c, _) = ensureClient(BrowserPolicy.DEFAULT_TIMEOUT_MS)
        val shot = c.screenshot(BrowserPolicy.DEFAULT_TIMEOUT_MS)
        return Envelope.ok(buildJsonObject { put("data_b64", shot["data"]); put("format", "png") })
    }

    private fun cookies(ctx: CallContext): Envelope {
        val (c, _) = ensureClient(BrowserPolicy.DEFAULT_TIMEOUT_MS)
        val list = c.cookies(BrowserPolicy.DEFAULT_TIMEOUT_MS)
        return Envelope.ok(buildJsonObject { put("cookies", list["cookies"]) })
    }

    private fun close(ctx: CallContext): Envelope {
        runCatching { client?.closeTarget(5_000) }
        closeForRun()
        return Envelope.ok(buildJsonObject { put("closed", true) })
    }

    /** Run-terminal teardown: CDP session down, adb forward removed. Idempotent. */
    fun closeForRun() {
        runCatching { client?.close() }
        client = null
        wsPath = null
        if (forwardActive && port > 0) {
            runCatching { shellExec("adb forward --remove tcp:$port", FORWARD_TIMEOUT_MS) }
        }
        forwardActive = false
        port = -1
    }

    companion object {
        /** Our WebView's abstract unix socket: `webview_devtools_remote_<processName>`. */
        const val DEVTOOLS_SOCKET_DEFAULT = "webview_devtools_remote_app.autohost"
        const val FORWARD_TIMEOUT_MS: Long = 10_000
        private val PORT_COUNTER = AtomicInteger(49_200) // private dynamic range, one per process
    }
}
