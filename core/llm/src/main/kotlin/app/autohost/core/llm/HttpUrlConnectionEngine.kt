package app.autohost.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * P13-06 (ADR-090, `docs/MODEL_GATEWAY.md` §7): the production engine over JDK `java.net`.
 * `useCaches=false`, `instanceFollowRedirects=false` (a 3xx is returned as a status — the gateway
 * maps it to InvalidRequest; a redirect must never carry the Authorization header elsewhere),
 * request body written once, response body read with a hard cap (2 MB — placeholder), a
 * `User-Agent: AutoHost/<version>` header, connect/read timeouts from the profile.
 *
 * Cancellation (ADR-091 (9)): the blocking IO runs on the injected dispatcher; the engine
 * registers `invokeOnCompletion { disconnect() }` on the caller's Job, so cancelling the run's
 * coroutine closes the socket immediately and the blocked read throws — the call returns within
 * ~1 s and CancellationException propagates (never swallowed into an error result).
 */
class HttpUrlConnectionEngine(
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val userAgent: String = "AutoHost/1.0",
    private val maxResponseBytes: Int = 2 * 1024 * 1024,
) : LlmHttpEngine {

    override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int): HttpResponse =
        withContext(ioDispatcher) {
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
            // Cancel => disconnect => the blocked read throws IOException inside this block;
            // withContext then rethrows the caller's CancellationException (never an error value).
            val job = coroutineContext[Job]
            job?.invokeOnCompletion { connection.disconnect() }
            try {
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                val responseHeaders = connection.headerFields.entries
                    .associate { (k, v) -> (k ?: "") to (v.firstOrNull() ?: "") }
                    .filterKeys { it.isNotEmpty() }
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
}
