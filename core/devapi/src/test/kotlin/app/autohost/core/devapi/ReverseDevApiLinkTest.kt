package app.autohost.core.devapi

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * P11-07 (ADR-081): the reverse link against a fake PC bridge over real loopback sockets —
 * handshake, first-frame token, one request/response round trip, and the honest failure when
 * no token exists. (The Kotlin mirror of what `autohost_cli/bridge.py` does on the PC.)
 */
class ReverseDevApiLinkTest {

    /** Minimal fake bridge: server-role WS that validates the first-frame token and relays one RPC. */
    private class FakeBridge(
        private val serverSocket: ServerSocket,
        private val expectedToken: String,
    ) {
        lateinit var client: Socket

        fun acceptAndExpect(requestJson: String): String {
            client = serverSocket.accept()
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            // server-side handshake
            val lines = mutableListOf<String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                lines += line
            }
            val key = lines.first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: ${WsFrames.accept(key)}\r\n\r\n"
            output.write(response.encodeToByteArray())
            output.flush()
            // first frame from the phone = the token (masked, client role)
            val first = readFrame(input, expectMasked = true)
            assertEquals("""{"token":"$expectedToken"}""", first.text)
            // send one JSON-RPC request (server role → unmasked)
            output.write(WsFrames.encodeText(requestJson, mask = false))
            output.flush()
            // read the phone's response (masked)
            return readFrame(input, expectMasked = true)
        }

        private fun readFrame(input: InputStream, expectMasked: Boolean): WsFrames.Frame {
            val buffer = ByteArray(65536)
            val n = input.read(buffer)
            check(n > 0) { "bridge read failed" }
            when (val decoded = WsFrames.decode(buffer, 0, n, expectMasked)) {
                is WsFrames.Decode.Frame -> return decoded.frame
                else -> error("unexpected decode: $decoded")
            }
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

    @Test
    fun endToEndRoundTripThroughALoopbackBridge() {
        val serverSocket = ServerSocket(0)
        val bridge = FakeBridge(serverSocket, expectedToken = "tok-123")
        val dispatcher = ReverseDevApiLink.JsonDispatcher { body, auth, caller ->
            assertEquals("Bearer tok-123", auth)
            assertEquals("reverse", caller)
            kotlinx.serialization.json.buildJsonObject {
                put("echo", JsonPrimitive(body))
                put("auth_seen", JsonPrimitive(auth ?: ""))
            }
        }
        val link = ReverseDevApiLink(
            dispatcher = dispatcher,
            tokenProvider = { "tok-123" },
            host = "127.0.0.1",
            port = serverSocket.localPort,
        )
        val request = """{"jsonrpc":"2.0","id":7,"method":"system.ping"}"""
        val reply = java.util.concurrent.CompletableFuture.supplyAsync { bridge.acceptAndExpect(request) }
        Thread({ link.run() }, "reverse-link").apply { isDaemon = true; start() }
        val response = reply.get(10, TimeUnit.SECONDS)
        assertTrue(response.contains(""""id":7"""))
        assertTrue(response.contains("system.ping"))
        assertTrue(response.contains(""""auth_seen":"Bearer tok-123""""))
        assertTrue(link.lastError == null)
        serverSocket.close()
    }

    @Test
    fun missingTokenFailsHonestlyBeforeServing() {
        val serverSocket = ServerSocket(0)
        val link = ReverseDevApiLink(
            dispatcher = { _, _, _ -> kotlinx.serialization.json.buildJsonObject { put("ok", JsonPrimitive(true)) } },
            tokenProvider = { null },
            host = "127.0.0.1",
            port = serverSocket.localPort,
        )
        // The bridge side never completes a handshake; the link must die on its own.
        val thread = Thread({ link.run() }, "reverse-link-no-token").apply { isDaemon = true; start() }
        thread.join(5_000)
        assertTrue(!thread.isAlive)
        assertTrue(link.lastError?.contains("token") == true)
        serverSocket.close()
    }

    @Test
    fun refusedUpgradeIsReportedNotSwallowed() {
        val serverSocket = ServerSocket(0)
        Thread({
            val client = serverSocket.accept()
            client.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".encodeToByteArray())
            client.close()
        }).apply { isDaemon = true; start() }
        val link = ReverseDevApiLink(
            dispatcher = { _, _, _ -> kotlinx.serialization.json.buildJsonObject { put("ok", JsonPrimitive(true)) } },
            tokenProvider = { "tok" },
            host = "127.0.0.1",
            port = serverSocket.localPort,
        )
        link.run()
        assertTrue(link.lastError?.contains("403") == true)
        assertEquals(false, link.isRunning)
        serverSocket.close()
    }
}
