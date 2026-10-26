package app.autohost.capabilities.shell

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive

/**
 * P9-08: the client half of the UiAutomation runner protocol (the runner lives in the app module:
 * `app.autohost.shell.UiAutomationRunner`, an Instrumentation started via `am instrument` through a
 * shell transport). Line-delimited JSON over a loopback socket:
 *
 *      → {"op":"dump"}                                  ← {"ok":true,"xml":"<hierarchy …"}
 *      → {"op":"click","index":17}                      ← {"ok":true,"performed":true}
 *      → {"op":"text","index":17,"text":"hi"}           ← {"ok":true,"performed":true}
 *      → {"op":"gesture","x1":..,"y1":..,"x2":..,"y2":..,"ms":300}  ← {"ok":true,"performed":true}
 *      → {"op":"key","code":4}                          ← {"ok":true,"performed":true}
 *      → {"op":"screenshot"}                            ← {"ok":true,"png":"<base64>"}
 *      → {"op":"ping"}                                  ← {"ok":true,"pong":true}
 *
 * The `index` is the depth-first flat position of the node inside the LAST dump both sides agree
 * on (the runner walks the tree in document order, the same order UiautomatorDump assigns indexes),
 * so the provider must re-dump before acting when its snapshot is stale.
 *
 * All ops may fail honestly: {"ok":false,"error":"NODE_NOT_FOUND"}. The socket is loopback-only and
 * the runner process is this app's own instrumentation — text set through `text` never crosses a
 * shell command line, which is why the provider CAN accept secret text (unlike ShellUiProvider).
 */
class UiAutomationSessionClient(private val connection: Connection) {

    /** Transport seam: real wiring uses a local Socket; JVM tests use in-memory streams. */
    fun interface SocketFactory {
        fun open(host: String, port: Int): Connection
    }

    interface Connection {
        fun sendLine(line: String)

        fun readLine(): String?

        fun close()
    }

    class RpcError(val code: String, detail: String) : Exception(detail)

    private fun request(obj: JsonObject): JsonObject {
        connection.sendLine(Json.encodeToString(JsonObject.serializer(), obj))
        val line = connection.readLine() ?: throw RpcError("SESSION_CLOSED", "the runner closed the connection")
        return Json.decodeFromString(JsonObject.serializer(), line)
    }

    private fun expectOk(reply: JsonObject): JsonObject {
        val ok = (reply["ok"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        if (!ok) {
            throw RpcError(
                (reply["error"] as? JsonPrimitive)?.content ?: "RUNNER_ERROR",
                (reply["detail"] as? JsonPrimitive)?.content ?: "runner refused the op",
            )
        }
        return reply
    }

    fun ping(): Boolean = try {
        expectOk(request(buildJsonObject { put("op", "ping") }))
        true
    } catch (_: Exception) {
        false
    }

    /** The runner's accessibility tree serialized in the uiautomator XML shape (parser is shared). */
    fun dump(): String = expectOk(request(buildJsonObject { put("op", "dump") }))
        .let { (it["xml"] as? JsonPrimitive)?.content ?: throw RpcError("BAD_REPLY", "dump had no xml") }

    fun click(index: Int): Boolean = perform(buildJsonObject { put("op", "click"); put("index", index) })

    fun setText(index: Int, text: String): Boolean =
        perform(buildJsonObject { put("op", "text"); put("index", index); put("text", text) })

    fun gesture(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int): Boolean = perform(
        buildJsonObject {
            put("op", "gesture"); put("x1", x1); put("y1", y1); put("x2", x2); put("y2", y2); put("ms", ms)
        },
    )

    fun key(code: Int): Boolean = perform(buildJsonObject { put("op", "key"); put("code", code) })

    /** PNG bytes of a full-device screenshot (runner-side Bitmap compress). */
    fun screenshot(): ByteArray {
        val reply = expectOk(request(buildJsonObject { put("op", "screenshot") }))
        val b64 = (reply["png"] as? JsonPrimitive)?.content ?: throw RpcError("BAD_REPLY", "screenshot had no png")
        return runCatching { java.util.Base64.getDecoder().decode(b64) }
            .getOrElse { throw RpcError("BAD_REPLY", "screenshot base64 was malformed") }
    }

    fun quit() {
        runCatching { connection.sendLine(Json.encodeToString(JsonObject.serializer(), buildJsonObject { put("op", "quit") })) }
        runCatching { connection.close() }
    }

    fun close() {
        runCatching { connection.close() }
    }

    private fun perform(op: JsonObject): Boolean =
        (expectOk(request(op))["performed"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
}
