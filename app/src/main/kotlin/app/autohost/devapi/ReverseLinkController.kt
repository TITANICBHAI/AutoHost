package app.autohost.devapi

import app.autohost.core.common.AhLog
import app.autohost.core.devapi.ReverseDevApiLink
import java.util.concurrent.atomic.AtomicReference

/**
 * P11-07 (ADR-081): the Android half of the reverse transport — dials the PC bridge and serves
 * the Dev API over it while the HTTP server stays untouched.
 *
 * Gates (fail closed, all logged): the Dev API must be enabled (the reverse link IS the dev api —
 * same token, same router, same rate limiter) and a bridge URL must be configured
 * (Settings → Developer → Reverse bridge, `ws://<pc-host>:<port>`). The link retries with a
 * bounded backoff while enabled; every failure is logged with its reason so the Setup screen can
 * show the honest state instead of a spinner.
 */
class ReverseLinkController(
    private val dispatcher: ReverseDevApiLink.JsonDispatcher,
    private val tokenProvider: () -> String?,
    private val enabledProvider: () -> Boolean,
) {

    @Volatile private var stopRequested = false
    private val worker = AtomicReference<Thread?>(null)
    @Volatile var state: String = "off"
        private set
    @Volatile var lastError: String? = null
        private set

    /** Starts (or restarts) the dial loop against [url] (`ws://host:port[/path]`). */
    @Synchronized
    fun start(url: String) {
        stop()
        val parsed = parseUrl(url)
        if (parsed == null) {
            lastError = "invalid bridge URL (want ws://<host>:<port>)"
            state = "error"
            AhLog.w(TAG, "reverse bridge: $lastError")
            return
        }
        if (!enabledProvider()) {
            lastError = "the dev api is off — enable it first (the reverse link is the same surface)"
            state = "error"
            AhLog.w(TAG, "reverse bridge: $lastError")
            return
        }
        stopRequested = false
        val thread = Thread({
            dialLoop(parsed.first, parsed.second, parsed.third)
        }, "devapi-reverse")
        thread.isDaemon = true
        worker.set(thread)
        state = "connecting"
        thread.start()
        AhLog.i(TAG, "reverse bridge dialing ${parsed.first}:${parsed.second}", null)
    }

    @Synchronized
    fun stop() {
        stopRequested = true
        worker.getAndSet(null)?.let { current ->
            current.interrupt()
            current.join(2_000)
        }
        state = "off"
    }

    private fun dialLoop(host: String, port: Int, path: String) {
        var backoffMs = 1_000L
        while (!stopRequested) {
            val link = ReverseDevApiLink(
                dispatcher = dispatcher,
                tokenProvider = tokenProvider,
                host = host,
                port = port,
                path = path,
                log = { level, message ->
                    if (level == "w") AhLog.w(TAG, "reverse bridge: $message", null) else AhLog.i(TAG, "reverse bridge: $message", null)
                },
            )
            state = "connecting"
            link.run()
            if (stopRequested) break
            lastError = link.lastError
            state = "reconnecting"
            try {
                Thread.sleep(backoffMs)
            } catch (e: InterruptedException) {
                break
            }
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
        state = "off"
    }

    companion object {
        private const val TAG = "devapi"

        /** Accepts `ws://host:port[/path]` (and tolerates `http://`); everything else is refused. */
        fun parseUrl(raw: String): Triple<String, Int, String>? {
            val cleaned = raw.trim().removePrefix("ws://").removePrefix("http://")
            if (cleaned == raw.trim() && !raw.trim().startsWith("ws://") && !raw.trim().startsWith("http://")) return null
            val authority = cleaned.substringBefore('/')
            val path = "/" + cleaned.substringAfter('/', "")
            val host = authority.substringBefore(':')
            val port = authority.substringAfter(':', "").toIntOrNull()
            if (host.isEmpty() || port == null || port !in 1..65535) return null
            return Triple(host, port, if (path == "/") "/reverse" else path)
        }
    }
}
