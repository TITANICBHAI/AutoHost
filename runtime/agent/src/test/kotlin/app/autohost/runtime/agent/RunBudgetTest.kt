package app.autohost.runtime.agent

import app.autohost.core.llm.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P13-10 (AGENT_RUNTIME §7.1): the six BUDGET_* reasons and the pre-dispatch reserve. */
class RunBudgetTest {

    private val limits = RunBudget.Limits(
        maxTurns = 4,
        wallClockMs = 10_000,
        maxModelCalls = 4,
        tokenBudget = 100_000,
        maxActions = 3,
        maxOutputTokens = 200,
    )

    private fun budget(
        clock: LongArray = longArrayOf(0),
        limits: RunBudget.Limits = this.limits,
    ) = RunBudget(limits) {
        clock[0] // the fake clock reads the cell
    }

    @Test
    fun `given a fresh budget when checked then every gate is open`() {
        val b = budget()
        b.start()
        assertEquals(RunBudget.Verdict.Ok, b.beforeModelCall(1_000))
        assertEquals(RunBudget.Verdict.Ok, b.beforeAction())
    }

    @Test
    fun `given the turn limit when passed then BUDGET_TURNS`() {
        val b = budget()
        b.start()
        repeat(4) { assertEquals(RunBudget.Verdict.Ok, b.beforeModelCall(100)) }
        val verdict = b.beforeModelCall(100)
        assertTrue(verdict is RunBudget.Verdict.Exhausted)
        assertEquals("BUDGET_TURNS", (verdict as RunBudget.Verdict.Exhausted).reason)
    }

    @Test
    fun `given the wall clock passed then BUDGET_TIME`() {
        val clock = longArrayOf(0)
        val b = budget(clock)
        b.start()
        clock[0] = 10_001
        val verdict = b.beforeModelCall(100)
        assertEquals("BUDGET_TIME", (verdict as RunBudget.Verdict.Exhausted).reason)
    }

    @Test
    fun `given the model-call limit when passed then BUDGET_MODEL_CALLS`() {
        val b = budget(limits = limits.copy(maxModelCalls = 2))
        b.start()
        assertEquals(RunBudget.Verdict.Ok, b.beforeModelCall(100))
        assertEquals(RunBudget.Verdict.Ok, b.beforeModelCall(100))
        val verdict = b.beforeModelCall(100)
        assertEquals("BUDGET_MODEL_CALLS", (verdict as RunBudget.Verdict.Exhausted).reason)
    }

    @Test
    fun `given the token budget when the reserve does not fit then BUDGET_TOKENS`() {
        val b = budget(limits = limits.copy(tokenBudget = 1_000, maxOutputTokens = 200))
        b.start()
        // turn 1: reserve = estimate(6_400 chars)=1600 + 200 = 1800 <= 1000? No — 1800 > 1000 →
        // BUDGET_TOKENS on the FIRST call (used 0, reserve alone busts it).
        val verdict = b.beforeModelCall(6_400)
        assertEquals("BUDGET_TOKENS", (verdict as RunBudget.Verdict.Exhausted).reason)
    }

    @Test
    fun `given a prompt that cannot fit even alone then BUDGET_CONTEXT (compaction cannot help)`() {
        val b = budget(limits = limits.copy(tokenBudget = 500, maxOutputTokens = 200))
        b.start()
        // estimate(4_000 chars) = 1000 + 200 = 1200 > 500 even with tokensUsed = 0 → CONTEXT
        val verdict = b.beforeModelCall(4_000)
        assertEquals("BUDGET_CONTEXT", (verdict as RunBudget.Verdict.Exhausted).reason)
    }

    @Test
    fun `given the action limit when passed then BUDGET_ACTIONS (host tools are free)`() {
        val b = budget()
        b.start()
        repeat(3) { assertEquals(RunBudget.Verdict.Ok, b.beforeAction()) }
        val verdict = b.beforeAction()
        assertEquals("BUDGET_ACTIONS", (verdict as RunBudget.Verdict.Exhausted).reason)
        // and a model call still passes — the action budget does not block turns
        assertEquals(RunBudget.Verdict.Ok, b.beforeModelCall(10))
    }

    @Test
    fun `given a response without usage when accounted then the estimate is charged, never 0`() {
        val b = budget()
        b.start()
        assertEquals(RunBudget.Verdict.Ok, b.beforeModelCall(2_000)) // reserve 500 + 200
        b.accountModelCall(usage = null, promptChars = 2_000) // 500 tokens estimated
        assertEquals(500L, b.tokensUsed)
    }

    @Test
    fun `given a response with usage when accounted then the real total is charged`() {
        val b = budget()
        b.start()
        assertEquals(RunBudget.Verdict.Ok, b.beforeModelCall(2_000))
        b.accountModelCall(Usage(300, 120, 420, estimated = false), promptChars = 2_000)
        assertEquals(420L, b.tokensUsed)
    }

    @Test
    fun `given used tokens when the next reserve does not fit then BUDGET_TOKENS (not CONTEXT)`() {
        val b = budget(limits = limits.copy(tokenBudget = 2_000, maxOutputTokens = 200))
        b.start()
        assertEquals(RunBudget.Verdict.Ok, b.beforeModelCall(2_000)) // turns=1
        b.accountModelCall(null, promptChars = 2_000) // +500 used
        // reserve = estimate(6_000)=1500 + 200 = 1700; 500 + 1700 = 2200 > 2000 → TOKENS
        val verdict = b.beforeModelCall(6_000)
        assertEquals("BUDGET_TOKENS", (verdict as RunBudget.Verdict.Exhausted).reason)
    }
}
