package app.autohost.core.model

/**
 * P4-04: trigger sources (plan §11.9). Phase 4 arms the `time.*` sources through the ScheduleEngine;
 * device/event sources are stored and validated now but only ARM once the EventHub exists (Phase 8) —
 * persisted with `armed = false` and shown honestly in the UI as "waiting for event support".
 */
object TriggerSource {
    val TIMED = setOf(
        "time.delay", "time.absolute", "time.daily", "time.weekly",
    )

    val EVENT_ONLY = setOf(
        "notification.posted", "notification.removed",
        "app.entered", "app.exited",
        "battery.low", "battery.okay", "battery.level",
        "power.connected", "power.disconnected",
        "screen.on", "screen.off", "user.present",
        "network.connected", "network.type_changed", "network.wifi_ssid",
        "sms.received", "toast.shown",
        "external", "task.chain",
    )

    val ALL = TIMED + EVENT_ONLY

    /** Sources whose rule maps onto a ScheduleRule the ScheduleEngine can arm today. */
    fun isSchedulable(source: String): Boolean = source in TIMED

    /** Params each source requires (beyond the schema's per-source `required`); kept in one place. */
    fun requiredParams(source: String): Set<String> = when (source) {
        "time.delay" -> setOf("seconds")
        "time.absolute" -> setOf("at")
        "time.daily" -> setOf("time")
        "time.weekly" -> setOf("days", "time")
        "app.entered", "app.exited" -> setOf("package")
        "battery.level" -> setOf("op", "percent")
        "task.chain" -> setOf("task")
        "external" -> setOf("name")
        else -> emptySet()
    }
}
