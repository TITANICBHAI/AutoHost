package app.autohost.capabilities.net

import java.net.Socket
import java.net.URI
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * P8-08: the real [WsClientProvider.SocketFactory] — plain TCP for ws://, TLS for wss://.
 * Handshake frames are written by [WsSession] on first use; this factory only opens the socket
 * (a ws handshake is sent as an HTTP upgrade over the same stream — see [WsHandshake]).
 */
class JvmWsSocketFactory : WsClientProvider.SocketFactory {
    override fun connect(url: String, subprotocols: List<String>, timeoutMs: Long): WsClientProvider.Connection {
        val uri = URI(url)
        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme == "wss" -> 443
            else -> 80
        }
        val rawSocket = Socket()
        val socket = try {
            rawSocket.connect(
                InetSocketAddress(uri.host, port),
                timeoutMs.toInt().coerceAtLeast(1),
            )
            val connected = if (uri.scheme == "wss") {
                (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(rawSocket, uri.host, port, true) as SSLSocket
            } else {
                rawSocket
            }
            connected.tcpNoDelay = true
            connected.soTimeout = timeoutMs.toInt().coerceAtLeast(1)
            if (connected is SSLSocket) connected.startHandshake()
            connected
        } catch (e: Exception) {
            runCatching { rawSocket.close() }
            throw e
        }
        val output = socket.getOutputStream()
        WsHandshake.send(output, uri, subprotocols)
        val input = socket.getInputStream()
        val acceptedSubprotocol = WsHandshake.awaitAccept(input, uri.host)
        return WsClientProvider.Connection(input, output, acceptedSubprotocol) { ms ->
            socket.soTimeout = ms.toInt().coerceAtLeast(1)
        }
    }
}

/** RFC 6455 client handshake (HTTP GET + Upgrade) over the socket stream. */
internal object WsHandshake {
    private val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    fun send(output: java.io.OutputStream, uri: URI, subprotocols: List<String>) {
        val path = uri.rawPath.orEmpty().ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
        val key = java.util.Base64.getEncoder().encodeToString(
            ByteArray(16) { (Math.random() * 256).toInt().toByte() },
        )
        val request = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: ${uri.host}\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            if (subprotocols.isNotEmpty()) append("Sec-WebSocket-Protocol: ${subprotocols.joinToString(", ")}\r\n")
            append("\r\n")
        }
        output.write(request.encodeToByteArray())
        output.flush()
    }

    /** Reads the 101 response headers; returns the negotiated subprotocol (or null). */
    fun awaitAccept(input: java.io.InputStream, host: String): String? {
        val status = readLine(input)
        if (!status.startsWith("HTTP/1.1 101")) {
            throw IllegalStateException("ws handshake refused: $status")
        }
        var subprotocol: String? = null
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val name = line.substring(0, idx).trim().lowercase()
                if (name == "sec-websocket-protocol") subprotocol = line.substring(idx + 1).trim()
            }
        }
        return subprotocol
    }

    private fun readLine(input: java.io.InputStream): String {
        val bytes = ArrayList<Byte>(64)
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) bytes.add(b.toByte())
        }
        return String(bytes.toByteArray())
    }
}
