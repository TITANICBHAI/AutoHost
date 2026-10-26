package app.autohost.capabilities.shell

import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

/**
 * P9-08: the exclusivity policy and the session protocol client (pure JVM).
 */
class UiAutomationExclusivityTest {

    @Test fun `start refused while a11y is connected or toggle off`() {
        assertTrue(UiAutomationExclusivity.canStart(a11yConnected = true, experimentalEnabled = true)
            is UiAutomationExclusivity.StartDecision.Refused)
        assertTrue(UiAutomationExclusivity.canStart(a11yConnected = false, experimentalEnabled = false)
            is UiAutomationExclusivity.StartDecision.Refused)
        assertEquals(
            UiAutomationExclusivity.StartDecision.Ok,
            UiAutomationExclusivity.canStart(a11yConnected = false, experimentalEnabled = true),
        )
    }

    @Test fun `a11y reconnect closes a live session and nothing else`() {
        val decision = UiAutomationExclusivity.onA11yEvent(sessionLive = true, a11yConnected = true)
        assertTrue(decision is UiAutomationExclusivity.A11yEventDecision.CloseSession)
        assertEquals("A11Y_RECONNECTED", (decision as UiAutomationExclusivity.A11yEventDecision.CloseSession).reason)
        assertEquals(
            UiAutomationExclusivity.A11yEventDecision.Keep,
            UiAutomationExclusivity.onA11yEvent(sessionLive = false, a11yConnected = true),
        )
        assertEquals(
            UiAutomationExclusivity.A11yEventDecision.Keep,
            UiAutomationExclusivity.onA11yEvent(sessionLive = true, a11yConnected = false),
        )
    }
}

/** P9-08: the wire protocol against an in-memory fake runner. */
class UiAutomationSessionClientTest {

    /** Loopback-socket stand-in: request lines in, canned reply lines out. */
    private class FakeConnection(private val replies: MutableList<String>) : UiAutomationSessionClient.Connection {
        val sent = mutableListOf<String>()
        private val incoming = ArrayDeque<String>()
        var closed = false

        override fun sendLine(line: String) {
            sent.add(line)
            // The fake runner answers synchronously from the reply script.
            incoming.addLast(replies.removeFirstOrNull() ?: "{\"ok\":false,\"error\":\"SESSION_CLOSED\"}")
        }

        override fun readLine(): String? = incoming.removeFirstOrNull()

        override fun close() {
            closed = true
        }
    }

    @Test fun `dump round-trips the xml payload`() {
        val connection = FakeConnection(mutableListOf("{\"ok\":true,\"xml\":\"<hierarchy><node index=\\\"0\\\" text=\\\"hi\\\"/></hierarchy>\"}"))
        val client = UiAutomationSessionClient(connection)
        val xml = client.dump()
        assertTrue(xml.contains("<hierarchy"))
        assertEquals("{\"op\":\"dump\"}", connection.sent[0])
        val nodes = UiautomatorDump.parse(xml)
        assertEquals(1, nodes.size)
        assertEquals("hi", nodes[0].text)
    }

    @Test fun `click and text carry the flat index and performed flag`() {
        val connection = FakeConnection(mutableListOf(
            "{\"ok\":true,\"performed\":true}",
            "{\"ok\":true,\"performed\":false,\"error\":\"NODE_NOT_FOUND\"}",
        ))
        val client = UiAutomationSessionClient(connection)
        assertTrue(client.click(17))
        assertFalse(client.setText(17, "hello"))
        assertTrue(connection.sent[0].contains("\"index\":17"))
        assertTrue(connection.sent[1].contains("\"text\":\"hello\""))
    }

    @Test fun `runner errors surface as typed RpcError`() {
        val connection = FakeConnection(mutableListOf("{\"ok\":false,\"error\":\"SESSION_CLOSED\",\"detail\":\"gone\"}"))
        val client = UiAutomationSessionClient(connection)
        try {
            client.ping()
            throw AssertionError("expected RpcError")
        } catch (e: UiAutomationSessionClient.RpcError) {
            assertEquals("SESSION_CLOSED", e.code)
        }
    }

    @Test fun `quit closes the connection`() {
        val connection = FakeConnection(mutableListOf("{\"ok\":true,\"quit\":true}"))
        val client = UiAutomationSessionClient(connection)
        client.quit()
        assertTrue(connection.closed)
    }
}

/**
 * P9-08: the provider over a scripted fake runner (a real client whose connection answers from a
 * reply script) — the same device.ui surface, honest verification, secret text allowed (ADR-068),
 * package scoping enforced.
 */
class UiAutomationProviderTest {

    private class ScriptedConnection : UiAutomationSessionClient.Connection {
        val replies = ArrayDeque<String>()
        val sent = mutableListOf<String>()
        var closed = false

        override fun sendLine(line: String) {
            sent.add(line)
        }

        override fun readLine(): String? = replies.removeFirstOrNull()

        override fun close() {
            closed = true
        }
    }

    private class Harness {
        val connection = ScriptedConnection()
        val provider = UiAutomationProvider(
            session = { UiAutomationSessionClient(connection) },
        )

        private fun ctx() = app.autohost.core.broker.CallContext(
            runId = "r1",
            taskId = "t1",
            profileId = null,
            grants = mapOf(
                "device.ui.observe" to buildJsonObject { put("packages", kotlinx.serialization.json.JsonArray(emptyList())) },
                "device.ui.act" to buildJsonObject {
                    put(
                        "packages",
                        kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("com.fixture"))),
                    )
                },
            ),
            nowMs = 0L,
        )

        suspend fun call(method: String, args: JsonObject): Envelope =
            provider.invoke(ctx(), method, args)
    }

    private fun dumpReply(vararg texts: String): String {
        val nodes = texts.mapIndexed { i, text ->
            "<node index=\"$i\" text=\"$text\" resource-id=\"\" class=\"android.view.View\"" +
                " package=\"com.fixture\" content-desc=\"\" checkable=\"false\" checked=\"false\"" +
                " clickable=\"true\" enabled=\"true\" focusable=\"false\" focused=\"false\"" +
                " scrollable=\"false\" password=\"false\" bounds=\"[0,${i * 100}][100,${i * 100 + 80}]\" />"
        }.joinToString("")
        val xml = "<?xml version='1.0'?><hierarchy rotation=\"0\">$nodes</hierarchy>"
        return Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { put("ok", true); put("xml", xml) },
        )
    }

    @Test fun `availability is unsupported without a live session`() = runBlocking {
        val provider = UiAutomationProvider(session = { null })
        assertEquals(
            Availability.Unsupported::class,
            provider.availability(DeviceEnvStub)::class,
        )
    }

    @Test fun `click verifies through the runner and the expect recheck`() = runBlocking {
        val harness = Harness()
        harness.connection.replies.addLast(dumpReply("Buy now"))
        harness.connection.replies.addLast("{\"ok\":true,\"performed\":true}")
        val envelope = harness.call(
            "click",
            buildJsonObject {
                put(
                    "target",
                    buildJsonObject {
                        put(
                            "selector",
                            buildJsonObject { put("text", JsonPrimitive("Buy now")) },
                        )
                    },
                )
            },
        )
        val payload = envelope.result!!.jsonObject
        assertTrue(payload["verified"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("ui.automation", payload["method"]!!.jsonPrimitive.content)
        // The act rode the socket, not a shell command line: click op with the flat index.
        assertTrue(harness.connection.sent[1].contains("\"op\":\"click\""))
    }

    @Test fun `out-of-scope act is refused before any runner traffic`() = runBlocking {
        val harness = Harness()
        harness.connection.replies.addLast(
            dumpReply("harmless").replace("\"package=\\"com.fixture\\"\"", "\"package=\\"com.other\\"\""),
        )
        val envelope = harness.call(
            "click",
            buildJsonObject {
                put(
                    "target",
                    buildJsonObject {
                        put(
                            "selector",
                            buildJsonObject { put("text", JsonPrimitive("harmless")) },
                        )
                    },
                )
            },
        )
        assertEquals(ErrorCode.SCOPE_VIOLATION, envelope.errorOrNull()?.codeAsEnum())
        assertEquals(1, harness.connection.sent.size) // only the dump, never the click op
    }

    @Test fun `secret text is allowed on this provider (ADR-068)`() = runBlocking {
        val harness = Harness()
        harness.connection.replies.addLast(dumpReply("password"))
        harness.connection.replies.addLast("{\"ok\":true,\"performed\":true}")
        val envelope = harness.call(
            "set_text",
            buildJsonObject {
                put("text", JsonPrimitive("hunter2"))
                put("secret", JsonPrimitive("true"))
                put(
                    "target",
                    buildJsonObject {
                        put(
                            "selector",
                            buildJsonObject { put("text", JsonPrimitive("password")) },
                        )
                    },
                )
            },
        )
        val payload = envelope.result!!.jsonObject
        assertEquals("AcceptedUnverified", payload["outcome"]!!.jsonPrimitive.content)
        assertTrue(harness.connection.sent[1].contains("\"op\":\"text\""))
    }

    private val DeviceEnvStub: DeviceEnv = DeviceEnv(sdkInt = 29)
}
