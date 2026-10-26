package app.autohost.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * P4-04: one trigger rule (plan §11.9) — the single data model for schedules and event triggers.
 * Schema: schemas/trigger-rule.schema.json. Validation is strict with
 * `TRIGGER_RULE_INVALID(field)` as the error code (schemas/errors.json).
 */
@Serializable
data class TriggerRule(
    val id: String = "",
    val task: String,
    val profile: String? = null,
    val source: String,
    val params: JsonObject = JsonObject(emptyMap()),
    @SerialName("cooldown_seconds") val cooldownSeconds: Long = 0,
    @SerialName("max_launch_count") val maxLaunchCount: Int? = null,
    @SerialName("debounce_ms") val debounceMs: Long = 0,
    @SerialName("busy_policy") val busyPolicy: String = "drop",
    @SerialName("queue_max") val queueMax: Int = 5,
    val enabled: Boolean = true,
) {
    companion object {
        private val lenient = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun fromJson(text: String): TriggerRule? = runCatching {
            lenient.decodeFromString(serializer(), text)
        }.getOrNull()
    }

    /** `TRIGGER_RULE_INVALID(field)` reasons; empty when the rule is usable. */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        if (task.isBlank()) problems += "task"
        if (source !in TriggerSource.ALL) {
            problems += "source"
            return problems
        }
        for (field in TriggerSource.requiredParams(source)) {
            if (params[field] == null) problems += "params.$field"
        }
        when (source) {
            "time.delay" -> if ((params["seconds"] as? JsonPrimitive)?.intOrNull?.let { it < 1 } != false) {
                problems += "params.seconds"
            }
            "time.daily" -> if (!(params["time"] as? JsonPrimitive)?.content?.let { isHhMm(it) }.let { it == true }) {
                problems += "params.time"
            }
            "time.weekly" -> {
                val days = (params["days"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                if (days.isNullOrEmpty() || days.any { it !in 1..7 }) problems += "params.days"
                if (!(params["time"] as? JsonPrimitive)?.content?.let { isHhMm(it) }.let { it == true }) {
                    problems += "params.time"
                }
            }
            "battery.level" -> {
                if ((params["op"] as? JsonPrimitive)?.content !in setOf("lt", "lte", "gt", "gte", "eq")) {
                    problems += "params.op"
                }
                val pct = (params["percent"] as? JsonPrimitive)?.intOrNull
                if (pct == null || pct !in 0..100) problems += "params.percent"
            }
        }
        if (cooldownSeconds < 0) problems += "cooldown_seconds"
        if (maxLaunchCount != null && maxLaunchCount < 1) problems += "max_launch_count"
        if (debounceMs < 0) problems += "debounce_ms"
        if (busyPolicy !in setOf("queue", "drop", "replace")) problems += "busy_policy"
        if (queueMax < 1) problems += "queue_max"
        return problems
    }

    /**
     * Maps a timed source onto the ScheduleRule the ScheduleEngine arms (plan §11.9).
     * `time.delay` needs the arm time, so the scheduler controller builds its once-rule directly.
     */
    fun toScheduleRule(timezone: String = "UTC"): ScheduleRule? {
        if (source !in TriggerSource.TIMED) return null
        fun param(key: String): String? = (params[key] as? JsonPrimitive)?.content
        val ruleId = if (id.isBlank()) "tr_$task" else id
        return when (source) {
            "time.absolute" -> ScheduleRule(
                id = ruleId, task = task, type = "once",
                at = param("at"), timezone = "UTC",
            )
            "time.daily" -> ScheduleRule(
                id = ruleId, task = task, type = "daily",
                times = listOfNotNull(param("time")), timezone = timezone,
            )
            "time.weekly" -> ScheduleRule(
                id = ruleId, task = task, type = "weekly",
                days = (params["days"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList(),
                times = listOfNotNull(param("time")), timezone = timezone,
            )
            else -> null
        }
    }
}
