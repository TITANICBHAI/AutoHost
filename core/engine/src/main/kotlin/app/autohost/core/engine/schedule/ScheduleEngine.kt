package app.autohost.core.engine.schedule

import app.autohost.core.common.Clock
import app.autohost.core.model.CronSpec
import app.autohost.core.model.ScheduleRule
import app.autohost.core.model.ScheduleConditions
import app.autohost.core.model.isHhMm
import app.autohost.core.model.parseIsoToMs
import app.autohost.core.model.toMinutes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Per-schedule counters the host persists (plan §11.3); the engine consumes them as inputs. */
data class ScheduleState(
    val runsTotal: Long = 0,
    val runsToday: Int = 0,
    /** Local day (rule timezone; UTC for interval rules) the [runsToday] counter belongs to, `uuuu-MM-dd`. */
    val dayKey: String? = null,
    val consecutiveFailures: Int = 0,
    /** For `from: previous_end` interval rules. */
    val lastRunEndMs: Long? = null,
)

/** One profile-targeted fire (P4-03 fan-out). Order in the list is the sequential execution order. */
data class FireTarget(val profileId: String?, val dueAtMs: Long)

/** Result of fire-time condition evaluation (plan §11.2/§11.5). */
data class ConditionOutcome(val ok: Boolean, val failedReason: String? = null) {
    companion object {
        val PASS = ConditionOutcome(true)
    }
}

/** Device snapshot the preflight evaluates conditions against. */
data class ConditionEnv(
    val nowMs: Long,
    val charging: Boolean = false,
    val batteryPct: Int = 100,
    val unmetered: Boolean = true,
    val screenAwake: Boolean = false,
    val screenUnlocked: Boolean = false,
)

/**
 * P4-01/P4-02: pure schedule math (plan §11.2). No clock reads and no I/O — callers pass
 * `afterMs`; the fake-clock suite (P4-16) drives every rule type, limit and misfire policy.
 * The Kotlin implementation must match the Python reference (`autohost_ref/schedule.py`);
 * testing/golden/schedule_vectors.json is the shared contract.
 */
class ScheduleEngine(private val clock: Clock) {

    fun nextFire(rule: ScheduleRule, afterMs: Long, state: ScheduleState = ScheduleState()): Long? {
        val limits = rule.limits
        if (limits.maxTotalRuns != null && state.runsTotal >= limits.maxTotalRuns) return null
        if (limits.stopAfterConsecutiveFailures != null &&
            state.consecutiveFailures >= limits.stopAfterConsecutiveFailures
        ) {
            return null
        }
        val endMs = limits.endAt?.let { runCatching { parseIsoToMs(it) }.getOrNull() }
        val perDay = limits.maxRunsPerDay
        val dayFull = perDay != null && state.dayKey != null && state.runsToday >= perDay

        return when (rule.type) {
            "once" -> {
                val at = rule.at?.let { runCatching { parseIsoToMs(it) }.getOrNull() } ?: return null
                if (at > afterMs && (endMs == null || at <= endMs)) at else null
            }
            "interval" -> {
                val every = rule.everyMinutes ?: return null
                val stepMs = every * 60_000L
                val anchor = rule.anchor?.let { runCatching { parseIsoToMs(it) }.getOrNull() }
                var candidate: Long
                if (rule.from == "previous_end" && state.lastRunEndMs != null) {
                    candidate = state.lastRunEndMs + stepMs
                    if (candidate <= afterMs) {
                        candidate = gridFrom(anchor ?: afterMs, stepMs, afterMs)
                    }
                } else {
                    candidate = gridFrom(anchor ?: afterMs, stepMs, afterMs)
                }
                if (dayFull && utcDayKey(candidate) == state.dayKey) {
                    val tomorrow = dayStartUtc(afterMs) + 86_400_000L
                    candidate = maxOf(candidate, tomorrow)
                }
                if (endMs != null && candidate > endMs) return null
                candidate
            }
            "daily", "weekly", "window", "cron" -> nextZonedFire(rule, afterMs, endMs, dayFull, state.dayKey)
            else -> null
        }
    }

    /** Next [n] fires for the UI preview (plan §11.2/S10); future days assume a fresh daily counter. */
    fun nextFires(rule: ScheduleRule, afterMs: Long, state: ScheduleState = ScheduleState(), n: Int = 5): List<Long> {
        val out = mutableListOf<Long>()
        var cursor = afterMs
        var working = state
        var guard = 0
        while (out.size < n && guard < n * 64 + 64) {
            guard++
            val fire = nextFire(rule, cursor, working) ?: break
            out += fire
            cursor = fire
            val zone = if (rule.type in TZED_TYPES) zoneOf(rule.timezone) else ZoneOffset.UTC
            val fireDay = localDayKey(fire, zone)
            working = if (working.dayKey != fireDay) {
                working.copy(runsToday = 1, dayKey = fireDay)
            } else {
                working.copy(runsToday = working.runsToday + 1)
            }
        }
        return out
    }

    /** P4-03: fan one due fire out over the rule's profiles (§11.2). */
    fun fanOut(rule: ScheduleRule, dueAtMs: Long): List<FireTarget> {
        val profiles = rule.profileIds
        if (profiles.isEmpty()) return listOf(FireTarget(null, dueAtMs))
        return when (rule.fanout.mode) {
            "staggered" -> profiles.mapIndexed { i, p ->
                FireTarget(p, dueAtMs + i * rule.fanout.staggerSeconds * 1000L)
            }
            else -> profiles.map { FireTarget(it, dueAtMs) } // sequential keeps list order; parallel shares the due
        }
    }

    /** Fire-time conditions (plan §11.2); the preflight (§11.5) applies the outcome. */
    fun evaluateConditions(conditions: ScheduleConditions, env: ConditionEnv): ConditionOutcome {
        conditions.charging?.let { if (it && !env.charging) return ConditionOutcome(false, "charging") }
        conditions.batteryMin?.let { if (env.batteryPct < it) return ConditionOutcome(false, "battery_min") }
        conditions.unmetered?.let { if (it && !env.unmetered) return ConditionOutcome(false, "unmetered") }
        conditions.screenAwake?.let { if (it && !env.screenAwake) return ConditionOutcome(false, "screen_awake") }
        conditions.screenUnlocked?.let { if (it && !env.screenUnlocked) return ConditionOutcome(false, "screen_unlocked") }
        val window = conditions.window ?: return ConditionOutcome.PASS
        val zone = zoneOf(window.timezone)
        val nowLocal = LocalDateTime.ofInstant(Instant.ofEpochMilli(env.nowMs), zone)
        if (window.days.isNotEmpty() && nowLocal.dayOfWeek.value !in window.days) {
            return ConditionOutcome(false, "window_day")
        }
        if (!isHhMm(window.start) || !isHhMm(window.end)) return ConditionOutcome(false, "window_invalid")
        val minuteOfDay = nowLocal.hour * 60 + nowLocal.minute
        if (minuteOfDay < toMinutes(window.start) || minuteOfDay >= toMinutes(window.end)) {
            return ConditionOutcome(false, "window_time")
        }
        return ConditionOutcome.PASS
    }

    /** Current counter-day key for persisted state; UTC for interval rules, rule tz otherwise. */
    fun dayKeyFor(rule: ScheduleRule, atMs: Long = clock.nowMs()): String {
        val zone = if (rule.type in TZED_TYPES) zoneOf(rule.timezone) else ZoneOffset.UTC
        return localDayKey(atMs, zone)
    }

    // ------------------------------------------------------------------ internals

    private fun nextZonedFire(
        rule: ScheduleRule,
        afterMs: Long,
        endMs: Long?,
        dayFull: Boolean,
        counterDay: String?,
    ): Long? {
        val zone = zoneOf(rule.timezone)
        var cursor = afterMs
        var blockedToday = dayFull
        repeat(370) { // at most one year of day iterations
            val day = LocalDate.ofInstant(Instant.ofEpochMilli(cursor), zone)
            for (candidate in dayCandidates(rule, day, zone)) {
                if (candidate <= cursor) continue
                if (blockedToday && counterDay != null && localDayKey(candidate, zone) == counterDay) continue
                if (endMs != null && candidate > endMs) return null
                return candidate
            }
            // move to the next local day; future days assume a fresh counter
            cursor = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            blockedToday = false
        }
        return null
    }

    private fun dayCandidates(rule: ScheduleRule, day: LocalDate, zone: ZoneId): List<Long> = when (rule.type) {
        "daily" -> rule.times.filter { isHhMm(it) }.sorted().map { at(day, it, zone) }
        "weekly" -> {
            if (day.dayOfWeek.value !in rule.days) emptyList()
            else rule.times.filter { isHhMm(it) }.sorted().map { at(day, it, zone) }
        }
        "window" -> {
            if (day.dayOfWeek.value !in rule.days || !isHhMm(rule.start) || !isHhMm(rule.end) ||
                rule.everyMinutes == null
            ) {
                emptyList()
            } else {
                val first = at(day, rule.start!!, zone)
                val last = at(day, rule.end!!, zone)
                val step = rule.everyMinutes * 60_000L
                generateSequence(first) { it + step }.takeWhile { it < last }.toList()
            }
        }
        "cron" -> {
            val spec = cronOf(rule) ?: return emptyList()
            if (day.monthValue !in spec.months) return emptyList()
            val domOk = day.dayOfMonth in spec.dom
            val dowOk = (day.dayOfWeek.value % 7) in spec.dow
            if (!spec.dayMatches(domOk, dowOk)) return emptyList()
            val out = mutableListOf<Long>()
            for (h in spec.hours.sorted()) {
                for (m in spec.minutes.sorted()) {
                    out += at(day, "%02d:%02d".format(h, m), zone)
                }
            }
            out.sorted()
        }
        else -> emptyList()
    }

    private fun at(day: LocalDate, hhmm: String, zone: ZoneId): Long {
        val minutes = toMinutes(hhmm)
        return LocalDateTime.of(day, java.time.LocalTime.of(minutes / 60, minutes % 60))
            .atZone(zone).toInstant().toEpochMilli()
    }

    private fun cronOf(rule: ScheduleRule): CronSpec? {
        cachedCrons[rule.id]?.let { return it }
        val expr = rule.cron ?: return null
        val spec = runCatching { CronSpec.parse(expr) }.getOrNull() ?: return null
        cachedCrons[rule.id] = spec
        return spec
    }

    private val cachedCrons = java.util.concurrent.ConcurrentHashMap<String, CronSpec>()

    companion object {
        private val TZED_TYPES = setOf("daily", "weekly", "cron", "window")

        fun zoneOf(name: String): ZoneId = runCatching { ZoneId.of(name) }.getOrDefault(ZoneOffset.UTC)

        private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd")

        fun localDayKey(epochMs: Long, zone: ZoneId): String =
            DAY_FMT.withZone(zone).format(Instant.ofEpochMilli(epochMs))

        fun utcDayKey(epochMs: Long): String = localDayKey(epochMs, ZoneOffset.UTC)

        fun dayStartUtc(epochMs: Long): Long =
            Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        /** First grid point strictly after afterMs. */
        fun gridFrom(anchorMs: Long, stepMs: Long, afterMs: Long): Long =
            anchorMs + ((afterMs - anchorMs) / stepMs + 1) * stepMs
    }
}
