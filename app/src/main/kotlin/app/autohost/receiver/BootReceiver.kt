package app.autohost.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.autohost.AutoHostApp
import app.autohost.core.common.AhLog
import kotlinx.coroutines.launch

/**
 * P4-07: re-arm on boot, app update, time-zone change and clock change; reconcile on app start
 * (plan §11.3). Also runs the P4-14 boot self-heal when WRITE_SECURE_SETTINGS is granted.
 * Manifest: docs/ANDROID_MANIFEST.md §4 (BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIMEZONE_CHANGED,
 * TIME_SET, SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val graph = (context.applicationContext as? AutoHostApp)?.graph ?: return
        val why = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "package_replaced"
            Intent.ACTION_TIMEZONE_CHANGED -> "timezone_changed"
            Intent.ACTION_TIME_CHANGED -> "time_changed"
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> "exact_alarm_permission"
            else -> return
        }
        AhLog.i("scheduler", "re-arm requested", app.autohost.core.common.AhJson.meta { put("why", why) })
        // P4-14 first (accessibility back before anything needs it), then reconcile + re-arm.
        // Both hand off into the controller scope; the receiver returns promptly.
        graph.selfHeal.onBoot(why)
        graph.scheduleController.reconcile(why)
        // P8-03: trigger rules re-arm on the same boot/time-change surface (plan §11.3).
        graph.triggerController.reconcile(why)
        // P9-12: the shell-tier re-arm reminder (ADB-over-TCP resets at boot on the Android 10
        // phone) — transport probes are bounded but not instant, so this runs in the app scope.
        graph.appScope.launch { graph.shellRearmReminder.onBoot(why) }
    }
}
