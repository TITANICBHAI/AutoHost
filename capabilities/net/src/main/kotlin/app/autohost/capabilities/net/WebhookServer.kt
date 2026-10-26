package app.autohost.capabilities.net

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P8-08: the `net.server` webhook endpoint (plan §6.1 W2: "Local HTTP endpoint for webhooks →
 * triggers. Localhost by default; LAN opt-in with token").
 *
 * A POST to `/hook/<name>` with the `X-AutoHost-Token` header fires the named `external` trigger
 * (§11.9) through [dispatcher]. Any other path/method is a 404/405; a wrong token is a 401 and
 * audited. The token is mandatory in both bindings — it is the only auth for the LAN case.
 *
 * Pure-JVM: the accept loop runs on a daemon thread; [dispatcher] is called on that thread and
 * must be cheap (the app wires it to TriggerController.externalFire, which is).
 */
class WebhookServer(
    private val policy: Policy = Policy(),
    private val dispatcher: (name: String, payload: JsonObject) -> Boolean,
) {
    data class Policy(
        val enabled: Boolean = false,
        val port: Int = 8082,
        val token: String = "",
        /** LAN opt-in: false binds 127.0.0.1 only (default, plan-honest). */
        val lan: Boolean = false,
    )

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    @Volatile var lastRejected: Long? = null
        private set

    fun start(): Int {
        check(running.compareAndSet(false, true)) { "webhook server already running" }
        check(policy.token.isNotBlank()) { "webhook token must not be blank" }
        val bind = if (policy.lan) InetAddress.getByName("0.0.0.0") else InetAddress.getByName("127.0.0.1")
        val socket = ServerSocket(policy.port, 32, bind)
        serverSocket = socket
        Thread({
            while (running.get()) {
                try {
                    val client = socket.accept()
                    Thread({ handle(client) }, "webhook-conn").apply { isDaemon = true; start() }
                } catch (e: Exception) {
                    if (running.get() && e !is java.net.SocketException) {
                        app.autohost.core.common.AhLog.w("webhook", "accept failed: ${e.message}")
                    }
                }
            }
        }, "webhook-accept").apply { isDaemon = true; start() }
        return socket.localPort
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
    }

    fun isRunning(): Boolean = running.get()

    private fun handle(socket: Socket) {
        try {
            socket.use { client ->
                client.soTimeout = 10_000
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
                val body = if (headers["content-length"]?.toIntOrNull() ?: 0 > 0) {
                    val length = headers["content-length"]!!.toInt().coerceAtMost(MAX_BODY)
                    val chars = CharArray(length)
                    reader.read(chars)
                    String(chars)
                } else {
                    ""
                }
                if (!method.equals("POST", ignoreCase = true)) {
                    write(client, 405, """{"error":{"code":"METHOD_NOT_ALLOWED"}}""")
                    return
                }
                val name = Regex("^/hook/([A-Za-z0-9_.-]{1,100})$").find(path)?.groupValues?.get(1)
                    ?: run {
                        write(client, 404, """{"error":{"code":"NOT_FOUND"}}""")
                        return
                    }
                val token = headers["x-autohost-token"]
                if (token == null || !constantTimeEquals(token, policy.token)) {
                    lastRejected = System.currentTimeMillis()
                    app.autohost.core.common.AhLog.w("webhook", "rejected webhook call with a bad token")
                    write(client, 401, """{"error":{"code":"UNAUTHORIZED"}}""")
                    return
                }
                val payload = parseJson(body)
                val fired = dispatcher(name, payload)
                write(client, if (fired) 200 else 200, """{"fired":$fired}""")
            }
        } catch (e: Exception) {
            runCatching { app.autohost.core.common.AhLog.w("webhook", "connection error: ${e.message}") }
        }
    }

    private fun parseJson(text: String): JsonObject = runCatching {
        app.autohost.core.common.AhJson.json.parseToJsonElement(text.ifBlank { "{}" }) as? JsonObject
    }.getOrNull() ?: JsonObject(emptyMap())

    private fun write(socket: Socket, status: Int, body: String) {
        BufferedWriter(OutputStreamWriter(socket.getOutputStream())).use { writer ->
            writer.write("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n")
            writer.write("Content-Type: application/json\r\n")
            writer.write("Content-Length: ${body.encodeToByteArray().size}\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.write(body)
            writer.flush()
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        java.security.MessageDigest.isEqual(
            Base64.getEncoder().encodeToString(a.encodeToByteArray()).encodeToByteArray(),
            Base64.getEncoder().encodeToString(b.encodeToByteArray()).encodeToByteArray(),
        )

    companion object {
        const val MAX_BODY = 64 * 1024
    }
}

/**
 * The `net.server` capability: read-only status for tasks (what the webhook endpoint can fire).
 * Configuration (enable/port/token/LAN) lives in Settings — tasks never reconfigure the server.
 */
class NetServerProvider(
    private val serverState: () -> RunningState,
    private val hookNames: () -> List<String>,
) : CapabilityProvider {
    data class RunningState(val running: Boolean, val port: Int, val lan: Boolean)

    override val capabilityId: String = "net.server"
    override val providerId: String = "net.server"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method in METHODS) "net.server" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "status" -> {
            val state = serverState()
            Envelope.ok(buildJsonObject {
                put("running", state.running)
                put("port", state.port)
                put("lan", state.lan)
                put("hook_count", hookNames().size)
            })
        }
        "hook_names" -> Envelope.ok(buildJsonObject {
            put("names", kotlinx.serialization.json.JsonArray(hookNames().map { kotlinx.serialization.json.JsonPrimitive(it) }))
        })
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown net.server method '$method'")
    }

    companion object {
        val METHODS = setOf("status", "hook_names")
    }
}
