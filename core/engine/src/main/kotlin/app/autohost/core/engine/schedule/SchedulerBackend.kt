package app.autohost.core.engine.schedule

/**
 * P4-05: pluggable scheduler backends (plan §11.3). The controller arms exactly ONE alarm for
 * the earliest due fire and re-arms after each fire; precise arming is only used for fires
 * inside [NEAR_WINDOW_MS] — farther fires are covered by the reconcile worker.
 *
 * Backends never throw across this seam: they report via [ArmResult]. Implementations live in
 * their own files (`AlarmBackend`, `WorkBackend`, `JobBackend`) and are Android-bound; tests
 * use fakes.
 */
interface SchedulerBackend {
    val kind: String // "alarm" | "work" | "job"

    /** Arm (or replace) the one-shot fire [fireId] at [atMs]. */
    fun arm(fireId: String, atMs: Long, allowWhileIdle: Boolean): ArmResult

    /** Cancel a previously armed fire (schedule disabled/removed/re-armed elsewhere). */
    fun cancel(fireId: String)

    /** Whether this backend can currently deliver precise timing (exact alarms on API 31+). */
    fun canDeliverExact(): Boolean
}

sealed interface ArmResult {
    data object Armed : ArmResult

    /** Backend refused (exact alarms denied, background start restricted): fall back + badge. */
    data class Refused(val reason: String) : ArmResult
}

/** Which backend should arm right now (plan §11.3: `auto` prefers Alarm when exact alarms exist). */
object ExactAlarmPolicy {

    const val NEAR_WINDOW_MS: Long = 48 * 60 * 60 * 1000L

    /**
     * `backend` setting: `auto` | `alarm` | `work` | `job`. `auto` picks Alarm when the device
     * allows exact alarms (or the fire is far enough that the fallback matters less), else Work.
     */
    fun choose(setting: String, exactAlarmsAllowed: Boolean, fireAtMs: Long, nowMs: Long): String = when (setting) {
        "alarm" -> "alarm"
        "work" -> "work"
        "job" -> "job"
        else -> if (exactAlarmsAllowed || fireAtMs - nowMs > NEAR_WINDOW_MS) "alarm" else "work"
    }

    /** True when the fire is close enough to deserve precise arming (§11.3 near-window rule). */
    fun needsPreciseArm(fireAtMs: Long, nowMs: Long): Boolean = fireAtMs - nowMs <= NEAR_WINDOW_MS
}
