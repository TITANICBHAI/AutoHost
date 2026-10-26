package app.autohost.core.engine.orchestrator

/**
 * P5-07 (plan §12.5): the pure idle-watchdog decision. `lastActivityAt` moves on every broker call
 * (via the audit sink) and every worker event; at 80 % of `execution.idle_timeout_seconds` the run
 * gets a warning event, at 100 % it is cancelled with `FAILED:IDLE_TIMEOUT`.
 *
 * Pure JVM logic — the orchestrator only wires the clock, the activity feed and the verdicts.
 */
object IdleWatchdogPolicy {

    /** Idle budget consumed by a run with no activity (fraction of the limit, 0.0..1.0+). */
    fun consumed(lastActivityMs: Long, nowMs: Long, idleTimeoutMs: Long): Float {
        if (idleTimeoutMs <= 0L) return 0f // non-positive limit = watchdog disabled
        val idle = (nowMs - lastActivityMs).coerceAtLeast(0L)
        return idle.toFloat() / idleTimeoutMs.toFloat()
    }

    sealed interface Verdict {
        data object None : Verdict
        data class Warn(val consumedPercent: Int) : Verdict
        data object Expire : Verdict
    }

    /**
     * The verdict for one check tick. WARN repeats while the run sits between 80 % and 100 % — the
     * orchestrator de-duplicates by only appending the first warn event per run.
     */
    fun verdict(lastActivityMs: Long, nowMs: Long, idleTimeoutMs: Long): Verdict {
        if (idleTimeoutMs <= 0L) return Verdict.None
        val idle = (nowMs - lastActivityMs).coerceAtLeast(0L)
        return when {
            idle >= idleTimeoutMs -> Verdict.Expire
            idle * 100L >= idleTimeoutMs * 80L ->
                Verdict.Warn(((idle * 100L) / idleTimeoutMs).toInt().coerceAtMost(99))
            else -> Verdict.None
        }
    }
}
