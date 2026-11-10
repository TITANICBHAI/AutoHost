package app.autohost.core.engine.schedule

import app.autohost.core.common.Clock
import app.autohost.core.common.Ids
import app.autohost.core.model.FanOut
import app.autohost.core.model.MisfirePolicy
import app.autohost.core.model.ScheduleConditions
import app.autohost.core.model.ScheduleLimits
import app.autohost.core.model.ScheduleRule
import app.autohost.core.model.isHhMm
import app.autohost.core.model.parseIsoToMs
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The schedule *form* (UI_SPEC S10) as pure Kotlin: what a person fills in on the Schedule screen becomes the same
 * [ScheduleRule] the engine already runs, so nobody has to type JSON.
 *
 * The contract is `testing/golden/schedule_form_vectors.json` (made by `tools/gen_schedule_form_vectors.py` from the
 * Python reference scheduler). Error keys are stable identifiers; the UI turns them into plain sentences.
 * No clock and no randomness in here: callers pass `nowMs`, and ids come from [newRuleId].
 */
object ScheduleForm {

    /** What the screen collects. Fields a repeat kind does not use stay at their defaults. */
    data class Form(
        val task: String = "",
        /** IANA zone id: always the phone's own zone, never the model default "UTC". */
        val zone: String = "UTC",
        /** One of [INTERVAL], [DAILY], [WEEKLY], [ONCE], [WINDOW]. */
        val repeat: String = DAILY,
        val every: Int = 30,
        /** [MINUTES] or [HOURS]. */
        val unit: String = MINUTES,
        val times: List<String> = emptyList(),
        /** ISO weekdays, 1 = Monday .. 7 = Sunday. */
        val days: List<Int> = emptyList(),
        /** `YYYY-MM-DD`, for [ONCE]. */
        val date: String = "",
        /** `HH:MM`, for [ONCE]. */
        val time: String = "",
        /** `HH:MM`, for [WINDOW]. */
        val from: String = "",
        val to: String = "",
        val misfire: MisfirePolicy = MisfirePolicy.Skip,
        val profileIds: List<String> = emptyList(),
        val enabled: Boolean = true,
    )

    const val INTERVAL = "interval"
    const val DAILY = "daily"
    const val WEEKLY = "weekly"
    const val ONCE = "once"
    const val WINDOW = "window"
    const val MINUTES = "minutes"
    const val HOURS = "hours"

    /** Longest repeat gap the form accepts: one week. */
    const val MAX_EVERY_MINUTES: Long = 7L * 24 * 60

    private val REPEATS = setOf(INTERVAL, DAILY, WEEKLY, ONCE, WINDOW)

    /** `sch_` + the last 8 characters of a time-based id, lower-cased. Time comes from [clock] only. */
    fun newRuleId(clock: Clock): String = "sch_" + Ids.ulid(clock.nowMs()).takeLast(8).lowercase()

    /** Error keys, in a fixed order. Empty list = the form is usable. */
    fun validate(form: Form, nowMs: Long): List<String> {
        val errors = mutableListOf<String>()
        if (form.task.isEmpty()) errors += "no_task"
        val zone = zoneOrNull(form.zone)
        if (zone == null) errors += "unknown_zone"
        val repeat = form.repeat
        if (repeat == INTERVAL || repeat == WINDOW) {
            val minutes = everyMinutes(form)
            if (minutes < 1) {
                errors += "every_below_one"
            } else if (minutes > MAX_EVERY_MINUTES) {
                errors += "every_too_large"
            }
        }
        if (repeat == DAILY || repeat == WEEKLY) {
            if (form.times.isEmpty()) {
                errors += "no_times"
            } else if (!form.times.all { isHhMm(it) }) {
                errors += "bad_time"
            }
        }
        if (repeat == WEEKLY) {
            if (form.days.isEmpty()) {
                errors += "no_days"
            } else if (!form.days.all { it in 1..7 }) {
                errors += "bad_days"
            }
        }
        if (repeat == WINDOW) {
            if (!(isHhMm(form.from) && isHhMm(form.to))) {
                errors += "bad_time"
            } else if (form.from >= form.to) {
                errors += "window_end_not_after_start"
            }
            if (form.days.isNotEmpty() && !form.days.all { it in 1..7 }) errors += "bad_days"
        }
        if (repeat == ONCE && zone != null) {
            val local = localDateTimeOrNull(form.date, form.time)
            if (local == null) {
                errors += "bad_date"
            } else {
                val zoned = local.atZone(zone)
                if (zoned.toLocalDateTime() != local) {
                    errors += "time_does_not_exist" // inside a daylight-saving gap
                } else if (zoned.toInstant().toEpochMilli() <= nowMs) {
                    errors += "once_in_past"
                }
            }
        }
        val misfire = form.misfire
        if (misfire is MisfirePolicy.CatchUp && misfire.max < 1) errors += "bad_misfire"
        if (repeat !in REPEATS) errors += "bad_repeat"
        return errors
    }

    /**
     * The mapping. Keys that hold their default are left out, exactly as a hand-written rule would leave them.
     * Call [validate] first; an unusable form throws instead of producing a rule the engine would refuse.
     */
    fun toRule(form: Form, ruleId: String, nowMs: Long): ScheduleRule {
        val problems = validate(form, nowMs)
        require(problems.isEmpty()) { "form is not usable: $problems" }
        val zone = ZoneId.of(form.zone)
        return when (form.repeat) {
            INTERVAL -> ScheduleRule(
                id = ruleId, task = form.task, type = INTERVAL, timezone = form.zone,
                everyMinutes = everyMinutes(form).toInt(),
                from = "fixed",
                // Anchored at creation time so "every 30 minutes" is a fixed grid, not re-anchored at each re-arm.
                anchor = isoOffset(Instant.ofEpochMilli(nowMs).atZone(zone).toOffsetDateTime()),
            )
            DAILY -> ScheduleRule(
                id = ruleId, task = form.task, type = DAILY, timezone = form.zone,
                times = form.times.sorted(),
            )
            WEEKLY -> ScheduleRule(
                id = ruleId, task = form.task, type = WEEKLY, timezone = form.zone,
                times = form.times.sorted(), days = form.days.distinct().sorted(),
            )
            ONCE -> {
                val local = checkNotNull(localDateTimeOrNull(form.date, form.time))
                ScheduleRule(
                    id = ruleId, task = form.task, type = ONCE, timezone = form.zone,
                    at = isoOffset(local.atZone(zone).toOffsetDateTime()),
                )
            }
            else -> ScheduleRule( // WINDOW
                id = ruleId, task = form.task, type = WINDOW, timezone = form.zone,
                everyMinutes = everyMinutes(form).toInt(), start = form.from, end = form.to,
                days = form.days.distinct().sorted(),
            )
        }.let { base ->
            base.copy(
                misfire = form.misfire,
                profileIds = form.profileIds,
                enabled = form.enabled,
            )
        }
    }

    /**
     * Shows a stored rule on the form, or returns null when the form cannot show it (cron, limits, conditions,
     * fan-out settings, "after the previous run ends" intervals, anything with an odd anchor). Those rules stay
     * visible, read-only, in the developer section. The interval anchor is not kept: saving re-anchors to "now".
     */
    fun fromRule(rule: ScheduleRule): Form? {
        if (rule.limits != ScheduleLimits() || rule.conditions != ScheduleConditions() || rule.fanout != FanOut()) return null
        val zone = zoneOrNull(rule.timezone) ?: return null
        val base = Form(
            task = rule.task, zone = rule.timezone, repeat = rule.type,
            misfire = rule.misfire, profileIds = rule.profileIds, enabled = rule.enabled,
        )
        return when (rule.type) {
            INTERVAL -> {
                if (rule.from != null && rule.from != "fixed") return null
                val anchor = rule.anchor
                if (anchor != null && runCatching { parseIsoToMs(anchor) }.isFailure) return null
                val minutes = rule.everyMinutes ?: return null
                splitEvery(base, minutes)
            }
            DAILY -> base.copy(times = rule.times)
            WEEKLY -> base.copy(times = rule.times, days = rule.days)
            ONCE -> {
                val at = rule.at ?: return null
                val local = runCatching { Instant.ofEpochMilli(parseIsoToMs(at)).atZone(zone).toLocalDateTime() }
                    .getOrNull() ?: return null
                if (local.second != 0 || local.nano != 0) return null
                base.copy(
                    date = local.toLocalDate().toString(),
                    time = "%02d:%02d".format(local.hour, local.minute),
                )
            }
            WINDOW -> {
                val minutes = rule.everyMinutes ?: return null
                splitEvery(base.copy(from = rule.start ?: return null, to = rule.end ?: return null, days = rule.days), minutes)
            }
            else -> null
        }
    }

    private fun splitEvery(base: Form, minutes: Int): Form =
        if (minutes >= 60 && minutes % 60 == 0) base.copy(every = minutes / 60, unit = HOURS)
        else base.copy(every = minutes, unit = MINUTES)

    private fun everyMinutes(form: Form): Long =
        if (form.unit == HOURS) form.every.toLong() * 60 else form.every.toLong()

    private fun zoneOrNull(name: String): ZoneId? = runCatching { ZoneId.of(name) }.getOrNull()

    private fun localDateTimeOrNull(date: String, time: String): LocalDateTime? {
        if (!isHhMm(time)) return null
        val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        return LocalDateTime.of(day, LocalTime.of(time.substring(0, 2).toInt(), time.substring(3, 5).toInt()))
    }

    /** ISO-8601 with an offset, `Z` for UTC and seconds always printed (`OffsetDateTime.toString()` drops zero seconds). */
    private fun isoOffset(moment: java.time.OffsetDateTime): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(moment.truncatedTo(ChronoUnit.SECONDS))
}
