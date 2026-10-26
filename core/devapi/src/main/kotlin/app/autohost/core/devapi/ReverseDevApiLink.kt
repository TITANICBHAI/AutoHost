package app.autohost.core.devapi

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * P11-07 (ADR-081): the reverse Dev API transport — the phone dials OUT to a PC listener
 * (plan §18 P11 "reverse WebSocket transport"; rides the ADR-075 net.reverse convention:
 * the token rides the FIRST frame, never the URL) and then serves the SAME JSON-RPC surface
 * the HTTP server does, over that socket.
 *
 * Roles (RFC 6455): the PC bridge is the WS **server** (accepts, sends unmasked frames), the
 * phone is the WS **client** (connects, sends masked frames). Requests flow PC → phone and are
 * dispatched through the same [JsonDispatcher] as `DevApiServer.dispatch` (the app passes that
 * method reference, so the token check, rate limiter and router all apply unchanged; the
 * `caller` is `"reverse"` so a disabled Dev API still refuses honestly).
 *
 * Pure JVM: sockets in, JSON out, no Android imports. Tests drive it over loopback pairs
 * against a fake bridge (see ReverseDevApiLinkTest).
 */
class ReverseDevApiLink(
    private val dispatcher: JsonDispatcher,
    private val tokenProvider: () -> String?,
    private val host: String,
    private val port: Int,
    private val path: String = "/reverse",
    private val log: (level: String, message: String) -> Unit = { _, _ -> },
    /** One connect attempt budget — the PC side may not be up yet; the controller retries. */
    private val connectTimeoutMs: Int = 5_000,
) {

    fun interface JsonDispatcher {
        fun dispatch(body: String, authHeader: String?, caller: String): kotlinx.serialization.json.JsonObject
    }

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile var lastError: String? = null
        private set

    val isRunning: Boolean get() = running

    /**
     * Connects, performs the client handshake, sends the first-frame token and serves requests
     * until [stop] or a transport error. Blocks the calling thread — the controller wraps it in
     * its own retry loop on a daemon thread; tests call it from the test thread.
     */
    fun run() {
        check(!running) { "reverse link already running" }
        running = true
        try {
            val connection = Socket().apply {
                connect(InetSocketAddress(host, port), connectTimeoutMs)
                soTimeout = 0 // the link is long-lived; read() blocks until traffic or close
                tcpNoDelay = true
            }
            socket = connection
            val input = BufferedInputStream(connection.getInputStream())
            val output = BufferedOutputStream(connection.getOutputStream())
            handshake(input, output)
            sendFirstToken(output)
            serve(input, output)
        } catch (e: IOException) {
            lastError = e.message ?: "transport error"
            log("w", "reverse link transport error: $lastError")
        } finally {
            running = false
            runCatching { socket?.close() }
            socket = null
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
    }

    // ---- protocol pieces ----------------------------------------------------------------------

    /** RFC 6455 client handshake: GET + Upgrade, then verify the 101 + the Accept value. */
    private fun handshake(input: InputStream, output: OutputStream) {
        val key = WsFrames.randomKey()
        val request = "GET $path HTTP/1.1\r\n" +
            "Host: $host:$port\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"
        output.write(request.encodeToByteArray())
        output.flush()
        val statusLine = input.readLine() ?: throw IOException("bridge closed before the handshake response")
        if (!statusLine.contains(" 101 ")) throw IOException("bridge refused the upgrade: ${statusLine.trim()}")
        while (true) {
            val line = input.readLine() ?: throw IOException("bridge closed mid-handshake")
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("Sec-WebSocket-Accept", ignoreCase = true)) {
                val accept = line.substring(idx + 1).trim()
                if (accept != WsFrames.accept(key)) throw IOException("bridge accept value mismatch (not our bridge?)")
            }
        }
    }

    /** ADR-075 convention: the token rides the first frame — it never appears in a URL. */
    private fun sendFirstToken(output: OutputStream) {
        val token = tokenProvider() ?: throw IOException("no dev api token issued — enable the dev api first")
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("token", kotlinx.serialization.json.JsonPrimitive(token))
        }.toString()
        output.write(WsFrames.encodeText(payload, mask = true))
        output.flush()
    }

    /** The serve loop: unmasked server frames in (JSON-RPC requests), masked client frames out. */
    private fun serve(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        var pending = ByteArray(0)
        val token = tokenProvider()
        while (running) {
            val n = input.read(buffer)
            if (n < 0) throw IOException("bridge closed the connection")
            pending += buffer.copyOfRange(0, n)
            while (true) {
                val decoded = WsFrames.decode(pending, offset = 0, length = pending.size, expectMasked = false)
                when (decoded) {
                    is WsFrames.Decode.NeedMore -> break
                    is WsFrames.Decode.Invalid -> throw IOException("bridge sent an invalid frame: ${decoded.reason}")
                    is WsFrames.Decode.Closed -> throw IOException("bridge sent close")
                    is WsFrames.Decode.Frame -> {
                        pending = pending.copyOfRange(frameSize(pending), pending.size)
                        when (decoded.frame.opcode) {
                            WsFrames.OP_PING -> {
                                output.write(WsFrames.encode(WsFrames.OP_PONG, decoded.frame.payload, mask = true))
                                output.flush()
                            }
                            WsFrames.OP_TEXT -> {
                                val response = dispatcher.dispatch(decoded.frame.text, token?.let { "Bearer $it" }, "reverse")
                                // One text frame per response — WsFrames supports 64-bit lengths and the
                                // decoder caps at 8 MiB, far above any Dev API response.
                                output.write(WsFrames.encodeText(response.toString(), mask = true))
                                output.flush()
                            }
                            else -> Unit // pong/close handled above; anything else is ignored
                        }
                    }
                }
            }
        }
    }

    /** Re-derives the consumed byte count so the pending buffer can be trimmed (frame-in-frame). */
    private fun frameSize(buffer: ByteArray): Int {
        val b1 = buffer[1].toInt() and 0x7F
        var header = 2
        var length = b1.toLong()
        if (b1 == 126) {
            header = 4
            length = ((buffer[2].toInt() and 0xFF).toLong() shl 8) or (buffer[3].toInt() and 0xFF).toLong()
        } else if (b1 == 127) {
            header = 10
            length = 0
            for (i in 2..9) length = (length shl 8) or (buffer[i].toLong() and 0xFF)
        }
        val maskBits = buffer[1].toInt() and 0x80
        return header + (if (maskBits != 0) 4 else 0) + length.toInt()
    }

    private fun InputStream.readLine(): String? {
        val out = StringBuilder()
        while (true) {
            val c = read()
            if (c < 0) return if (out.isEmpty()) null else out.toString()
            if (c == '\n'.code) return out.toString().trimEnd('\r')
            out.append(c.toChar())
        }
    }
}
