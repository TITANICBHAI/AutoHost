package app.autohost.core.llm

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * P13-06 (ADR-090, `docs/MODEL_GATEWAY.md` §7): the production engine over JDK `java.net`.
 * `useCaches=false`, `instanceFollowRedirects=false` (a 3xx is returned as a status — the gateway
 * maps it to InvalidRequest; a redirect must never carry the Authorization header elsewhere),
 * request body written once, response body read with a hard cap (2 MB — placeholder), a
 * `User-Agent: AutoHost/<version>` header, connect/read timeouts from the profile.
 *
 * Cancellation (ADR-091 (9)): the blocking IO runs on the injected dispatcher; a watcher child
 * coroutine calls `disconnect()` when the caller is cancelled, so cancelling the run's coroutine
 * closes the socket immediately and the blocked read throws — the call returns within ~1 s and
 * CancellationException propagates (never swallowed into an error result).
 */
class HttpUrlConnectionEngine(
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val userAgent: String = "AutoHost/1.0",
    private val maxResponseBytes: Int = 2 * 1024 * 1024,
) : LlmHttpEngine {

    override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int): HttpResponse =
        coroutineScope {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", userAgent)
                for ((k, v) in headers) setRequestProperty(k, v)
                setFixedLengthStreamingMode(body.size)
            }
            // withContext(ioDispatcher) below only returns once its blocking block ends, and
            // invokeOnCompletion on its Job fires only on completion (not on cancellation), so a
            // blocked read would outlive a cancel until the socket timeout. This watcher child is
            // cancelled together with the caller; its finally closes the socket, the blocked read
            // throws, and the caller's CancellationException propagates (never an error value).
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    connection.disconnect()
                }
            }
            try {
                withContext(ioDispatcher) {
                    try {
                        connection.outputStream.use { it.write(body) }
                        val status = connection.responseCode
                        // Header names are case-insensitive on the wire (servers and proxies
                        // re-case them), and the status line comes back under a null key.
                        val responseHeaders = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
                        for ((name, values) in connection.headerFields) {
                            if (name != null && !responseHeaders.containsKey(name)) {
                                responseHeaders[name] = values.firstOrNull() ?: ""
                            }
                        }
                        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                        val text = stream?.use { input ->
                            val buffer = input.readNBytes(maxResponseBytes)
                            if (input.read() != -1) {
                                throw IOException("response body exceeds the $maxResponseBytes byte cap")
                            }
                            String(buffer, Charsets.UTF_8)
                        } ?: ""
                        HttpResponse(status, responseHeaders, text)
                    } finally {
                        connection.disconnect()
                    }
                }
            } finally {
                watcher.cancel()
            }
        }
}
