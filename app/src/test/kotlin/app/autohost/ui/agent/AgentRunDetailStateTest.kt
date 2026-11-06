package app.autohost.ui.agent

import app.autohost.core.common.FakeClock
import app.autohost.core.model.BrokerEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P14-04 (S30, G14-a): every agent run-detail rule through the holder — the thought → action
 * timeline paired with results, the usage panel counters, the budget warnings, and the HOST
 * verdict (derived only from the host's terminal transition, never the model's claim).
 */
class AgentRunDetailStateTest {

    private val clock = FakeClock(startMs = 1_000_000L)

    private fun state() = AgentRunDetailState(clock)

    private fun log(kind: String, vararg pairs: Pair<String, Any>) = BrokerEvent(
        type = "log",
        ts = 1_000_000L,
        data = buildJsonObject { put("kind", kind); pairs.forEach { put(it.first, it.second) } },
    )

    @Test
    fun `given a thought and an action then a result when fed then the timeline pairs the ok icon`() {
        val s = state()
        s.onEvent(log("agent.thought", "text" to "I will tap the toggle."))
        s.onEvent(log("agent.action", "tool" to "ui_click", "args" to """{"id":"[1]"}"""))
        val pending = s.snapshot()
        assertEquals(2, pending.entries.size)
        assertNull("the icon is pending until the result arrives", (pending.entries[1] as AgentRunDetailState.Entry.Action).ok)

        s.onEvent(log("agent.result", "tool" to "ui_click", "ok" to true))
        val paired = s.snapshot()
        assertEquals(true, (paired.entries[1] as AgentRunDetailState.Entry.Action).ok)
        assertEquals(1, paired.usage.actions)
    }

    @Test
    fun `given a failed result when fed then the icon is a cross, never a guess`() {
        val s = state()
        s.onEvent(log("agent.action", "tool" to "ui_set_text", "args" to "{}"))
        s.onEvent(log("agent.result", "tool" to "ui_set_text", "ok" to false))
        assertEquals(false, (s.snapshot().entries[0] as AgentRunDetailState.Entry.Action).ok)
    }

    @Test
    fun `given two actions with the same tool when results arrive then each pairs with its own action in order`() {
        val s = state()
        s.onEvent(log("agent.action", "tool" to "ui_click", "args" to "a"))
        s.onEvent(log("agent.action", "tool" to "ui_click", "args" to "b"))
        s.onEvent(log("agent.result", "tool" to "ui_click", "ok" to true))
        s.onEvent(log("agent.result", "tool" to "ui_click", "ok" to false))
        val entries = s.snapshot().entries.map { it as AgentRunDetailState.Entry.Action }
        // the LAST pending action pairs first (one action per turn; results arrive in order)
        assertEquals(true, entries[0].ok)
        assertEquals(false, entries[1].ok)
    }

    @Test
    fun `given usage and progress events when fed then the panel counters add up`() {
        val s = state()
        s.onEvent(BrokerEvent(type = "progress", ts = 1_000_000L, data = buildJsonObject { put("turn", 3) }))
        repeat(2) {
            s.onEvent(log("agent.usage", "prompt_tokens" to 200, "completion_tokens" to 50, "estimated" to false))
        }
        val usage = s.snapshot().usage
        assertEquals(3, usage.turns)
        assertEquals(2, usage.modelCalls)
        assertEquals(500L, usage.tokens)
    }

    @Test
    fun `given a budget crossing when fed then the warning shows once per budget`() {
        val s = state()
        s.onEvent(log("agent.budget", "budget" to "tokens"))
        s.onEvent(log("agent.budget", "budget" to "tokens"))
        s.onEvent(log("agent.budget", "budget" to "turns"))
        assertEquals(listOf("tokens", "turns"), s.snapshot().budgetWarnings)
    }

    @Test
    fun `given SUCCEEDED when finished then the host verdict is Confirmed`() {
        val s = state()
        s.onEvent(BrokerEvent(type = "finished", ts = 1_000_000L, data = buildJsonObject { put("status", "SUCCEEDED") }))
        assertEquals(AgentRunDetailState.Verdict.Confirmed, s.snapshot().verdict)
    }

    @Test
    fun `given COMPLETION_REJECTED and COMPLETION_UNVERIFIED when finished then the verdict matches the host decision`() {
        val rejected = state()
        rejected.onEvent(
            BrokerEvent(type = "finished", ts = 1_000_000L, data = buildJsonObject { put("status", "FAILED"); put("reason", "COMPLETION_REJECTED") }),
        )
        assertEquals(AgentRunDetailState.Verdict.Rejected, rejected.snapshot().verdict)

        val unverified = state()
        unverified.onEvent(
            BrokerEvent(type = "finished", ts = 1_000_000L, data = buildJsonObject { put("status", "FAILED"); put("reason", "COMPLETION_UNVERIFIED") }),
        )
        assertEquals(AgentRunDetailState.Verdict.Unverified, unverified.snapshot().verdict)
    }

    @Test
    fun `given a non-completion failure when finished then there is NO host verdict to show`() {
        val s = state()
        s.onEvent(
            BrokerEvent(type = "finished", ts = 1_000_000L, data = buildJsonObject { put("status", "FAILED"); put("reason", "AGENT_STUCK") }),
        )
        val snap = s.snapshot()
        assertNull(snap.verdict)
        assertEquals("FAILED — AGENT_STUCK", snap.status)
    }

    @Test
    fun `given events over time when snapshotted then the wall clock counts from the first event`() {
        val s = state()
        s.onEvent(log("agent.thought", "text" to "start"))
        clock.advance(65_432L)
        val snap = s.snapshot()
        assertEquals(65_432L, snap.wallClockMs)
    }

    @Test
    fun `given a feed of mixed non-agent events when fed then they change nothing (stage/heartbeat/evidence)`() {
        val s = state()
        s.onEvent(BrokerEvent(type = "heartbeat", ts = 1_000_000L))
        s.onEvent(BrokerEvent(type = "stage_begin", ts = 1_000_000L, stage = "observe"))
        s.onEvent(BrokerEvent(type = "evidence", ts = 1_000_000L, data = buildJsonObject { put("path", "/x"); put("kind", "ui_snapshot") }))
        val snap = s.snapshot()
        assertTrue(snap.entries.isEmpty())
        assertEquals(0, snap.usage.modelCalls)
        assertNull(snap.verdict)
    }
}
