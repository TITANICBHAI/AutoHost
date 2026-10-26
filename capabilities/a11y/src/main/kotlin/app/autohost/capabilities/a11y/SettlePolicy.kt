package app.autohost.capabilities.a11y

/**
 * Pure settle decisions (plan §5.7): "settled" means the UI stopped changing for [quietMs], optionally
 * requiring [consecutiveEqual] identical snapshot fingerprints. Also owns the `retry_if_no_change` rule:
 * after an action that changed nothing, repeat it *once* — never loop.
 */
object SettlePolicy {

    sealed class Decision {
        object Settled : Decision() {
            override fun toString(): String = "Settled"
        }

        data class KeepWaiting(val detail: String) : Decision()

        /** Timeout elapsed while the UI kept changing. */
        object TimedOut : Decision() {
            override fun toString(): String = "TimedOut"
        }
    }

    /**
     * @param quietForMs     how long the window has been quiet (no content/state change events)
     * @param elapsedMs      how long the caller has been waiting
     * @param lastChangedMs  when the last change event arrived (monotonic)
     * @param nowMs          monotonic now
     */
    fun evaluate(
        quietMs: Long,
        timeoutMs: Long,
        elapsedMs: Long,
        quietForMs: Long = 0,
        nowMs: Long = 0,
        lastChangedMs: Long = 0,
        consecutiveEqual: Int = 1,
        equalStreak: Int = 1,
    ): Decision {
        val quietEnough = (if (lastChangedMs > 0) nowMs - lastChangedMs else quietForMs) >= quietMs
        val streakOk = equalStreak >= consecutiveEqual
        if (quietEnough && streakOk) return Decision.Settled
        if (elapsedMs >= timeoutMs) return Decision.TimedOut
        val wait = buildString {
            if (!quietEnough) append("ui changed ").append(if (lastChangedMs > 0) nowMs - lastChangedMs else quietForMs).append("ms ago (want ").append(quietMs).append("ms quiet)")
            if (!streakOk) {
                if (isNotEmpty()) append("; ")
                append("equal-snapshot streak ").append(equalStreak).append("/").append(consecutiveEqual)
            }
        }
        return Decision.KeepWaiting(wait)
    }

    /**
     * `retry_if_no_change`: decide whether a failed/ineffective action should be repeated once.
     * [changed] = did the snapshot fingerprint change since before the action; [alreadyRetried] guards
     * the once-only rule.
     */
    fun retryIfNoChange(changed: Boolean, alreadyRetried: Boolean): Boolean = !changed && !alreadyRetried

    /** Stable fingerprint of a snapshot used for settle-streak and no-change checks. */
    fun fingerprint(nodes: List<app.autohost.core.model.UiNode>): String = nodes.joinToString("|") { n ->
        "${n.index}:${n.id}:${n.text}:${n.desc}:${n.bounds.l},${n.bounds.t},${n.bounds.r},${n.bounds.b}"
    }
}
