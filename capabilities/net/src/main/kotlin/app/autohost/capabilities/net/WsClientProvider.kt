package app.autohost.capabilities.net

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * P8-08: the `net.ws` capability (plan §6.1 W2: "WebSocket; TCP/UDP" — TCP/UDP is W3 `net.socket`,
 * not in v1). A minimal RFC 6455 client over [SocketFactory] (mirrors the hand-rolled server side
 * in `core/devapi/DevApiServer.kt`): ws:// (plain TCP) and wss:// via SSLSocket. Text frames only
 * in v1; server pings are answered automatically during [recv]; frame cap guards the worker.
 *
 * Handles are run-scoped ([CallContext.runId]) and force-closed when the run ends
 * ([closeAllForRun], called by the app's run collector).
 */
class WsClientProvider(private val socketFactory: SocketFactory) : CapabilityProvider {

    /** Socket seam (the JVM implementation uses real sockets; tests use pipes). */
    interface SocketFactory {
        fun connect(url: String, subprotocols: List<String>, timeoutMs: Long): Connection
    }

    class Connection(
        val input: InputStream,
        val output: OutputStream,
        val subprotocol: String? = null,
        /** Optional per-read timeout hook (real sockets set soTimeout; pipes ignore it). */
        val setTimeoutMs: (Long) -> Unit = {},
    )

    override val capabilityId: String = "net.ws"
    override val providerId: String = "net.ws"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method in METHODS) "net.ws" else null

    private val handles = ConcurrentHashMap<String, WsSession>()

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        return when (method) {
        "connect" -> {
            val url = (args["url"] as? JsonPrimitive)?.content
                ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"url\" is required")
            if (!NetPolicy.schemeAllowed(url, websocket = true)) {
                return Envelope.err(ErrorCode.INVALID_ARGS, "net.ws needs a ws:// or wss:// URL")
            }
            when (val verdict = NetPolicy.domainVerdict(ctx.grants["net.ws"], url)) {
                is NetPolicy.DomainVerdict.Allowed -> {}
                is NetPolicy.DomainVerdict.NoGrant -> return Envelope.err(
                    ErrorCode.PERMISSION_DENIED, "the run has no net.ws grant",
                    fixAction = "OPEN_PERMISSION_REVIEW",
                )
                is NetPolicy.DomainVerdict.Denied -> return Envelope.err(
                    ErrorCode.SCOPE_VIOLATION,
                    "host \"${verdict.host}\" is not in this run's net.ws domain allowlist",
                    fixAction = "OPEN_PERMISSION_REVIEW",
                )
            }
            val timeout = NetPolicy.timeoutMs((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull())
            val subprotocols = (args["subprotocols"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
            return try {
                val connection = socketFactory.connect(url, subprotocols, timeout)
                val handle = "ws-${ctx.runId}-${handles.size + 1}"
                val session = WsSession(handle, connection)
                handles[handle] = session
                Envelope.ok(buildJsonObject {
                    put("handle", handle)
                    connection.subprotocol?.let { put("subprotocol", it) }
                })
            } catch (e: java.net.SocketTimeoutException) {
                Envelope.err(ErrorCode.TIMEOUT, "handshake timed out after ${timeout}ms")
            } catch (e: Exception) {
                Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "connect failed: ${e.message}")
            }
        }
        "send" -> withSession(args) { session ->
            val text = (args["text"] as? JsonPrimitive)?.content
                ?: return@withSession Envelope.err(ErrorCode.INVALID_ARGS, "\"text\" is required")
            if (text.length > MAX_FRAME_CHARS) {
                return@withSession Envelope.err(ErrorCode.INVALID_ARGS, "text exceeds the $MAX_FRAME_CHARS-char frame cap")
            }
            Envelope.ok(buildJsonObject { put("sent", session.sendText(text)) })
        }
        "recv" -> withSession(args) { session ->
            val timeout = NetPolicy.timeoutMs((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull())
            when (val frame = session.recvFrame(timeout)) {
                is Frame.Text -> Envelope.ok(buildJsonObject { put("kind", "text"); put("text", frame.text) })
                is Frame.Closed -> Envelope.ok(buildJsonObject { put("kind", "closed") })
                is Frame.TimedOut -> Envelope.err(ErrorCode.TIMEOUT, "no frame within ${timeout}ms")
                is Frame.TooLarge -> Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "frame exceeds the $MAX_FRAME_CHARS-char cap; closing")
            }
        }
        "close" -> withSession(args) { session ->
            session.close()
            handles.remove(session.handle)
            Envelope.ok(buildJsonObject { put("closed", true) })
        }
            else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown net.ws method '$method'")
        }
    }

    private inline fun withSession(args: JsonObject, block: (WsSession) -> Envelope): Envelope {
        val handle = (args["handle"] as? JsonPrimitive)?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"handle\" is required")
        val session = handles[handle]
            ?: return Envelope.err(ErrorCode.NOT_FOUND, "no open ws handle \"$handle\" (closed or run-scoped)")
        return block(session)
    }

    /** The app's run collector force-closes every handle a finished run still holds. */
    fun closeAllForRun(runId: String) {
        handles.values.filter { it.handle.startsWith("ws-$runId-") }.forEach { session ->
            runCatching { session.close() }
            handles.remove(session.handle)
        }
    }

    companion object {
        const val MAX_FRAME_CHARS = 1_000_000
        val METHODS = setOf("connect", "send", "recv", "close")
    }
}

internal sealed class Frame {
    data class Text(val text: String) : Frame()
    data object Closed : Frame()
    data object TimedOut : Frame()
    data object TooLarge : Frame()
}

/** One live RFC 6455 session: single-threaded poll-style use (send/recv from the worker thread). */
internal class WsSession(val handle: String, private val connection: WsClientProvider.Connection) {

    @Volatile
    private var closed = false

    fun sendText(text: String): Boolean {
        return try {
            synchronized(connection.output) {
                val payload = text.encodeToByteArray()
                val header = when {
                    payload.size < 126 -> byteArrayOf(0x81.toByte(), (MASKED.toInt() or payload.size).toByte())
                    payload.size < 65536 -> byteArrayOf(
                        0x81.toByte(), (MASKED.toInt() or 126).toByte(),
                        ((payload.size shr 8) and 0xFF).toByte(), (payload.size and 0xFF).toByte(),
                    )
                    else -> return false // v1 frame cap; provider validated already
                }
                connection.output.write(header)
                connection.output.write(payload)
                connection.output.flush()
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Blocking one-frame read with a deadline; server pings are answered, closes are reported. */
    fun recvFrame(timeoutMs: Long): Frame {
        return try {
            val deadline = System.currentTimeMillis() + timeoutMs
            val input = connection.input
            while (!closed) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return Frame.TimedOut
                // soTimeout governs the blocking read; poll in slices so close() can interrupt.
                connection.setTimeoutMs(minOf(remaining, POLL_SLICE_MS))
                val byte0 = try {
                    input.read()
                } catch (e: java.net.SocketTimeoutException) {
                    continue
                }
                if (byte0 < 0) {
                    closed = true
                    return Frame.Closed
                }
                val opcode = byte0 and 0x0F
                val byte1 = input.read()
                if (byte1 < 0) {
                    closed = true
                    return Frame.Closed
                }
                var length = (byte1 and 0x7F).toLong()
                if (length == 126L) {
                    length = ((input.read() and 0xFF).toLong() shl 8) or (input.read() and 0xFF).toLong()
                } else if (length == 127L) {
                    for (i in 0 until 8) input.read() // v1: no 64-bit frames; treat as too large below
                    return Frame.TooLarge
                }
                if (length > WsClientProvider.MAX_FRAME_CHARS) return Frame.TooLarge
                val mask = ByteArray(4)
                if (input.read(mask) < 0) {
                    closed = true
                    return Frame.Closed
                }
                val payload = ByteArray(length.toInt())
                var read = 0
                while (read < length) {
                    val n = input.read(payload, read, (length - read).toInt())
                    if (n < 0) {
                        closed = true
                        return Frame.Closed
                    }
                    read += n
                }
                when (opcode) {
                    0x1 -> return Frame.Text(String(payload))
                    0x8 -> { // close
                        closed = true
                        runCatching { connection.output.write(byteArrayOf(0x88.toByte(), 0)) }
                        return Frame.Closed
                    }
                    0x9 -> { // ping → pong (control frames are not delivered to the task)
                        runCatching {
                            connection.output.write(byteArrayOf(0x8A.toByte(), length.toByte()))
                            connection.output.write(payload)
                        }
                    }
                    0xA -> {} // pong — ignore
                    else -> {} // binary (0x2) etc. are skipped in v1 (text-frame contract)
                }
            }
            Frame.Closed
        } catch (e: Exception) {
            closed = true
            Frame.Closed
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching {
            connection.output.write(byteArrayOf(0x88.toByte(), 0)) // opcode 8, empty close frame
            connection.output.flush()
        }
        runCatching { connection.input.close() }
        runCatching { connection.output.close() }
    }

    companion object {
        const val MASKED = 0x80.toByte()
        const val POLL_SLICE_MS = 250L
    }
}
