package app.autohost.runtime.agent

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P13-14 (AGENT_RUNTIME §9): the completion protocol — confirmed / rejected / timeout paths. */
class CompletionVerifierTest {

    private val snapshot = Json.parseToJsonElement(
        """{"package":"com.android.settings","keyboard":"hidden","node_count":0,"nodes":[]}""",
    ) as JsonObject

    private class FakeTransport(var confirmAnswer: JsonObject) : BrokerTransport {
        val calls = mutableListOf<Triple<String, String, JsonObject>>()
        override suspend fun call(permission: String, method: String, args: JsonObject): JsonObject {
            calls.add(Triple(permission, method, args))
            return confirmAnswer
        }
    }

    private class RecordingEvidence : CompletionVerifier.EvidenceWriter {
        val written = mutableListOf<JsonObject>()
        override suspend fun write(snapshot: JsonObject): String {
            written.add(snapshot)
            return "/runs/run-1/evidence/final.json"
        }
    }

    private fun verifier(
        transport: FakeTransport,
        evidence: RecordingEvidence = RecordingEvidence(),
        events: MutableList<JsonObject> = mutableListOf(),
        confirmTimeoutS: Int = 120,
    ): CompletionVerifier = CompletionVerifier(
        observer = { snapshot },
        evidence = evidence,
        emit = { events.add(it) },
        transport = transport,
        config = CompletionVerifier.Config(confirmTimeoutS = confirmTimeoutS),
        clockMs = { 0L },
    )

    private fun confirm(confirmed: Boolean) = buildJsonObject {
        put("answered", true)
        put("confirmed", confirmed)
    }

    @Test
    fun `given confirmed true when verified then Confirmed (the only path to SUCCEEDED)`() = runBlocking {
        val transport = FakeTransport(confirm(true))
        val result = verifier(transport).verify("opened Settings")
        assertEquals(AgentLoop.FinishResult.Confirmed, result)
    }

    @Test
    fun `given confirmed false when verified then Rejected (COMPLETION_REJECTED)`() = runBlocking {
        val transport = FakeTransport(confirm(false))
        val result = verifier(transport).verify("opened Settings")
        assertEquals(AgentLoop.FinishResult.Rejected, result)
    }

    @Test
    fun `given a timeout when verified then Unverified (COMPLETION_UNVERIFIED)`() = runBlocking {
        val transport = FakeTransport(buildJsonObject { put("answered", false); put("reason", "timeout") })
        val result = verifier(transport).verify("opened Settings")
        assertEquals(AgentLoop.FinishResult.Unverified, result)
    }

    @Test
    fun `given a dismissed prompt when verified then Unverified (same as timeout)`() = runBlocking {
        val transport = FakeTransport(buildJsonObject { put("answered", false); put("reason", "dismissed") })
        val result = verifier(transport).verify(null)
        assertEquals(AgentLoop.FinishResult.Unverified, result)
    }

    @Test
    fun `given the kill switch cancelled the prompt when verified then Unverified`() = runBlocking {
        val transport = FakeTransport(buildJsonObject { put("answered", false); put("reason", "cancelled") })
        val result = verifier(transport).verify(null)
        assertEquals(AgentLoop.FinishResult.Unverified, result)
    }

    @Test
    fun `given an unknown answer shape when verified then Unverified (fail closed, never confirmed)`() = runBlocking {
        val transport = FakeTransport(buildJsonObject { put("answered", true) }) // no confirmed field
        val result = verifier(transport).verify(null)
        assertEquals(AgentLoop.FinishResult.Unverified, result)
    }

    @Test
    fun `given a finish when verified then the final observation is written as ui_snapshot evidence BEFORE the ask`() = runBlocking {
        val transport = FakeTransport(confirm(true))
        val evidence = RecordingEvidence()
        val events = mutableListOf<JsonObject>()
        verifier(transport, evidence, events).verify("done")
        assertEquals(1, evidence.written.size)
        assertEquals(snapshot, evidence.written[0])
        // the evidence event carries data.path + data.kind (the RunPersistenceBridge contract)
        val evidenceEvent = events.first { (it["type"] as? JsonPrimitive)?.content == "evidence" }
        val data = evidenceEvent["data"] as JsonObject
        assertEquals("/runs/run-1/evidence/final.json", (data["path"] as JsonPrimitive).content)
        assertEquals("ui_snapshot", (data["kind"] as JsonPrimitive).content)
        // the ask came AFTER the evidence write (transport order: ask is the only call)
        assertEquals(1, transport.calls.size)
        assertEquals("ui.prompt" to "confirm", transport.calls[0].first to transport.calls[0].second)
    }

    @Test
    fun `given a finish when verified then the ask carries the template with the 120-char clip and the timeout`() = runBlocking {
        val transport = FakeTransport(confirm(true))
        verifier(transport).verify("x".repeat(300))
        val args = transport.calls[0].third
        assertEquals("confirm", (args["kind"] as JsonPrimitive).content)
        assertEquals(120_000L, (args["timeout_ms"] as JsonPrimitive).content.toLong())
        val message = (args["message"] as JsonPrimitive).content
        assertTrue(message.startsWith("The assistant says it is done: "))
        assertTrue(message.endsWith(". Is it?"))
        // 120 chars of summary + the template wrapper
        val embedded = message.removePrefix("The assistant says it is done: ").removeSuffix(". Is it?")
        assertTrue("the summary is clipped to ≤ 120 chars", embedded.length <= 120)
    }

    @Test
    fun `given a short summary when verified then the template embeds it verbatim`() = runBlocking {
        val transport = FakeTransport(confirm(true))
        verifier(transport).verify("DND is on")
        val message = (transport.calls[0].third["message"] as JsonPrimitive).content
        assertEquals("The assistant says it is done: DND is on. Is it?", message)
    }

    @Test
    fun `the clip helper bounds the summary at 120 chars`() {
        assertEquals(120, CompletionVerifier.clip("y".repeat(300)).length)
        assertEquals("abc", CompletionVerifier.clip("abc"))
    }

    @Test
    fun `given a slow confirm when the keep-alive ticks then a log event fires every 15 s (the idle watchdog is fed)`() = runTest {
        val events = mutableListOf<JsonObject>()
        val gate = kotlinx.coroutines.CompletableDeferred<JsonObject>()
        val transport = BrokerTransport { _, _, _ -> gate.await() } // the ask stays in flight
        val verifier = CompletionVerifier(
            observer = { snapshot },
            evidence = { _ -> "/evidence/final.json" },
            emit = { events.add(it) },
            transport = transport,
            config = CompletionVerifier.Config(confirmTimeoutS = 120, keepAliveIntervalMs = 15_000),
            clockMs = { 0L },
        )
        val verdict = async(start = CoroutineStart.UNDISPATCHED) { verifier.verify("done") }
        advanceTimeBy(45_001) // three keep-alive intervals
        val keepAlives = events.count {
            (it["type"] as? JsonPrimitive)?.content == "log" &&
                ((it["data"] as? JsonObject)?.get("kind") as? JsonPrimitive)?.content == "agent.waiting_confirm"
        }
        assertTrue("expected ≥ 3 keep-alive events by 45 s, got $keepAlives", keepAlives >= 3)
        gate.complete(confirm(true))
        advanceUntilIdle()
        assertEquals(AgentLoop.FinishResult.Confirmed, verdict.await())
    }
}
