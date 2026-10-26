package app.autohost.capabilities.a11y

/**
 * Pure throttling policy for screenshots (plan Phase 3): the accessibility screenshot API is expensive and
 * the framework rate-limits it, so the host enforces a minimum interval per scope and a small burst budget.
 */
class ScreenshotThrottlePolicy(
    private val minIntervalMs: Long = 1_000,
    private val burstSize: Int = 3,
) {
    sealed class Decision {
        /** Take the screenshot now. */
        object Allow : Decision() {
            override fun toString(): String = "Allow"
        }

        /** Refuse with RATE_LIMITED; [retryAfterMs] says when to try again. */
        data class Limit(val retryAfterMs: Long) : Decision()
    }

    private val taken = ArrayDeque<Long>()

    /**
     * @param nowMs       wall clock
     * @param forced      caller explicitly asked to bypass the burst budget (evidence capture on failure) —
     *                    still honours the minimum interval.
     */
    @Synchronized
    fun evaluate(nowMs: Long, forced: Boolean = false): Decision {
        val last = taken.lastOrNull()
        if (last != null && nowMs - last < minIntervalMs) {
            return Decision.Limit(minIntervalMs - (nowMs - last))
        }
        val recent = taken.count { nowMs - it < 60_000 }
        if (!forced && recent >= burstSize) {
            return Decision.Limit(60_000 - (nowMs - taken.first { nowMs - it < 60_000 }))
        }
        taken.addLast(nowMs)
        while (taken.size > 64) taken.removeFirst()
        return Decision.Allow
    }
}
