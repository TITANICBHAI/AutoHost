package app.autohost.shell

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.autohost.capabilities.shell.ShellTransports
import app.autohost.capabilities.shell.TransportStatus
import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import kotlinx.serialization.json.put

/**
 * P9-12: the post-reboot re-arm reminder (plan Gate 9-d — "reboot/re-arm behaviour documented and
 * reminder shown"). ADB-over-TCP — the Android 10 phone's only shell path — resets at every boot
 * (adbd leaves tcpip mode), so the user must re-run `adb tcpip 5555` from the PC. After a boot,
 * when the shell tier had been live in the previous session and no transport is up now, this posts
 * one reminder notification whose tap opens the in-app shell setup (the OPEN_SHELL_SETUP route).
 *
 * The "had been live" memory is the `shell.was_live` app preference the Setup screen and the
 * transports-status refresh keep updated — a user who never paired is never nagged.
 */
class RearmReminder(private val context: Context, private val transports: ShellTransports) {

    /** Called from BootReceiver (and app-start reconcile) with the boot reason. */
    fun onBoot(why: String) {
        if (why != "boot" && why != "package_replaced" && why != "app_start") return
        if (!wasLive()) return
        val anyLive = transports.all().any { it.status() is TransportStatus.Live }
        if (anyLive) {
            AhLog.i(TAG, "shell tier already up after boot", AhJson.meta { put("why", why) })
            rememberLive(true)
            return
        }
        postReminder()
    }

    /** Refreshed from the shell transports status reader: remembers whether the tier ever ran. */
    fun rememberLive(live: Boolean) {
        prefs().edit().putBoolean(PREF_WAS_LIVE, live).apply()
    }

    private fun wasLive(): Boolean = prefs().getBoolean(PREF_WAS_LIVE, false)

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun postReminder() {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                if (manager.getNotificationChannel(CHANNEL_REMINDERS) == null) {
                    manager.createNotificationChannel(
                        android.app.NotificationChannel(
                            CHANNEL_REMINDERS, "Shell tier reminders", NotificationManager.IMPORTANCE_DEFAULT,
                        ),
                    )
                }
            }
            val openSetup = PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                Intent().setClassName(context.packageName, LAUNCHER_ACTIVITY), // the nav deep-links to Setup
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = android.app.Notification.Builder(context, CHANNEL_REMINDERS)
                .setSmallIcon(context.resources.getIdentifier("ic_autohost", "drawable", context.packageName))
                .setContentTitle("Shell tier needs re-arming")
                .setContentText("ADB-over-TCP resets at reboot — run \"adb tcpip 5555\" on the PC, then check Setup.")
                .setContentIntent(openSetup)
                .setAutoCancel(true)
            manager.notify(NOTIFICATION_ID, notification.build())
            AhLog.i(TAG, "re-arm reminder posted", AhJson.meta {})
        }.onFailure { e ->
            AhLog.w(TAG, "re-arm reminder failed", AhJson.meta { put("detail", e.message ?: "?") })
        }
    }

    companion object {
        private const val TAG = "shell-rearm"
        private const val PREFS = "shell_tier"
        private const val PREF_WAS_LIVE = "shell.was_live"
        private const val CHANNEL_REMINDERS = "shell_reminders"
        private const val NOTIFICATION_ID = 4711
        private const val REQUEST_CODE = 4712

        /** The launcher activity (Setup hosts the shell section; the user lands on the app UI). */
        const val LAUNCHER_ACTIVITY = "app.autohost.MainActivity"
    }
}
