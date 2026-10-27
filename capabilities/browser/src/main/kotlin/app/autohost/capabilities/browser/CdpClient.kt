package app.autohost.capabilities.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/**
 * P10-04 (ADR-071): a minimal Chrome DevTools Protocol client — the pure half of
 * `browser.cdp`. Message framing and id-correlation only; the socket layer sits behind
 * [Transport] (the app side wires: shell-tier `adb forward tcp:<p> localabstract:<socket>`
 * → plain HTTP GET /json/list → RFC 6455 WebSocket to the target's debugger URL).
 *
 * Commands supported (the subset the provider exposes): Page.navigate, Runtime.evaluate,
 * Page.captureScreenshot, Network.getCookies, Page.close. Events between responses are
 * skipped. No stealth switches exist here either — CDP talks to a debugging endpoint that
 * the user's device explicitly exposes.
 */
class CdpClient(private val transport: Transport) : Closeable {

    interface Transport : Closeable {
        data class Target(val id: String, val url: String, val title: String?, val type: String, val wsPath: String)

        /** GET http://127.0.0.1:<port>/json/list */
        fun listTargets(port: Int, timeoutMs: Long): List<Target>

        /** WebSocket to ws://127.0.0.1:<port><wsPath>; one connection per target. */
        fun connect(port: Int, wsPath: String, timeoutMs: Long): Connection
    }

    interface Connection : Closeable {
        fun send(text: String)

        /** One text frame or null on timeout. */
        fun receive(timeoutMs: Long): String?
    }

    class CdpException(message: String) : Exception(message)

    private val nextId = AtomicLong(1)
    private var connection: Connection? = null
    private var boundPort: Int = -1
    private var boundPath: String? = null

    fun targets(port: Int, timeoutMs: Long): List<Transport.Target> = transport.listTargets(port, timeoutMs)

    /** Binds this client to one page target; subsequent commands share the connection. */
    fun attach(port: Int, wsPath: String, timeoutMs: Long) {
        if (connection != null && (boundPort != port || boundPath != wsPath)) close()
        if (connection == null) {
            connection = transport.connect(port, wsPath, timeoutMs)
            boundPort = port
            boundPath = wsPath
        }
    }

    fun navigate(url: String, timeoutMs: Long): JsonObject =
        command("Page.navigate", buildJsonObject { put("url", url) }, timeoutMs)

    fun evaluate(expression: String, timeoutMs: Long): JsonObject =
        command("Runtime.evaluate", buildJsonObject { put("expression", expression); put("returnByValue", true) }, timeoutMs)

    fun screenshot(timeoutMs: Long): JsonObject =
        command("Page.captureScreenshot", buildJsonObject { put("format", "png") }, timeoutMs)

    fun cookies(timeoutMs: Long): JsonObject = command("Network.getCookies", buildJsonObject { }, timeoutMs)

    fun closeTarget(timeoutMs: Long): JsonObject = command("Page.close", buildJsonObject { }, timeoutMs)

    /** Sends one command and reads frames until the matching id replies (events skipped). */
    private fun command(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
        val conn = connection ?: throw CdpException("not attached to a CDP target")
        val id = nextId.getAndIncrement()
        conn.send(
            Json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("id", id)
                    put("method", method)
                    put("params", params)
                },
            ),
        )
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val frame = conn.receive((deadline - System.nanoTime()).coerceAtLeast(1L)) ?: continue
            val obj = runCatching { Json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: continue
            if ((obj["id"]?.jsonPrimitive?.content ?: "") != id.toString()) continue // event or older reply
            obj["error"]?.let { err ->
                val msg = runCatching { err.jsonObject["message"]?.jsonPrimitive?.content }.getOrNull() ?: "CDP error"
                throw CdpException("$method failed: $msg")
            }
            return obj["result"]?.jsonObject ?: JsonObject(emptyMap())
        }
        throw CdpException("$method timed out after ${timeoutMs}ms")
    }

    override fun close() {
        runCatching { connection?.close() }
        connection = null
        boundPort = -1
        boundPath = null
    }
}
