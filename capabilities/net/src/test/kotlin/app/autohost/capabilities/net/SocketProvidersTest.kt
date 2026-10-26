package app.autohost.capabilities.net

import app.autohost.core.broker.CallContext
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * P10 W3 rider JVM tests: net.socket (TCP handles, domain gates, UDP caps) and net.reverse
 * (localhost-only plain ws, token-required opens, run-scoped teardown).
 */
private class FakeTcpFactory : SocketSeams.TcpFactory {
    val connections = mutableListOf<Pair<String, Int>>()
    var failHosts: Set<String> = emptySet()
    val closed = mutableListOf<SocketSeams.Connection>()

    override fun connect(host: String, port: Int, timeoutMs: Long): SocketSeams.Connection {
        if (host in failHosts) throw java.net.ConnectException("refused")
        connections.add(host to port)
        val connection = SocketSeams.Connection(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        return connection
    }
}

private fun ctx(vararg grants: String, scope: String? = null) = CallContext(
    "r1", "t1", "p1",
    grants.associate { id ->
        id to if (scope != null) {
            kotlinx.serialization.json.buildJsonObject {
                put("domains", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(scope))))
            }
        } else {
            kotlinx.serialization.json.JsonObject(emptyMap())
        }
    },
    nowMs = 0,
)

class SocketProviderTest {

    @Test fun `tcp connect opens a run-scoped handle`() = runBlocking {
        val factory = FakeTcpFactory()
        val provider = SocketProvider(factory)
        val envelope = provider.invoke(ctx("net.socket"), "tcp_connect", buildJsonObject {
            put("host", JsonPrimitive("example.com"))
            put("port", JsonPrimitive(443))
        })
        assertTrue(envelope.ok)
        assertEquals("tcp-1", envelope.result!!.jsonObject["handle"]!!.jsonPrimitive.content)
        assertEquals(listOf("example.com" to 443), factory.connections)
    }

    @Test fun `tcp connect respects the domain allowlist`() = runBlocking {
        val provider = SocketProvider(FakeTcpFactory())
        val denied = provider.invoke(ctx("net.socket", scope = "*.example.org"), "tcp_connect", buildJsonObject {
            put("host", JsonPrimitive("evil.org"))
            put("port", JsonPrimitive(443))
        })
        assertEquals(ErrorCode.SCOPE_VIOLATION, ErrorCode.valueOf(denied.error!!.code))
        val noGrant = provider.invoke(ctx(), "tcp_connect", buildJsonObject {
            put("host", JsonPrimitive("example.com"))
            put("port", JsonPrimitive(443))
        })
        assertEquals(ErrorCode.PERMISSION_DENIED, ErrorCode.valueOf(noGrant.error!!.code))
    }

    @Test fun `tcp handles cap at eight`() = runBlocking {
        val provider = SocketProvider(FakeTcpFactory())
        val grants = ctx("net.socket")
        repeat(8) {
            assertTrue(provider.invoke(grants, "tcp_connect", buildJsonObject {
                put("host", JsonPrimitive("example.com"))
                put("port", JsonPrimitive(80))
            }).ok)
        }
        val ninth = provider.invoke(grants, "tcp_connect", buildJsonObject {
            put("host", JsonPrimitive("example.com"))
            put("port", JsonPrimitive(80))
        })
        assertEquals(ErrorCode.RATE_LIMITED, ErrorCode.valueOf(ninth.error!!.code))
    }

    @Test fun `connect failures are honest capability errors`() = runBlocking {
        val factory = FakeTcpFactory().apply { failHosts = setOf("down.example") }
        val provider = SocketProvider(factory)
        val envelope = provider.invoke(ctx("net.socket"), "tcp_connect", buildJsonObject {
            put("host", JsonPrimitive("down.example"))
            put("port", JsonPrimitive(80))
        })
        assertEquals(ErrorCode.CAPABILITY_UNAVAILABLE, ErrorCode.valueOf(envelope.error!!.code))
    }

    @Test fun `closeAllForRun releases every handle`() = runBlocking {
        val factory = FakeTcpFactory()
        val provider = SocketProvider(factory)
        val grants = ctx("net.socket")
        repeat(2) {
            provider.invoke(grants, "tcp_connect", buildJsonObject {
                put("host", JsonPrimitive("example.com"))
                put("port", JsonPrimitive(80))
            })
        }
        assertEquals(2, provider.tcpHandles.size)
        provider.closeAllForRun()
        assertEquals(0, provider.tcpHandles.size)
    }

    @Test fun `udp payloads are capped at the datagram ceiling`() = runBlocking {
        val provider = SocketProvider(FakeTcpFactory())
        val envelope = provider.invoke(ctx("net.socket"), "udp_send", buildJsonObject {
            put("host", JsonPrimitive("example.com"))
            put("port", JsonPrimitive(5353))
            put("data_b64", JsonPrimitive(java.util.Base64.getEncoder().encodeToString(ByteArray(70_000))))
        })
        assertEquals(ErrorCode.INVALID_ARGS, ErrorCode.valueOf(envelope.error!!.code))
    }
}

private class FakeReverseFactory : WsClientProvider.SocketFactory {
    val opened = mutableListOf<String>()
    var fail = false

    override fun connect(url: String, subprotocols: List<String>, timeoutMs: Long): WsClientProvider.Connection =
        if (fail) throw java.net.ConnectException("refused")
        else WsClientProvider.Connection(
            ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(),
        ).also { opened.add(url) }
}

class ReverseWsProviderTest {

    @Test fun `open requires a token and the right schemes`() = runBlocking {
        val provider = ReverseWsProvider(FakeReverseFactory())
        val noToken = provider.invoke(ctx("net.reverse"), "open", buildJsonObject {
            put("url", JsonPrimitive("wss://pc.example.org/ws"))
        })
        assertEquals(ErrorCode.INVALID_ARGS, ErrorCode.valueOf(noToken.error!!.code))
        val badScheme = provider.invoke(ctx("net.reverse"), "open", buildJsonObject {
            put("url", JsonPrimitive("http://pc.example.org/ws"))
            put("token", JsonPrimitive("t"))
        })
        assertEquals(ErrorCode.INVALID_ARGS, ErrorCode.valueOf(badScheme.error!!.code))
    }

    @Test fun `plain ws is localhost only`() = runBlocking {
        val provider = ReverseWsProvider(FakeReverseFactory())
        val remote = provider.invoke(ctx("net.reverse"), "open", buildJsonObject {
            put("url", JsonPrimitive("ws://remote.example.org/ws"))
            put("token", JsonPrimitive("t"))
        })
        assertEquals(ErrorCode.SCOPE_VIOLATION, ErrorCode.valueOf(remote.error!!.code))
        val local = provider.invoke(ctx("net.reverse"), "open", buildJsonObject {
            put("url", JsonPrimitive("ws://localhost:9999/ws"))
            put("token", JsonPrimitive("t"))
        })
        assertTrue(local.ok)
    }

    @Test fun `unknown handles are not found`() = runBlocking {
        val provider = ReverseWsProvider(FakeReverseFactory())
        val envelope = provider.invoke(ctx("net.reverse"), "send", buildJsonObject {
            put("handle", JsonPrimitive("rev-none"))
            put("text", JsonPrimitive("x"))
        })
        assertEquals(ErrorCode.NOT_FOUND, ErrorCode.valueOf(envelope.error!!.code))
    }

    @Test fun `closeAllForRun clears handles`() = runBlocking {
        val factory = FakeReverseFactory()
        val provider = ReverseWsProvider(factory)
        provider.invoke(ctx("net.reverse"), "open", buildJsonObject {
            put("url", JsonPrimitive("ws://localhost:9999/ws"))
            put("token", JsonPrimitive("t"))
        })
        assertEquals(1, provider.handles.size)
        provider.closeAllForRun()
        assertEquals(0, provider.handles.size)
    }

    @Test fun `the net_reverse switch rides the feature map`() {
        assertEquals("net_reverse", InsecureFeatures.featureFor("net.reverse", "net.reverse"))
        assertTrue("net_reverse" in InsecureFeatures.KNOWN)
    }
}
