package app.autohost.runtime.agent

import app.autohost.core.llm.Usage

/**
 * P13-10 (AGENT_RUNTIME §7.1): the run's budgets, checked BEFORE every model call and every
 * device action. Six failure reasons (schemas/errors.json reasonCodes):
 *
 *   BUDGET_TURNS       the turn count hit `maxTurns`
 *   BUDGET_TIME        the wall clock passed `wallClockMs` (the orchestrator's watchdog enforces
 *                      the same limit independently — X-16 — this check is the loop's own)
 *   BUDGET_MODEL_CALLS `maxModelCalls` model calls
 *   BUDGET_TOKENS      the token budget including the NEXT call's reserve
 *   BUDGET_ACTIONS     `maxActions` device actions (host tools — finish/todo_write/wait/ask — are free)
 *   BUDGET_CONTEXT     even ONE call cannot fit: prompt-estimate + max_output alone > the token
 *                      budget (compaction cannot help; a fresh turn would not either)
 *
 * The idle budget is NOT here: it is the orchestrator's watchdog counting non-heartbeat activity
 * (§11, ADR-098/X-16) — the loop's events keep it fed. Unknown model usage is ESTIMATED and
 * flagged, never 0 (§7.1).
 */
class RunBudget(
    val limits: Limits,
    private val clockMs: () -> Long,
) {

    data class Limits(
        val maxTurns: Int,
        val wallClockMs: Long,
        val maxModelCalls: Int,
        val tokenBudget: Long,
        val maxActions: Int,
        /** The per-call reserve: every check assumes the next completion can be this long. */
        val maxOutputTokens: Int,
    )

    sealed interface Verdict {
        data object Ok : Verdict

        /** [reason] is one of the six `BUDGET_*` reason codes. */
        data class Exhausted(val reason: String, val message: String) : Verdict
    }

    private var startedAtMs: Long = 0
    var turns: Int = 0; private set
    var modelCalls: Int = 0; private set
    var actions: Int = 0; private set
    var tokensUsed: Long = 0; private set

    fun start() {
        startedAtMs = clockMs()
    }

    /** P14-07: the wall clock the next model call may use — the ModelClient's deadline. */
    fun remainingWallClockMs(): Long =
        if (startedAtMs == 0L) limits.wallClockMs
        else (limits.wallClockMs - (clockMs() - startedAtMs)).coerceAtLeast(0)

    /**
     * The pre-dispatch gate (§7.1 "budget.beforeModelCall()"). Reserves the next call: prompt
     * estimate ([promptChars] via [estimateTokens]) + [Limits.maxOutputTokens] + everything used
     * so far must fit the token budget. On Ok the turn and model-call counters advance (a
     * reserved call IS the turn's call, even if the gateway then fails — that is when the
     * estimate path of [accountModelCall] charges the tokens).
     */
    fun beforeModelCall(promptChars: Int): Verdict {
        val reserve = estimateTokens(promptChars) + limits.maxOutputTokens
        return when {
            turns + 1 > limits.maxTurns ->
                Verdict.Exhausted("BUDGET_TURNS", "the turn budget of ${limits.maxTurns} is used up")
            clockMs() - startedAtMs > limits.wallClockMs ->
                Verdict.Exhausted("BUDGET_TIME", "the wall-clock budget of ${limits.wallClockMs} ms is used up")
            modelCalls + 1 > limits.maxModelCalls ->
                Verdict.Exhausted("BUDGET_MODEL_CALLS", "the model-call budget of ${limits.maxModelCalls} is used up")
            reserve > limits.tokenBudget ->
                Verdict.Exhausted(
                    "BUDGET_CONTEXT",
                    "even one call does not fit: prompt estimate + max output = $reserve > budget ${limits.tokenBudget}",
                )
            tokensUsed + reserve > limits.tokenBudget ->
                Verdict.Exhausted(
                    "BUDGET_TOKENS",
                    "the token budget of ${limits.tokenBudget} is used up ($tokensUsed used, $reserve reserved)",
                )
            else -> {
                turns++
                modelCalls++
                Verdict.Ok
            }
        }
    }

    /** Charges the tokens of one model call; a missing usage is ESTIMATED, never 0 (§7.1). */
    fun accountModelCall(usage: Usage?, promptChars: Int) {
        tokensUsed += usage?.totalTokens?.toLong() ?: estimateTokens(promptChars)
    }

    /** The pre-action gate for device-touching tools; on Ok the action counter advances. */
    fun beforeAction(): Verdict = when {
        actions + 1 > limits.maxActions ->
            Verdict.Exhausted("BUDGET_ACTIONS", "the action budget of ${limits.maxActions} is used up")
        else -> {
            actions++
            Verdict.Ok
        }
    }

    companion object {
        /** The prompt estimate: ~4 characters per token (the §7.1 reserve convention). */
        const val CHARS_PER_TOKEN = 4

        fun estimateTokens(chars: Int): Long = (chars.toLong() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
    }
}
