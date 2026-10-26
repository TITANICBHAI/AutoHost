package app.autohost.core.engine.schedule

import app.autohost.core.model.MisfirePolicy

/**
 * P4-02: ranks already-missed fires (plan §11.2). `skip` drops them; `run_once` replays exactly
 * one (the latest missed fire, so the task sees the most recent intent); `catch_up(n)` replays
 * the first n missed fires in order and drops the rest — bounded, never a thundering herd.
 */
object MisfireResolver {

    /** Fires to launch now for the missed fires (ascending); may be empty. */
    fun resolve(policy: MisfirePolicy, missed: List<Long>): List<Long> = when (policy) {
        is MisfirePolicy.Skip -> emptyList()
        is MisfirePolicy.RunOnce -> missed.takeLast(1)
        is MisfirePolicy.CatchUp -> missed.take(policy.max)
    }

    /**
     * All rule fires in (persisted nextFireAt, horizon] — bounded so pathological rules cannot
     * spin (plan §11.3 reconcile). The caller passes the engine so the rule computes the fires.
     */
    fun missedFires(
        engine: ScheduleEngine,
        rule: app.autohost.core.model.ScheduleRule,
        nextFireAtMs: Long,
        horizonMs: Long,
        state: ScheduleState = ScheduleState(),
        max: Int = MAX_CATCH_UP_SCAN,
    ): List<Long> {
        if (horizonMs <= nextFireAtMs) return emptyList()
        val out = mutableListOf<Long>()
        var cursor = nextFireAtMs
        val working = state
        while (out.size < max) {
            val fire = engine.nextFire(rule, cursor, working) ?: break
            if (fire > horizonMs) break
            out += fire
            cursor = fire
        }
        return out
    }

    const val MAX_CATCH_UP_SCAN = 100
}
