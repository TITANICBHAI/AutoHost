package app.autohost.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * P4-01: one schedule attached to a task (plan §11.2). JSON shape is the contract shared with the
 * Python reference (`sdk-python/autohost_ref/schedule.py`) and pinned by testing/golden/schedule_vectors.json:
 *
 * ```
 * { "id": "sch_..", "task": "app.autohost.sample.x", "type": "interval|daily|weekly|cron|once|window",
 *   "every_minutes": 30, "from": "fixed|previous_end", "times": ["09:00"], "days": [1..7],  // ISO, 1=Mon
 *   "cron": "0,10,20,30,40,50 9-17 * * 1-5", "at": "<ISO-8601 with offset>", "start": "09:00", "end": "17:00",
 *   "timezone": "UTC", "anchor": "<ISO>", "profile_ids": ["p1"], "fanout": {...}, "limits": {...},
 *   "conditions": {...}, "misfire": "skip|run_once|{catch_up}", "enabled": true }
 * ```
 *
 * Unknown JSON keys are ignored (forward compatible); [validate] reports everything the engine refuses.
 */
@Serializable
data class ScheduleRule(
    val id: String = "",
    val task: String = "",
    val type: String,
    @SerialName("every_minutes") val everyMinutes: Int? = null,
    val from: String? = null,
    val times: List<String> = emptyList(),
    val days: List<Int> = emptyList(),
    val cron: String? = null,
    val at: String? = null,
    val start: String? = null,
    val end: String? = null,
    val timezone: String = "UTC",
    val anchor: String? = null,
    @SerialName("profile_ids") val profileIds: List<String> = emptyList(),
    val fanout: FanOut = FanOut(),
    val limits: ScheduleLimits = ScheduleLimits(),
    val conditions: ScheduleConditions = ScheduleConditions(),
    @Serializable(with = MisfirePolicySerializer::class)
    val misfire: MisfirePolicy = MisfirePolicy.Skip,
    val enabled: Boolean = true,
) {
    companion object {
        val TYPES = setOf("interval", "daily", "weekly", "cron", "once", "window")

        private val lenient = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** Lenient parse for stored/UI JSON; malformed input yields null (callers fail closed). */
        fun fromJson(text: String): ScheduleRule? = runCatching {
            lenient.decodeFromString(serializer(), text)
        }.getOrNull()
    }

    /** All problems that would make the engine refuse the rule; empty when usable. */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        if (id.isBlank()) problems += "id must be a non-empty string"
        if (task.isBlank()) problems += "task must be a non-empty string"
        if (type !in TYPES) problems += "type must be one of $TYPES"
        when (type) {
            "interval", "window" ->
                if (everyMinutes == null || everyMinutes < 1) problems += "every_minutes must be a positive integer"
            "daily", "weekly" -> {
                if (times.isEmpty()) problems += "$type needs a non-empty times list"
                times.forEach { if (!isHhMm(it)) problems += "times entry '$it' must be HH:MM" }
            }
            "cron" -> if (cron == null || runCatching { CronSpec.parse(cron) }.isFailure) {
                problems += "cron must be a restricted 5-field expression"
            }
            "once" -> if (at == null || runCatching { parseIsoToMs(at) }.isFailure) {
                problems += "once at must be an ISO-8601 timestamp with offset"
            }
            "window" -> {
                if (!isHhMm(start)) problems += "window start must be HH:MM"
                if (!isHhMm(end)) problems += "window end must be HH:MM"
                if (isHhMm(start) && isHhMm(end) && toMinutes(start!!) >= toMinutes(end!!)) {
                    problems += "window end must be after start (no midnight wrap in v1)"
                }
            }
        }
        if (type in setOf("weekly", "window") && days.any { it !in 1..7 }) {
            problems += "days must be ISO weekdays 1=Mon..7=Sun"
        }
        problems += fanout.validate()
        problems += limits.validate()
        problems += conditions.validate()
        problems += misfire.validate()
        return problems
    }
}

/** Duration of one HH:MM in minutes-of-day; callers validate the shape first. */
fun toMinutes(hhmm: String): Int = hhmm.substring(0, 2).toInt() * 60 + hhmm.substring(3, 5).toInt()

fun isHhMm(value: String?): Boolean =
    value != null && Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(value)

/**
 * ISO-8601 with a mandatory offset ("Z" accepted) -> epoch millis. Both the model validator and
 * the engine use this so `at` / `anchor` / `end_at` mean exactly what the Python reference parses.
 */
fun parseIsoToMs(value: String): Long {
    require(value.length >= 20) { "timestamp must be ISO-8601 with an offset: $value" }
    val normalised = if (value.endsWith("Z")) value.dropLast(1) + "+00:00" else value
    return java.time.OffsetDateTime.parse(normalised).toInstant().toEpochMilli()
}
