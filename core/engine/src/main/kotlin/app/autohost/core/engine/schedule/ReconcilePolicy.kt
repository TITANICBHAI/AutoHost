package app.autohost.core.engine.schedule

import app.autohost.core.model.MisfirePolicy
import app.autohost.core.model.ScheduleRule

/**
 * P4-06: what the periodic reconcile worker (~20 min, plan §11.3) decides from pure inputs.
 * The worker supplies persisted state + live backend facts; this policy never touches Android.
 */
data class ReconcileInput(
    val nowMs: Long,
    val armedFireId: String?,
    val armedAtMs: Long?,
    val nextFireAtMs: Long?,
    val graceMs: Long = DEFAULT_GRACE_MS,
    val exactAlarmsAllowed: Boolean,
    val backendKind: String, // alarm | work | job | auto-resolved
)

sealed interface ReconcileDecision {
    /** Alarm intact and still in the future — nothing to do. */
    data object Healthy : ReconcileDecision

    /** No alarm armed (deleted/cleared) but fires are still ahead — re-arm only. */
    data object Rearm : ReconcileDecision

    /** Fires were missed beyond the grace window: run these now (already ranked by the misfire policy). */
    data class Missed(val fires: List<Long>, val policyName: String) : ReconcileDecision

    /** The rule ended (limits/exhausted) — disarm and stop scheduling it. */
    data object Finished : ReconcileDecision
}

object ReconcilePolicy {

    /**
     * Decide one reconcile pass for one schedule. `state` carries the persisted counters; the
     * misfire policy (P4-02) ranks any missed fires. Missing alarm + no missed fires = re-arm.
     */
    fun decide(
        rule: ScheduleRule,
        engine: ScheduleEngine,
        state: ScheduleState,
        input: ReconcileInput,
    ): ReconcileDecision {
        val next = input.nextFireAtMs
            ?: return if (engine.nextFire(rule, input.nowMs, state) == null) {
                ReconcileDecision.Finished
            } else {
                ReconcileDecision.Rearm
            }
        if (next > input.nowMs) {
            return if (input.armedFireId == null) ReconcileDecision.Rearm else ReconcileDecision.Healthy
        }
        // nextFireAt is in the past: missed fires are those in (next, now - grace]
        val horizon = input.nowMs - input.graceMs
        val missed = MisfireResolver.missedFires(engine, rule, next, horizon, state)
        if (missed.isEmpty()) {
            // inside the grace window: the alarm may still deliver; re-arm only if it is gone
            return if (input.armedFireId == null) ReconcileDecision.Rearm else ReconcileDecision.Healthy
        }
        val policyName = when (val policy = rule.misfire) {
            is MisfirePolicy.Skip -> "skip"
            is MisfirePolicy.RunOnce -> "run_once"
            is MisfirePolicy.CatchUp -> "catch_up(${policy.max})"
        }
        return ReconcileDecision.Missed(MisfireResolver.resolve(rule.misfire, missed), policyName)
    }

    const val DEFAULT_GRACE_MS = 60_000L
}
