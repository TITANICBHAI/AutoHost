package app.autohost.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.autohost.AutoHostApp
import app.autohost.MainActivity
import app.autohost.R
import app.autohost.core.common.ApiGate
import app.autohost.receiver.NotificationActionReceiver
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Ongoing notification for any active run, with Stop (plan §5.2, AGENTS.md §3 rule 7: visible and stoppable).
 * `startForeground` is version-guarded: the specialUse type is passed on API 34+ only.
 */
class RunForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (ApiGate.atLeast(34)) {
            StartForegroundApi34.startForeground(this, NOTIFICATION_ID, notification)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channel = android.app.NotificationChannel(
            CHANNEL_RUNS,
            getString(R.string.channel_runs),
            android.app.NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_runs_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, CHANNEL_RUNS)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.run_notification_title))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.app.Notification.Action.Builder(
                    null,
                    getString(R.string.run_notification_stop),
                    stopIntent,
                ).build(),
            )
            .build()
    }

    companion object {
        private const val CHANNEL_RUNS = "runs"
        private const val NOTIFICATION_ID = 42

        /**
         * Starts the run FGS. P7-06..10: surfaces (tiles/shortcuts/widget/intent) can start runs
         * while the app is backgrounded; on API 31+ such a start may be refused (no exemption for
         * the sender). The run itself already exists at that point — the honest behaviour is to
         * log and continue without the foreground notification, never to crash the collector.
         */
        fun start(context: Context) {
            val intent = Intent(context, RunForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                app.autohost.core.common.AhLog.w(
                    "run-fgs",
                    "FGS start refused (background start); run continues without it",
                    buildJsonObject {
                        put("error", e.message ?: e.javaClass.simpleName)
                    },
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RunForegroundService::class.java))
        }
    }
}

/** Tiny API-34 seam so the SDK_INT branch lives in exactly one annotated place (AGENTS.md §3 rule 4). */
private object StartForegroundApi34 {
    @androidx.annotation.RequiresApi(34)
    fun startForeground(service: Service, id: Int, notification: Notification) {
        service.startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }
}
