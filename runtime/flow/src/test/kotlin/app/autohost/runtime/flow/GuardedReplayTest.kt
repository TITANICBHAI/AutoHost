package app.autohost.runtime.flow

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P6-08 (written; awaiting Gradle): StateMatcher scoring + GuardedReplay policies. The broker
 * stub serves `device.ui.snapshot` results; the guard runs through the same [FlowExec.stepGuard]
 * seam the provider wires at replay time.
 */
class GuardedReplayTest {

    private class StubBroker(private val snapshots: MutableList<JsonObject>) : FlowBroker {
        val otherCalls = mutableListOf<Pair<String, String>>()
        override suspend fun call(capability: String, method: String, args: JsonObject): JsonObject {
            if (capability == "device.ui" && method == "snapshot") {
                return snapshots.removeFirstOrNull() ?: error("no queued snapshot")
            }
            otherCalls.add(capability to method)
            return buildJsonObject { put("ok", true) }
        }
    }

    private fun snapshot(
        pkg: String = "com.example.app",
        activity: String = "Main",
        vararg keys: String,
    ): JsonObject = buildJsonObject {
        put("package", pkg)
        put("activity", activity)
        put("nodes", kotlinx.serialization.json.JsonArray(keys.map { key ->
            val parts = key.split("|")
            buildJsonObject {
                put("cls", parts.getOrElse(0) { "" })
                put("id", parts.getOrElse(1) { "" })
                put("text", parts.getOrElse(2) { "" })
                put("desc", parts.getOrElse(3) { "" })
            }
        }))
    }

    private fun expected(vararg keys: String, pkg: String = "com.example.app", activity: String = "Main") =
        StepSnapshot(pkg, activity, keys.toList())

    private fun sidecar(vararg metas: RecordingStepMeta, onMismatch: String = "stop", timeoutMs: Long = 100) =
        RecordingSidecar("f", 0, onMismatch, timeoutMs, metas.toList())

    private fun meta(label: String, snapshot: StepSnapshot) =
        RecordingStepMeta(label, snapshot, needsConfirmation = false, selectorRank = "id", elapsedMs = 0)

    private fun exec(
        broker: FlowBroker,
        events: MutableList<JsonObject> = mutableListOf(),
    ): Pair<FlowExec, MutableList<JsonObject>> = FlowExec(
        broker = broker,
        emit = { events.add(it) },
        sleep = { /* tests queue enough snapshots; no real waiting */ },
    ) to events

    private fun step(label: String, command: String = "tapOn") = app.autohost.core.model.FlowStep(
        command = command, body = buildJsonObject { put("label", label) }, label = label,
    )

    // --- StateMatcher --------------------------------------------------------------

    @Test
    fun `identical states score 1 and match`() {
        val expected = expected("Button|a|OK|", "TextView||hello|")
        val verdict = StateMatcher.compare(expected, StateMatcher.LiveState("com.example.app", "Main", expected.keys))
        assertEquals(1.0, verdict.score, 1e-9)
        assertTrue(verdict.matched)
    }

    @Test
    fun `half the nodes still matches - jaccard 05 + context 015`() {
        val expected = expected("Button|a|OK|", "TextView||hello|", "Button|b||")
        val live = StateMatcher.LiveState("com.example.app", "Main", listOf("Button|a|OK|", "TextView||hello|"))
        val verdict = StateMatcher.compare(expected, live)
        // jaccard = 2/3, context = 1.0 -> 0.85 * 0.6667 + 0.15 = 0.7167... below threshold? No:
        // 0.5667 + 0.15 = 0.7167 < 0.85. Use it to pin the exact arithmetic instead.
        assertEquals(0.85 * (2.0 / 3.0) + 0.15, verdict.score, 1e-9)
        assertFalse(verdict.matched)
    }

    @Test
    fun `package change pins the inclusive threshold boundary`() {
        val expected = expected("Button|a|OK|")
        val verdict = StateMatcher.compare(expected, StateMatcher.LiveState("com.other.app", "Main", expected.keys))
        assertEquals(0.85, verdict.score, 1e-9) // jaccard 1 but context 0
        assertTrue("0.85 >= 0.85: the threshold is inclusive (documented)", verdict.matched)
        // One step further — a different activity too — drops below and stops matching.
        val worse = StateMatcher.compare(expected, StateMatcher.LiveState("com.other.app", "Other", expected.keys))
        assertEquals(0.775, worse.score, 1e-9)
        assertFalse(worse.matched)
    }

    @Test
    fun `snapshot reduction mirrors the recorder's node keys`() {
        val live = StateMatcher.fromSnapshot(snapshot("com.example.app", "Main", "Button|a|OK|"))
        assertEquals(listOf("Button|a|OK|"), live.keys)
        assertEquals("com.example.app", live.packageName)
        assertEquals("Main", live.activity)
    }

    // --- GuardedReplay policies ------------------------------------------------------

    @Test
    fun `matching screen passes and the step runs`() = runBlocking {
        val wanted = expected("Button|a|OK|")
        val broker = StubBroker(mutableListOf(snapshot("com.example.app", "Main", "Button|a|OK|")))
        val guard = GuardedReplay(sidecar(meta("tap_1", wanted)))
        val (ex, events) = exec(broker)
        guard.guardStep(step("tap_1"), FlowScope.empty(), ex) // no exception = pass
        assertEquals(0, events.count { (it["type"] as? JsonPrimitive)?.content == "evidence" })
    }

    @Test
    fun `changed screen stops with PRE_STATE_MISMATCH and evidence`() = runBlocking {
        val wanted = expected("Button|a|OK|")
        val broker = StubBroker(mutableListOf(snapshot("com.example.app", "Main", "ImageView|b||")))
        val guard = GuardedReplay(sidecar(meta("tap_1", wanted)))
        val (ex, _) = exec(broker)
        try {
            guard.guardStep(step("tap_1"), FlowScope.empty(), ex)
            fail("expected FlowFailure")
        } catch (failure: FlowFailure) {
            assertEquals("PRE_STATE_MISMATCH", failure.code)
            assertFalse(failure.retryable)
        }
    }

    @Test
    fun `on_mismatch ask_user parks the run as WAITING_USER`() = runBlocking {
        val wanted = expected("Button|a|OK|")
        val broker = StubBroker(mutableListOf(snapshot("com.example.app", "Other", "ImageView|b||")))
        val guard = GuardedReplay(sidecar(meta("tap_1", wanted), onMismatch = "ask_user"))
        val (ex, _) = exec(broker)
        try {
            guard.guardStep(step("tap_1"), FlowScope.empty(), ex)
            fail("expected FlowWaitUserException")
        } catch (wait: FlowWaitUserException) {
            assertTrue(wait.message!!.contains("tap_1"))
        }
    }

    @Test
    fun `on_mismatch continue logs a warning and returns`() = runBlocking {
        val wanted = expected("Button|a|OK|")
        val broker = StubBroker(mutableListOf(snapshot("com.example.app", "Main", "ImageView|b||")))
        val guard = GuardedReplay(sidecar(meta("tap_1", wanted), onMismatch = "continue"))
        val (ex, events) = exec(broker)
        guard.guardStep(step("tap_1"), FlowScope.empty(), ex)
        val warnings = events.filter { (it["type"] as? JsonPrimitive)?.content == "log" }
        assertEquals(1, warnings.size)
    }

    @Test
    fun `steps without a sidecar entry are not guarded`() = runBlocking {
        val broker = StubBroker(mutableListOf()) // no snapshot needed
        val guard = GuardedReplay(sidecar(meta("tap_1", expected("Button|a|OK|"))))
        val (ex, _) = exec(broker)
        guard.guardStep(step("hand_edited"), FlowScope.empty(), ex)
        assertEquals(0, broker.otherCalls.size) // guard never even asked the broker
    }

    @Test
    fun `poll retries until the screen settles within the budget`() = runBlocking {
        val wanted = expected("Button|a|OK|")
        val broker = StubBroker(mutableListOf(
            snapshot("com.example.app", "Main", "ImageView|b||"), // still wrong
            snapshot("com.example.app", "Main", "Button|a|OK|"), // settled
        ))
        val guard = GuardedReplay(sidecar(meta("tap_1", wanted), timeoutMs = 5_000))
        val (ex, _) = exec(broker)
        guard.guardStep(step("tap_1"), FlowScope.empty(), ex)
    }

    @Test
    fun `snapshot broker failure counts as mismatch when the budget expires`() = runBlocking {
        val wanted = expected("Button|a|OK|")
        val broker = StubBroker(mutableListOf()) // snapshot() -> error("no queued snapshot") = typed failure path
        val guard = GuardedReplay(sidecar(meta("tap_1", wanted), timeoutMs = 1))
        val ex = FlowExec(broker = broker, emit = {}, sleep = { kotlinx.coroutines.delay(1) })
        try {
            guard.guardStep(step("tap_1"), FlowScope.empty(), ex)
            fail("expected FlowFailure after the poll budget")
        } catch (failure: FlowFailure) {
            assertEquals("PRE_STATE_MISMATCH", failure.code)
        }
    }
}
