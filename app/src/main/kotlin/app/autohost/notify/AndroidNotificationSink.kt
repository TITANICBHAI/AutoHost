package app.autohost.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.autohost.core.engine.host.NotificationRequest
import app.autohost.core.engine.host.NotificationSink
import app.autohost.receiver.NotificationActionReceiver

/**
 * P7-05: the Android half of `notify.post` — builds the platform notification on the `alerts`
 * channel (docs/ANDROID_MANIFEST.md). Every action becomes a `Notification.Action` whose
 * PendingIntent lands in [NotificationActionReceiver], which routes the tap to the task's
 * @ui.handler through the `:ui` worker (30 s cap). Post failures never throw — the sink returns -1,
 * the provider answers honestly, and the run continues.
 */
class AndroidNotificationSink(private val context: Context) : NotificationSink {

    override fun post(request: NotificationRequest): Int = runCatching {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val existing = manager.getNotificationChannel(CHANNEL_ALERTS)
            if (existing == null) {
                manager.createNotificationChannel(
                    android.app.NotificationChannel(
                        CHANNEL_ALERTS, "Task alerts", NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
        }
        val builder = android.app.Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(context.resources.getIdentifier("ic_autohost", "drawable", context.packageName))
            .setContentTitle(request.title)
            .setContentText(request.text)
            .setAutoCancel(true)
        request.progress?.let { progress ->
            builder.setProgress(100, (progress * 100).toInt(), false)
        }
        for ((index, action) in request.actions.withIndex()) {
            val intent = Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_RUN_HANDLER)
                .putExtra(NotificationActionReceiver.EXTRA_TASK_ID, request.taskId)
                .putExtra(NotificationActionReceiver.EXTRA_HANDLER, action.handler)
                .putExtra(NotificationActionReceiver.EXTRA_ARGS, action.args.toString())
            val pending = PendingIntent.getBroadcast(
                context,
                (request.taskId + action.handler + index).hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(
                android.app.Notification.Action.Builder(
                    null,
                    action.label,
                    pending,
                ).build(),
            )
        }
        val id = request.taskId.hashCode() + NOTIFICATION_SALT
        manager.notify(id, builder.build())
        id
    }.getOrDefault(-1)

    companion object {
        const val CHANNEL_ALERTS = "alerts"
        const val NOTIFICATION_SALT = 977
    }
}
