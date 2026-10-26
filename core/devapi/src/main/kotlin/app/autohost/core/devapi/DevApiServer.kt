package app.autohost.core.devapi

import app.autohost.core.common.AhJson
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * P2-21: the Dev API backend contract + minimal HTTP/WS server.
 *
 * The server is hand-rolled on [ServerSocket] (Android has no com.sun.httpserver): HTTP/1.1 with
 * `Connection: close`, one thread per connection, plus a minimal RFC 6455 WebSocket for event streaming.
 * It binds 127.0.0.1 only (docs/DEVAPI.md §1); the token gates every call except `GET /ping`.
 *
 * All method behaviour lives behind [DevApiBackend] so the router and server stay pure-JVM testable —
 * the real backend is wired in `:app` over the broker and engine (P2-22).
 */
interface DevApiBackend {
    fun systemInfo(): JsonObject

    fun capabilities(): JsonObject

    /** One capability call executed through the broker (device.ui.*, device.app.*). */
    fun call(capability: String, method: String, args: JsonObject): JsonObject

    fun tasksList(): JsonArray
    fun tasksGet(taskId: String): JsonObject?
    fun tasksRemove(taskId: String, keepHistory: Boolean): JsonObject
    fun tasksSetConfig(taskId: String, values: JsonObject): JsonObject
    fun runsStart(taskId: String, profileId: String?, args: JsonObject?): JsonObject
    fun runsList(): JsonArray
    fun runsStatus(runId: String): JsonObject?
    fun runsTail(runId: String, sinceSeq: Long): JsonArray
    fun runsPause(runId: String): JsonObject
    fun runsResume(runId: String): JsonObject
    fun runsCancel(runId: String): JsonObject
    fun healthRun(only: List<String>?): JsonObject

    // ---- P5-11: the full surface (docs/DEVAPI.md §3) ---------------------------------
    fun triggersList(): JsonArray
    fun triggersStatus(): JsonArray

    /** P8-03: the event-source catalog (§11.9 operations) — kinds, access, required grants, params. */
    fun triggersCatalog(): JsonObject
    fun triggerUpsert(rule: JsonObject): JsonObject
    fun triggerDelete(ruleId: String): JsonObject
    fun triggerTest(rule: JsonObject, count: Int, fire: Boolean): JsonArray
    fun healthChecks(): JsonArray
    fun runsExport(runId: String): JsonObject?
    fun recorderStart(): JsonObject
    fun recorderStop(): JsonObject
}

class DevApiPolicy(
    // Mutable: the Settings toggle and the dev_api_remote switch flip these at runtime (P8),
    // then restart() applies them — the server binds at start(), never per request.
    var enabled: Boolean = false,
    val port: Int = 8080,
    val rateLimit: Int = 20,
    var allowRemote: Boolean = false, // insecure-feature switch dev_api_remote (plan §14.5)
)

/** A WS connection that subscribed to event topics; notifications are broadcast to all of them. */
class DevApiServer(
    private val backend: DevApiBackend,
    val policy: DevApiPolicy = DevApiPolicy(),
    val tokens: TokenStore = TokenStore(),
    private val router: RpcRouter = RpcRouter(),
    private val limiter: RateLimiter = RateLimiter(policy.rateLimit),
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val connections = ConcurrentHashMap<String, WsConnection>()
    private val connCounter = AtomicLong()
    val eventTopic = Channel<Pair<String, JsonObject>>(Channel.BUFFERED) // (topic, payload)

    fun start(): Int {
        check(!running) { "server already running" }
        // dev_api_remote (plan §14.5): localhost/adb-forward unless the switch is on; the token
        // gates every call either way, and the LAN binding is the honest opt-in.
        val bind = if (policy.allowRemote) java.net.InetAddress.getByName("0.0.0.0") else java.net.InetAddress.getByName("127.0.0.1")
        val socket = ServerSocket(policy.port, 64, bind)
        serverSocket = socket
        running = true
        Thread({
            while (running) {
                try {
                    val client = socket.accept()
                    Thread({ handle(client) }, "devapi-conn-${connCounter.incrementAndGet()}").apply {
                        isDaemon = true
                        start()
                    }
                } catch (e: Exception) {
                    if (running && e !is java.net.SocketException) {
                        app.autohost.core.common.AhLog.w("devapi", "accept failed: ${e.message}")
                    }
                }
            }
        }, "devapi-accept").apply { isDaemon = true; start() }
        return socket.localPort
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        connections.values.forEach { runCatching { it.socket.close() } }
        connections.clear()
    }

    fun isRunning(): Boolean = running

    /** Rebinds with the current policy (P8: the dev_api_remote switch changes the bind address). */
    fun restart(): Int? {
        if (!running) return null
        stop()
        return try {
            start()
        } catch (e: Exception) {
            null
        }
    }

    /** In-process entry used by tests and the ContentProvider adapter (`call --method rpc`). */
    fun handleRpc(body: String, authHeader: String?, caller: String = "local"): String {
        val response = dispatch(body, authHeader, caller)
        return response.toString()
    }

    /** Core dispatch shared by HTTP, WS and the ContentProvider path. */
    fun dispatch(body: String, authHeader: String?, caller: String): JsonObject {
        if (!policy.enabled && caller == "http") {
            return JsonRpc.Response.Failure(null, "DEV_API_OFF", "dev api is disabled").toJson()
        }
        val parsed = JsonRpc.parse(body)
        val request = when (parsed) {
            is JsonRpc.Either.Left -> parsed.value
            is JsonRpc.Either.Right -> return (parsed.value as JsonRpc.Response.Failure).toJson()
        }
        if (!tokens.matches(authHeader?.removePrefix("Bearer "))) {
            return JsonRpc.Response.Failure(request.id, "UNAUTHORIZED", "missing or invalid token").toJson()
        }
        val now = System.currentTimeMillis()
        if (!limiter.allow(now)) {
            return JsonRpc.Response.Failure(request.id, "RATE_LIMITED", "too many requests").toJson()
        }
        return router.route(request, backend).toJson()
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { client ->
                client.soTimeout = 30_000
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
                val (method, path) = requestLine.split(" ").let { it[0] to it.getOrElse(1) { "/" } }
                when {
                    method == "GET" && path == "/ping" ->
                        writeHttp(client, 200, """{"status":"ok"}""")
                    method == "GET" && path == "/ws" && headers["upgrade"] == "websocket" ->
                        handleWebSocket(client, reader, headers)
                    method == "POST" && path == "/rpc" -> {
                        val length = headers["content-length"]?.toIntOrNull() ?: 0
                        val body = if (length > 0) CharArray(length).let { chars ->
                            reader.read(chars)
                            String(chars)
                        } else ""
                        val token = headers["authorization"]
                        val response = dispatch(body, token, caller = "http")
                        writeHttp(client, if ((response["error"] as? JsonObject)?.get("code")?.toString() == "UNAUTHORIZED") 401 else 200, response.toString())
                    }
                    else -> writeHttp(client, 404, """{"error":{"code":"NOT_FOUND"}}""")
                }
            }
        } catch (e: Exception) {
            runCatching { app.autohost.core.common.AhLog.w("devapi", "connection error: ${e.message}") }
        }
    }

    private fun writeHttp(socket: Socket, status: Int, body: String) {
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
        val reason = when (status) {
            200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Error"
        }
        writer.write("HTTP/1.1 $status $reason\r\n")
        writer.write("Content-Type: application/json\r\n")
        writer.write("Content-Length: ${body.encodeToByteArray().size}\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.write(body)
        writer.flush()
    }

    // ---- minimal RFC 6455 server-side WebSocket (single-frame text messages) ----

    private fun handleWebSocket(socket: Socket, reader: BufferedReader, headers: Map<String, String>) {
        val key = headers["sec-websocket-key"]
        if (key == null) {
            writeHttp(socket, 400, """{"error":{"code":"INVALID_REQUEST","message":"missing websocket key"}}""")
            return
        }
        val accept = Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-1")
                .digest((key + WS_GUID).encodeToByteArray()),
        )
        val out = socket.getOutputStream()
        val handshake = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        out.write(handshake.encodeToByteArray())
        out.flush()

        val connection = WsConnection(socket)
        val id = "ws-${connCounter.incrementAndGet()}"
        connections[id] = connection

        // reader loop: JSON-RPC requests from the PC; frames end up in dispatch()
        val raw = socket.getInputStream()
        val buffer = ByteArray(8192)
        try {
            while (running) {
                val n = raw.read(buffer)
                if (n < 0) break
                val text = decodeFrame(buffer, n) ?: continue
                val response = dispatch(text, null, caller = "ws") // token carried inside each WS request's header param
                if (response != EMPTY_OBJECT) connection.sendText(response.toString())
            }
        } catch (e: Exception) {
            // client went away
        } finally {
            connections.remove(id)
            runCatching { socket.close() }
        }
    }

    /** Broadcast a server notification `{"method":"event","params":{…}}` to all open WS connections. */
    fun broadcast(topic: String, payload: JsonObject) {
        val notification = buildJsonObject {
            put("method", JsonPrimitive("event"))
            put("params", buildJsonObject {
                put("topic", JsonPrimitive(topic))
                put("data", payload)
            })
        }.toString()
        connections.values.forEach { conn -> runCatching { conn.sendText(notification) } }
    }

    private fun decodeFrame(buffer: ByteArray, length: Int): String? {
        if (length < 2) return null
        val opcode = buffer[0].toInt() and 0x0F
        if (opcode == 0x8) return null // close
        val masked = (buffer[1].toInt() and 0x80) != 0
        var payloadLength = (buffer[1].toInt() and 0x7F).toLong()
        var offset = 2
        if (payloadLength == 126L) {
            payloadLength = ((buffer[2].toInt() and 0xFF).toLong() shl 8) or (buffer[3].toInt() and 0xFF).toLong()
            offset = 4
        } else if (payloadLength == 127L) {
            return null // 64-bit frames unsupported in v0 (requests are small)
        }
        if (!masked) return null // clients must mask
        val mask = buffer.copyOfRange(offset, offset + 4)
        offset += 4
        val end = (offset + payloadLength).toInt()
        if (end > length) return null
        val payload = buffer.copyOfRange(offset, end)
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return String(payload)
    }

    private class WsConnection(val socket: Socket) {
        private val output = socket.getOutputStream()

        @Synchronized
        fun sendText(text: String) {
            val payload = text.encodeToByteArray()
            val header = when {
                payload.size < 126 -> byteArrayOf(0x81.toByte(), payload.size.toByte())
                payload.size < 65536 -> byteArrayOf(
                    0x81.toByte(), 126.toByte(),
                    ((payload.size shr 8) and 0xFF).toByte(), (payload.size and 0xFF).toByte(),
                )
                else -> return // too large for v0 notifications
            }
            synchronized(output) {
                output.write(header)
                output.write(payload)
                output.flush()
            }
        }
    }

    companion object {
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private val EMPTY_OBJECT = AhJson.json.parseToJsonElement("{}") as JsonObject
    }
}
