package app.autohost.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * P4-02: fire-time conditions (plan §11.2). Conditions never move the fire time — the preflight
 * (§11.5) evaluates them against a device snapshot and may skip or wait. Pure data here; the
 * evaluation lives in `:core:engine` `ScheduleEngine.evaluateConditions` so it shares the vectors.
 */
@Serializable
data class ScheduleConditions(
    val charging: Boolean? = null,
    @SerialName("battery_min") val batteryMin: Int? = null,
    val unmetered: Boolean? = null,
    @SerialName("screen_awake") val screenAwake: Boolean? = null,
    @SerialName("screen_unlocked") val screenUnlocked: Boolean? = null,
    val window: TimeWindow? = null,
) {
    @Serializable
    data class TimeWindow(
        val start: String,
        val end: String,
        val days: List<Int> = emptyList(), // ISO 1=Mon..7=Sun; empty = every day
        val timezone: String = "UTC",
    )

    fun validate(): List<String> = buildList {
        if (batteryMin != null && batteryMin !in 0..100) add("battery_min must be 0..100")
        window?.let { w ->
            if (!isHhMm(w.start)) add("conditions window start must be HH:MM")
            if (!isHhMm(w.end)) add("conditions window end must be HH:MM")
            if (w.days.any { it !in 1..7 }) add("conditions window days must be ISO weekdays 1=Mon..7=Sun")
        }
    }
}
