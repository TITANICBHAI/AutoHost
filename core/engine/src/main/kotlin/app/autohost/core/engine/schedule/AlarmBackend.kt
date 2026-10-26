package app.autohost.core.engine.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * P4-05: exact-alarm backend (`setExactAndAllowWhileIdle`, plan §11.3). Fires [AlarmReceiver]
 * (`:app`), which starts the foreground service and enqueues the run. Exact-alarm permission is
 * checked per API (§3.5): implicit on 29–30, `SCHEDULE_EXACT_ALARM` on 31–32, `USE_EXACT_ALARM`/
 * user grant on 33+; refusal degrades to the Work backend with the "timing may be late" badge.
 */
class AlarmBackend(private val context: Context) : SchedulerBackend {

    override val kind: String = "alarm"

    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)

    override fun arm(fireId: String, atMs: Long, allowWhileIdle: Boolean): ArmResult {
        val manager = alarmManager ?: return ArmResult.Refused("no_alarm_manager")
        if (!canDeliverExact()) return ArmResult.Refused("exact_alarms_denied")
        val pi = pendingIntent(fireId)
        runCatching {
            manager.cancel(pi)
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }.onFailure { return ArmResult.Refused("arm_failed:${it.message ?: "unknown"}") }
        return ArmResult.Armed
    }

    override fun cancel(fireId: String) {
        runCatching { alarmManager?.cancel(pendingIntent(fireId)) }
    }

    override fun canDeliverExact(): Boolean {
        val manager = alarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= 31) {
            manager.canScheduleExactAlarms()
        } else {
            true // API 29–30: exact alarms need no special permission (plan §3.5)
        }
    }

    private fun pendingIntent(fireId: String): PendingIntent {
        val intent = Intent(ACTION_FIRE)
            .setPackage(context.packageName)
            .putExtra(EXTRA_FIRE_ID, fireId)
        return PendingIntent.getBroadcast(
            context,
            fireId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_FIRE = "app.autohost.action.SCHEDULE_FIRE"
        const val EXTRA_FIRE_ID = "fire_id"
    }
}
