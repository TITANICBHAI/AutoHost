package app.autohost.core.engine.triggers

import app.autohost.core.model.TriggerRule
import kotlinx.serialization.json.JsonObject

/**
 * P8-03: the guard chain a matching event must pass before a launch (plan §11.9 "Guards"), as a
 * pure decision against injectable inputs:
 *
 *   max_launch_count (lifetime cap) → cooldown_seconds (minimum gap) → debounce_ms (coalesce
 *   bursts into one launch).
 *
 * Debouncing is a two-phase protocol: [evaluate] answers [Decision.Debounce] and the controller
 * holds the payload; a later matching event REPLACES the held payload and restarts the window;
 * when the window lapses the controller asks [evaluate] again with `debounceElapsed = true` and
 * gets the fire decision for the last held payload.
 */
object TriggerDecisionEngine {

    data class RuleState(val lastFireAtMs: Long? = null, val launchCount: Int = 0)

    sealed class Decision {
        /** Launch now with the (held) payload. */
        data class Fire(val payload: JsonObject) : Decision()

        /** Inside the cooldown window: the event is dropped, [remainingMs] is reported to status. */
        data class Cooldown(val remainingMs: Long) : Decision()

        /** Lifetime launch cap reached; the rule stops firing until the counter is reset. */
        data object LimitReached : Decision()

        /** Start (or keep holding) the debounce window; the controller owns the timer. */
        data class Debounce(val windowMs: Long) : Decision()
    }

    fun evaluate(
        rule: TriggerRule,
        state: RuleState,
        payload: JsonObject,
        nowMs: Long,
        debounceElapsed: Boolean = false,
    ): Decision {
        val maxLaunchCount = rule.maxLaunchCount
        if (maxLaunchCount != null && state.launchCount >= maxLaunchCount) {
            return Decision.LimitReached
        }
        if (rule.cooldownSeconds > 0) {
            val last = state.lastFireAtMs
            if (last != null) {
                val remaining = last + rule.cooldownSeconds * 1000L - nowMs
                if (remaining > 0) return Decision.Cooldown(remaining)
            }
        }
        if (rule.debounceMs > 0 && !debounceElapsed) {
            return Decision.Debounce(rule.debounceMs)
        }
        return Decision.Fire(payload)
    }
}
