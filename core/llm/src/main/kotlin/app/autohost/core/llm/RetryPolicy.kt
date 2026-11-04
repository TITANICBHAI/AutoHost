package app.autohost.core.llm

/**
 * P13-04 (ADR-090, `docs/MODEL_GATEWAY.md` §5): the pure, deadline-aware retry decision.
 *
 * Rules: at most **3 total attempts**; the delay is `1 s · 2^(attempt-1)` ±20 % jitter (injected
 * [Jitter] seam — tests use a fixed one); a `Retry-After` is honoured but **capped at 30 s**;
 * retried: HTTP 408/425/429/5xx, timeouts, `IOException` before any response; never retried:
 * 400/401/403/404/422, a response that parsed and contains tool calls, anything after
 * cancellation, and any attempt whose delay would pass the run's remaining wall-clock budget.
 * The delay itself is a cancellable suspend at the gateway — this class only decides.
 */
object RetryPolicy {

    const val MAX_ATTEMPTS = 3
    const val RETRY_AFTER_CAP_MS = 30_000L
    private const val BASE_DELAY_MS = 1_000L

    /** Jitter seam: production injects ±20 % randomness; tests inject a fixed value. */
    fun interface Jitter {
        fun apply(delayMs: Long): Long
    }

    val NO_JITTER: Jitter = Jitter { it }

    /** ±20 % multiplicative jitter. */
    val TWENTY_PERCENT: Jitter = Jitter { delay ->
        val spread = (delay * 0.2).toLong()
        if (spread == 0L) delay else delay - spread + (0..spread * 2).random()
    }

    enum class OutcomeKind { SUCCESS, RATE_LIMITED, TRANSIENT, AUTH, INVALID, NETWORK, CANCELLED }

    data class Outcome(
        val kind: OutcomeKind,
        val attempt: Int, // 1-based attempt that just failed/succeeded
        val retryAfterMs: Long? = null,
        val hasToolCalls: Boolean = false,
    )

    sealed class Decision {
        /** Retry after `delayMs` (already jittered and capped). */
        data class Retry(val delayMs: Long) : Decision()

        /** Give up; the gateway maps the outcome to [LlmError]. */
        data object GiveUp : Decision()
    }

    fun plan(
        outcome: Outcome,
        remainingRunBudgetMs: Long,
        jitter: Jitter = NO_JITTER,
    ): Decision {
        if (outcome.kind == OutcomeKind.CANCELLED) return Decision.GiveUp
        if (outcome.hasToolCalls) return Decision.GiveUp // never replay a parsed response
        if (outcome.attempt >= MAX_ATTEMPTS) return Decision.GiveUp
        val retryable = when (outcome.kind) {
            OutcomeKind.RATE_LIMITED, OutcomeKind.TRANSIENT, OutcomeKind.NETWORK -> true
            OutcomeKind.AUTH, OutcomeKind.INVALID, OutcomeKind.SUCCESS -> false
            OutcomeKind.CANCELLED -> false
        }
        if (!retryable) return Decision.GiveUp
        val backoff = BASE_DELAY_MS * (1L shl (outcome.attempt - 1).coerceAtMost(6))
        val delay = when {
            outcome.retryAfterMs != null && outcome.retryAfterMs > 0 ->
                outcome.retryAfterMs.coerceAtMost(RETRY_AFTER_CAP_MS)
            else -> jitter.apply(backoff).coerceAtLeast(0L)
        }
        // The delay must fit the run's remaining wall-clock budget (the attempt itself runs after).
        if (delay >= remainingRunBudgetMs) return Decision.GiveUp
        return Decision.Retry(delay)
    }
}
