package app.autohost.capabilities.projection

/**
 * Pure throttling policy for screenshot captures. The projection capability must stay independent
 * of the accessibility module, so this policy lives alongside the provider that uses it.
 */
class ScreenshotThrottlePolicy(
    private val minIntervalMs: Long = 1_000,
    private val burstSize: Int = 3,
) {
    sealed class Decision {
        object Allow : Decision() {
            override fun toString(): String = "Allow"
        }

        data class Limit(val retryAfterMs: Long) : Decision()
    }

    private val taken = ArrayDeque<Long>()

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
