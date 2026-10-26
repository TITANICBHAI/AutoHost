package app.autohost.capabilities.net

import app.autohost.core.broker.CallContext
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/** P8-08: domain allowlist + scheme rules for net.http / net.ws. */
class NetPolicyTest {

    private val grant = buildJsonObject {
        put("domains", JsonArray(listOf(JsonPrimitive("example.com"), JsonPrimitive("*.example.org"))))
    }

    @Test fun `exact host match only`() {
        assertEquals(NetPolicy.DomainVerdict.Allowed, NetPolicy.domainVerdict(grant, "https://example.com/a"))
        assertEquals(NetPolicy.DomainVerdict.Allowed::class, NetPolicy.domainVerdict(grant, "https://EXAMPLE.com/a")::class)
        assertEquals(NetPolicy.DomainVerdict.Denied::class, NetPolicy.domainVerdict(grant, "https://sub.example.com/a")::class)
        assertEquals(NetPolicy.DomainVerdict.Denied::class, NetPolicy.domainVerdict(grant, "https://evilexample.com/a")::class)
    }

    @Test fun `wildcard covers deeper labels but not the bare domain`() {
        assertEquals(NetPolicy.DomainVerdict.Allowed, NetPolicy.domainVerdict(grant, "https://a.example.org/x"))
        assertEquals(NetPolicy.DomainVerdict.Allowed, NetPolicy.domainVerdict(grant, "https://a.b.example.org/x"))
        assertEquals(NetPolicy.DomainVerdict.Denied::class, NetPolicy.domainVerdict(grant, "https://example.org/x")::class)
    }

    @Test fun `no grant is unrestricted, empty list denies everything`() {
        assertEquals(NetPolicy.DomainVerdict.Allowed, NetPolicy.domainVerdict(null, "https://anywhere.dev/"))
        assertEquals(NetPolicy.DomainVerdict.Denied::class, NetPolicy.domainVerdict(
            buildJsonObject { put("domains", JsonArray(emptyList())) }, "https://anywhere.dev/",
        )::class)
    }

    @Test fun `schemes are split per capability`() {
        assertTrue(NetPolicy.schemeAllowed("https://x.dev/", websocket = false))
        assertTrue(!NetPolicy.schemeAllowed("ws://x.dev/", websocket = false))
        assertTrue(NetPolicy.schemeAllowed("ws://x.dev/", websocket = true))
        assertTrue(!NetPolicy.schemeAllowed("file://x/", websocket = true))
    }

    @Test fun `sensitive headers are redacted for logs`() {
        val redacted = NetPolicy.redactHeaders(mapOf("Authorization" to "Bearer x", "X-Api-Key" to "k", "Accept" to "*/*"))
        assertEquals("«redacted»", redacted["Authorization"])
        assertEquals("«redacted»", redacted["X-Api-Key"])
        assertEquals("*/*", redacted["Accept"])
    }
}

/** P8 first-build gap closed: the net.http provider mapping. */
class HttpProviderTest {

    private class FakeEngine(var status: Int = 200, var body: ByteArray = "ok".encodeToByteArray()) : HttpProvider.Engine {
        var lastUrl: String? = null
        var lastMethod: String? = null
        var truncated = false
        override fun request(method: String, url: String, headers: Map<String, String>, bodyBytes: ByteArray?, timeoutMs: Long, maxBytes: Int): HttpProvider.Engine.Response {
            lastUrl = url
            lastMethod = method
            return HttpProvider.Engine.Response(status, mapOf("Content-Type" to "text/plain"), body, truncated)
        }
    }

    private fun ctx(grant: JsonObject? = null) = CallContext(
        "r", "t", null,
        if (grant == null) emptyMap() else mapOf("net.http" to grant),
        0,
    )

    @Test fun `request without scope is unrestricted`() = runBlocking {
        val engine = FakeEngine()
        val provider = HttpProvider(engine)
        val result = provider.invoke(ctx(), "request", buildJsonObject { put("url", "https://example.com/api"); put("method", "GET") })
        assertEquals(true, result.ok)
        assertEquals(200, result.result!!.jsonObject["status"]!!.jsonPrimitive.content.toInt())
        assertEquals("https://example.com/api", engine.lastUrl)
    }

    @Test fun `out-of-allowlist host is a scope violation`() = runBlocking {
        val provider = HttpProvider(FakeEngine())
        val grant = buildJsonObject { put("domains", JsonArray(listOf(JsonPrimitive("example.com")))) }
        val result = provider.invoke(ctx(grant), "request", buildJsonObject { put("url", "https://other.dev/api") })
        assertEquals(ErrorCode.SCOPE_VIOLATION.name, result.error?.code)
    }

    @Test fun `non-http scheme is invalid args`() = runBlocking {
        val provider = HttpProvider(FakeEngine())
        val result = provider.invoke(ctx(), "request", buildJsonObject { put("url", "ftp://example.com/x") })
        assertEquals(ErrorCode.INVALID_ARGS.name, result.error?.code)
    }

    @Test fun `body is marshalled as b64 and headers echo redacted`() = runBlocking {
        val engine = FakeEngine()
        val provider = HttpProvider(engine)
        val result = provider.invoke(ctx(), "request", buildJsonObject {
            put("url", "https://example.com/api")
            put("method", "POST")
            put("body", "hello")
            put("headers", buildJsonObject { put("Authorization", "Bearer secret") })
        })
        assertEquals(true, result.ok)
        assertEquals("POST", engine.lastMethod)
        assertEquals(
            "«redacted»",
            (result.result!!.jsonObject["headers"]!!.jsonObject["Authorization"] as JsonPrimitive).content,
        )
    }
}

/** P8-08: net.ws over an in-memory pipe connection. */
class WsClientProviderTest {

    private class PipeFactory : WsClientProvider.SocketFactory {
        val serverFrames = ArrayDeque<String>()
        val sentByClient = mutableListOf<String>()
        override fun connect(url: String, subprotocols: List<String>, timeoutMs: Long): WsClientProvider.Connection {
            // Client writes here; the "server" (test) reads/writes raw RFC6455 frames below.
            val clientOut = ByteArrayOutputStream()
            val serverToClient = PipedInputStream()
            val serverWrite = PipedOutputStream(serverToClient)
            val clientIn = ByteArrayInputStream(ByteArray(0))
            return WsClientProvider.Connection(
                BlockingTestInput(serverToClient), clientOut, subprotocol = "chat",
            ).also { connections[url] = Triple(serverWrite, clientOut, serverToClient) }
        }
        val connections = mutableMapOf<String, Triple<PipedOutputStream, ByteArrayOutputStream, PipedInputStream>>()
    }

    /** A PipedInputStream subclass that keeps the pipe open instead of EOF-ing when nobody writes. */
    private class BlockingTestInput(private val source: PipedInputStream) : java.io.InputStream() {
        override fun read(): Int = try {
            source.read()
        } catch (e: java.io.IOException) {
            -1
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int = try {
            source.read(b, off, len)
        } catch (e: java.io.IOException) {
            -1
        }
    }

    private fun ctx() = CallContext("r", "t", null, emptyMap(), 0)

    @Test fun `connect returns a handle with the negotiated subprotocol`() = runBlocking {
        val factory = PipeFactory()
        val provider = WsClientProvider(factory)
        val result = provider.invoke(ctx(), "connect", buildJsonObject {
            put("url", "ws://example.org/feed"); put("subprotocols", JsonArray(listOf(JsonPrimitive("chat"))))
        })
        assertEquals(true, result.ok)
        assertEquals("chat", result.result!!.jsonObject["subprotocol"]!!.jsonPrimitive.content)
        assertTrue(result.result!!.jsonObject["handle"]!!.jsonPrimitive.content.startsWith("ws-r-"))
    }

    @Test fun `unknown host is a scope violation and unknown handle not found`() = runBlocking {
        val grant = buildJsonObject { put("domains", JsonArray(listOf(JsonPrimitive("example.org")))) }
        val provider = WsClientProvider(PipeFactory())
        val denied = provider.invoke(
            CallContext("r", "t", null, mapOf("net.ws" to grant), 0),
            "connect", buildJsonObject { put("url", "ws://other.dev/feed") },
        )
        assertEquals(ErrorCode.SCOPE_VIOLATION.name, denied.error?.code)
        val missing = provider.invoke(ctx(), "send", buildJsonObject { put("handle", "ws-nope"); put("text", "x") })
        assertEquals(ErrorCode.NOT_FOUND.name, missing.error?.code)
    }

    @Test fun `send writes a masked text frame`() = runBlocking {
        val factory = PipeFactory()
        val provider = WsClientProvider(factory)
        val connect = provider.invoke(ctx(), "connect", buildJsonObject { put("url", "ws://example.org/feed") })
        val handle = connect.result!!.jsonObject["handle"]!!.jsonPrimitive.content
        val sent = provider.invoke(ctx(), "send", buildJsonObject { put("handle", handle); put("text", "hi") })
        assertEquals(true, sent.result!!.jsonObject["sent"]!!.jsonPrimitive.content.toBooleanStrict())
        val (_, clientOut, _) = factory.connections.getValue("ws://example.org/feed")
        val bytes = clientOut.toByteArray()
        // frame: FIN+text opcode, masked length 2
        assertEquals(0x81.toByte(), bytes[0])
        assertEquals(0x82.toByte(), bytes[1])
        assertEquals(4, bytes.size) // 2 header + 2 masked payload
    }

    @Test fun `closeAllForRun force-closes run handles`() = runBlocking {
        val factory = PipeFactory()
        val provider = WsClientProvider(factory)
        provider.invoke(ctx(), "connect", buildJsonObject { put("url", "ws://example.org/feed") })
        provider.closeAllForRun("r")
        val gone = provider.invoke(ctx(), "send", buildJsonObject {
            val handle = "ws-r-1"
            put("handle", handle); put("text", "x")
        })
        assertEquals(ErrorCode.NOT_FOUND.name, gone.error?.code)
    }
}

/** P8-08: webhook endpoint over a real loopback socket (JVM-testable). */
class WebhookServerTest {

    private class Recorder {
        val calls = mutableListOf<Pair<String, kotlinx.serialization.json.JsonObject>>()
        var answer = true
    }

    @Test fun `hook fires the named external trigger`() {
        val recorder = Recorder()
        val server = WebhookServer(
            WebhookServer.Policy(enabled = true, port = 0, token = "tok-1"),
        ) { name, payload ->
            recorder.calls.add(name to payload)
            recorder.answer
        }
        val port = server.start()
        try {
            val response = post(port, "/hook/inbox", "tok-1", """{"from":"a@b.c"}""")
            assertTrue(response.contains("\"fired\":true"))
            assertEquals("inbox", recorder.calls.single().first)
            assertEquals("a@b.c", recorder.calls.single().second["from"]!!.jsonPrimitive.content)
        } finally {
            server.stop()
        }
    }

    @Test fun `wrong token is rejected with 401 and audited`() {
        val recorder = Recorder()
        val server = WebhookServer(
            WebhookServer.Policy(enabled = true, port = 0, token = "tok-1"),
        ) { _, _ -> true }
        val port = server.start()
        try {
            val response = post(port, "/hook/inbox", "wrong", "{}")
            assertTrue(response.contains("UNAUTHORIZED"))
            assertTrue(recorder.calls.isEmpty())
            assertTrue(server.lastRejected != null)
        } finally {
            server.stop()
        }
    }

    @Test fun `unknown hook path is a 404`() {
        val server = WebhookServer(
            WebhookServer.Policy(enabled = true, port = 0, token = "tok-1"),
        ) { _, _ -> true }
        val port = server.start()
        try {
            val response = post(port, "/nope/inbox", "tok-1", "{}")
            assertTrue(response.contains("NOT_FOUND"))
        } finally {
            server.stop()
        }
    }

    private fun post(port: Int, path: String, token: String, body: String): String {
        val socket = java.net.Socket("127.0.0.1", port)
        socket.use { client ->
            client.soTimeout = 5000
            val writer = client.getOutputStream()
            writer.write("POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\nX-AutoHost-Token: $token\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body".encodeToByteArray())
            writer.flush()
            return String(client.getInputStream().readBytes())
        }
    }
}

/** P8-08: net.server status/hook_names provider. */
class NetServerProviderTest {

    private fun ctx() = CallContext("r", "t", null, emptyMap(), 0)

    @Test fun `status reflects the running state`() = runBlocking {
        val provider = NetServerProvider({ NetServerProvider.RunningState(true, 8082, false) }) { listOf("a", "b") }
        val status = provider.invoke(ctx(), "status", JsonObject(emptyMap()))
        assertEquals(true, status.result!!.jsonObject["running"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals(8082, status.result!!.jsonObject["port"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, status.result!!.jsonObject["hook_count"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `hook names list what webhooks can fire`() = runBlocking {
        val provider = NetServerProvider({ NetServerProvider.RunningState(false, 8082, false) }) { listOf("inbox") }
        val names = provider.invoke(ctx(), "hook_names", JsonObject(emptyMap()))
        assertEquals("inbox", (names.result!!.jsonObject["names"] as JsonArray).first().jsonPrimitive.content)
        assertTrue(names.ok)
    }
}
