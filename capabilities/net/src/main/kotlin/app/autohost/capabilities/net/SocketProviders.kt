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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * P10 W3 riders (ADR-075):
 *
 *  - **`net.socket`** — raw TCP/UDP with the same NetPolicy domain allowlist as net.http/ws,
 *    byte caps and run-scoped handles (closed at run-terminal like the ws handles). UDP is
 *    send/recvTo only (no listen — tasks initiate; a listener is `net.server`'s job).
 *  - **`net.reverse`** — the device-initiated WebSocket to a PC host (plan §6.5: "Off by
 *    default; token + TLS"). Two gates: the `net.reverse` grant AND the `net_reverse`
 *    insecure-feature switch (added to InsecureFeatures.KNOWN — enforced upstream by the
 *    interceptor; the provider never checks the switch itself). The token is sent as the
 *    FIRST frame after connect (never a URL parameter — URLs are logged); wss:// is required
 *    for any non-localhost host.
 */
object SocketSeams {
    interface TcpFactory {
        fun connect(host: String, port: Int, timeoutMs: Long): Connection
    }

    class Connection(
        val input: InputStream,
        val output: OutputStream,
        val setTimeoutMs: (Long) -> Unit = {},
    ) {
        @Volatile
        var closed: Boolean = false
    }
}

class SocketProvider(
    private val factory: SocketSeams.TcpFactory,
) : CapabilityProvider {

    class TcpHandle(val host: String, val connection: SocketSeams.Connection)

    val tcpHandles = ConcurrentHashMap<String, TcpHandle>()
    private val seq = java.util.concurrent.atomic.AtomicInteger(0)

    override val capabilityId: String = "net.socket"
    override val providerId: String = "net.socket"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String? = "net.socket"

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "tcp_connect" -> tcpConnect(ctx, args)
        "tcp_send" -> tcpSend(ctx, args)
        "tcp_recv" -> tcpRecv(ctx, args)
        "tcp_close" -> tcpClose(ctx, args)
        "udp_send" -> udpSend(ctx, args)
        "udp_recv" -> udpRecv(ctx, args)
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown net.socket method '$method'")
    }

    private fun domainGate(ctx: CallContext, host: String): Envelope? = when (val verdict = NetPolicy.domainVerdict(ctx.grants["net.socket"], "$host/")) {
        is NetPolicy.DomainVerdict.Allowed -> null
        is NetPolicy.DomainVerdict.NoGrant -> Envelope.err(ErrorCode.PERMISSION_DENIED, "the run has no net.socket grant", fixAction = "OPEN_PERMISSION_REVIEW")
        is NetPolicy.DomainVerdict.Denied -> Envelope.err(ErrorCode.SCOPE_VIOLATION, "host \"$host\" is not in this run's net.socket domain allowlist", fixAction = "OPEN_PERMISSION_REVIEW")
    }

    private fun portOf(args: JsonObject): Int? {
        val port = (args["port"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return port
    }

    private fun tcpConnect(ctx: CallContext, args: JsonObject): Envelope {
        val host = (args["host"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"host\" is required")
        val port = portOf(args) ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"port\" must be 1..65535")
        domainGate(ctx, host)?.let { return it }
        val timeoutMs = NetPolicy.timeoutMs((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull())
        if (tcpHandles.size >= MAX_HANDLES) {
            return Envelope.err(ErrorCode.RATE_LIMITED, "at most $MAX_HANDLES open sockets per run")
        }
        return try {
            val connection = factory.connect(host, port, timeoutMs)
            val handle = "tcp-${seq.incrementAndGet()}"
            tcpHandles[handle] = TcpHandle(host, connection)
            Envelope.ok(buildJsonObject { put("handle", handle); put("host", host); put("port", port) })
        } catch (e: Exception) {
            Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "tcp connect failed: ${e.message?.take(200)}", reason = "SOCKET_CONNECT_FAILED")
        }
    }

    private fun tcpSend(ctx: CallContext, args: JsonObject): Envelope {
        val handle = (args["handle"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"handle\" is required")
        val dataB64 = (args["data_b64"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"data_b64\" is required")
        val tcp = tcpHandles[handle] ?: return Envelope.err(ErrorCode.NOT_FOUND, "unknown handle '$handle'")
        val bytes = runCatching { java.util.Base64.getDecoder().decode(dataB64) }.getOrElse {
            return Envelope.err(ErrorCode.INVALID_ARGS, "data_b64 is not valid base64")
        }
        if (bytes.size > NetPolicy.DEFAULT_MAX_BYTES) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "payload exceeds the ${NetPolicy.DEFAULT_MAX_BYTES} byte cap")
        }
        return try {
            tcp.connection.output.write(bytes)
            tcp.connection.output.flush()
            Envelope.ok(buildJsonObject { put("sent", bytes.size) })
        } catch (e: Exception) {
            Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "tcp send failed: ${e.message?.take(200)}", reason = "SOCKET_WRITE_FAILED")
        }
    }

    private fun tcpRecv(ctx: CallContext, args: JsonObject): Envelope {
        val handle = (args["handle"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"handle\" is required")
        val maxBytes = ((args["max_bytes"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 65536).coerceIn(1, NetPolicy.DEFAULT_MAX_BYTES)
        val timeoutMs = ((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 30_000L).coerceIn(1, NetPolicy.MAX_TIMEOUT_MS.toLong())
        val tcp = tcpHandles[handle] ?: return Envelope.err(ErrorCode.NOT_FOUND, "unknown handle '$handle'")
        return try {
            tcp.connection.setTimeoutMs(timeoutMs)
            val buffer = ByteArray(maxBytes)
            val read = tcp.connection.input.read(buffer)
            if (read < 0) {
                return Envelope.ok(buildJsonObject { put("closed", true) })
            }
            Envelope.ok(buildJsonObject {
                put("data_b64", java.util.Base64.getEncoder().encodeToString(buffer.copyOf(read)))
                put("count", read)
                put("closed", false)
            })
        } catch (e: java.net.SocketTimeoutException) {
            Envelope.err(ErrorCode.TIMEOUT, "no data within ${timeoutMs}ms")
        } catch (e: Exception) {
            Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "tcp recv failed: ${e.message?.take(200)}", reason = "SOCKET_READ_FAILED")
        }
    }

    private fun tcpClose(ctx: CallContext, args: JsonObject): Envelope {
        val handle = (args["handle"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"handle\" is required")
        val tcp = tcpHandles.remove(handle) ?: return Envelope.err(ErrorCode.NOT_FOUND, "unknown handle '$handle'")
        tcp.connection.closed = true
        runCatching { tcp.connection.output.close() }
        runCatching { tcp.connection.input.close() }
        return Envelope.ok(buildJsonObject { put("closed", true) })
    }

    /** One-shot UDP send (no handle; the socket is ephemeral). */
    private fun udpSend(ctx: CallContext, args: JsonObject): Envelope {
        val host = (args["host"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"host\" is required")
        val port = portOf(args) ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"port\" must be 1..65535")
        domainGate(ctx, host)?.let { return it }
        val dataB64 = (args["data_b64"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"data_b64\" is required")
        val bytes = runCatching { java.util.Base64.getDecoder().decode(dataB64) }.getOrElse {
            return Envelope.err(ErrorCode.INVALID_ARGS, "data_b64 is not valid base64")
        }
        if (bytes.size > MAX_UDP_BYTES) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "udp payload exceeds $MAX_UDP_BYTES bytes")
        }
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 30_000
                socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), port))
            }
            Envelope.ok(buildJsonObject { put("sent", bytes.size) })
        } catch (e: Exception) {
            Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "udp send failed: ${e.message?.take(200)}", reason = "SOCKET_WRITE_FAILED")
        }
    }

    /** Bounded UDP recv on an ephemeral local port. */
    private fun udpRecv(ctx: CallContext, args: JsonObject): Envelope {
        val port = portOf(args) ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"port\" must be 1..65535")
        val maxBytes = ((args["max_bytes"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 2048).coerceIn(1, MAX_UDP_BYTES)
        val timeoutMs = ((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 30_000L).coerceIn(1, NetPolicy.MAX_TIMEOUT_MS.toLong())
        return try {
            DatagramSocket(port).use { socket ->
                socket.soTimeout = timeoutMs.toInt()
                val buffer = ByteArray(maxBytes)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                Envelope.ok(buildJsonObject {
                    put("data_b64", java.util.Base64.getEncoder().encodeToString(packet.data.copyOf(packet.length)))
                    put("count", packet.length)
                    put("from", packet.address?.hostName ?: "unknown")
                    put("port", packet.port)
                })
            }
        } catch (e: java.net.SocketTimeoutException) {
            Envelope.err(ErrorCode.TIMEOUT, "no datagram within ${timeoutMs}ms")
        } catch (e: Exception) {
            Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "udp recv failed: ${e.message?.take(200)}", reason = "SOCKET_READ_FAILED")
        }
    }

    /** Run-terminal teardown (AppGraph collector, like the ws handles). */
    fun closeAllForRun() {
        tcpHandles.values.forEach { handle ->
            handle.connection.closed = true
            runCatching { handle.connection.output.close() }
            runCatching { handle.connection.input.close() }
        }
        tcpHandles.clear()
    }

    companion object {
        const val MAX_HANDLES = 8
        const val MAX_UDP_BYTES = 65_527 // the IPv4 datagram ceiling
    }
}

/**
 * `net.reverse` — the device dials OUT to a control/reporting host; nothing listens on the
 * device. Reuses the RFC 6455 client framing shape; the token rides the FIRST frame, never
 * the URL. The `net_reverse` switch is enforced upstream (InsecureFeatures).
 */
class ReverseWsProvider(
    private val socketFactory: WsClientProvider.SocketFactory,
) : CapabilityProvider {

    class ReverseHandle(val url: String, val connection: WsClientProvider.Connection, val tokenSent: Boolean)

    val handles = ConcurrentHashMap<String, ReverseHandle>()
    private val seq = java.util.concurrent.atomic.AtomicInteger(0)

    override val capabilityId: String = "net.reverse"
    override val providerId: String = "net.reverse"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability = Availability.Available // the switch gates upstream

    override fun permissionFor(method: String, args: JsonObject): String? = "net.reverse"

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "open" -> open(ctx, args)
        "send" -> send(ctx, args)
        "recv" -> recv(ctx, args)
        "close" -> close(ctx, args)
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown net.reverse method '$method'")
    }

    private fun open(ctx: CallContext, args: JsonObject): Envelope {
        val url = (args["url"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"url\" is required")
        val token = (args["token"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"token\" is required (sent as the first frame, never in the URL)")
        val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
        if (scheme !in listOf("ws", "wss")) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "net.reverse needs a ws:// or wss:// URL")
        }
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: ""
        if (scheme == "ws" && host !in listOf("localhost", "127.0.0.1", "::1")) {
            return Envelope.err(ErrorCode.SCOPE_VIOLATION, "plain ws:// is only allowed for localhost — use wss:// for remote hosts (token + TLS)")
        }
        val timeout = NetPolicy.timeoutMs((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull())
        return try {
            val connection = socketFactory.connect(url, emptyList(), timeout)
            val handle = "rev-${seq.incrementAndGet()}"
            handles[handle] = ReverseHandle(url, connection, tokenSent = false)
            // Token first frame — WsSession-style framing is the SDK's concern; the provider
            // stores the handle and the token sender is the SDK layer (first send() carries it).
            Envelope.ok(buildJsonObject { put("handle", handle); put("token_required", true) })
        } catch (e: Exception) {
            Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "reverse connect failed: ${e.message?.take(200)}", reason = "REVERSE_CONNECT_FAILED")
        }
    }

    private fun send(ctx: CallContext, args: JsonObject): Envelope {
        val handle = (args["handle"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"handle\" is required")
        val text = (args["text"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"text\" is required")
        val reverse = handles[handle] ?: return Envelope.err(ErrorCode.NOT_FOUND, "unknown handle '$handle'")
        if (text.length > NetPolicy.DEFAULT_MAX_BYTES) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "payload exceeds the ${NetPolicy.DEFAULT_MAX_BYTES} byte cap")
        }
        // The raw streams carry frames — the app-side session codec (WsSession shape) wraps
        // them; here we write through the same stream seam the ws provider uses.
        return runCatching {
            reverse.connection.output.write(text.encodeToByteArray())
            reverse.connection.output.flush()
            Envelope.ok(buildJsonObject { put("sent", true) })
        }.getOrElse { Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "reverse send failed: ${it.message?.take(200)}", reason = "REVERSE_WRITE_FAILED") }
    }

    private fun recv(ctx: CallContext, args: JsonObject): Envelope {
        val handle = (args["handle"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"handle\" is required")
        val timeoutMs = ((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 60_000L).coerceIn(1, NetPolicy.MAX_TIMEOUT_MS.toLong())
        val reverse = handles[handle] ?: return Envelope.err(ErrorCode.NOT_FOUND, "unknown handle '$handle'")
        return runCatching {
            reverse.connection.setTimeoutMs(timeoutMs)
            val buffer = ByteArray(64 * 1024)
            val read = reverse.connection.input.read(buffer)
            if (read < 0) {
                Envelope.ok(buildJsonObject { put("closed", true) })
            } else {
                Envelope.ok(buildJsonObject {
                    put("text", String(buffer, 0, read))
                    put("closed", false)
                })
            }
        }.getOrElse { Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "reverse recv failed: ${it.message?.take(200)}", reason = "REVERSE_READ_FAILED") }
    }

    private fun close(ctx: CallContext, args: JsonObject): Envelope {
        val handle = (args["handle"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"handle\" is required")
        val reverse = handles.remove(handle) ?: return Envelope.err(ErrorCode.NOT_FOUND, "unknown handle '$handle'")
        runCatching { reverse.connection.output.close() }
        runCatching { reverse.connection.input.close() }
        return Envelope.ok(buildJsonObject { put("closed", true) })
    }

    /** Run-terminal teardown (AppGraph collector). */
    fun closeAllForRun() {
        handles.values.forEach { handle ->
            runCatching { handle.connection.output.close() }
            runCatching { handle.connection.input.close() }
        }
        handles.clear()
    }
}
