package app.autohost.core.llm

/**
 * P13-06 (ADR-090, `docs/MODEL_GATEWAY.md` §7): the HTTP seam. The production engine is
 * `HttpUrlConnectionEngine` (JDK `java.net` only — JVM-testable against the JDK's own
 * `com.sun.net.httpserver.HttpServer`); tests inject fakes. No OkHttp/ktor exists in the tree
 * and none is added (ADR-090 context).
 *
 * Cancellation contract: the caller's coroutine cancellation must reach [post] and make it
 * return/throw within ~1 s — `HttpUrlConnectionEngine` wires `disconnect()` to that.
 */
fun interface LlmHttpEngine {
    /**
     * POSTs [body] with the given headers (Authorization included by the gateway, never logged).
     * [timeoutMs] bounds the whole attempt (connect+read from the profile, the larger wins here).
     * Never throws for HTTP error statuses — only for transport failures (returns them as a
     * failed result via the engine's own error type below keeps the seam pure; the production
     * engine throws [java.io.IOException] and the gateway classifies).
     */
    suspend fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int): HttpResponse
}

data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: String)
