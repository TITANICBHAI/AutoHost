package app.autohost.capabilities.projection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import app.autohost.core.common.ApiGate

/**
 * Foreground service of type `mediaProjection` that owns the MediaProjection (plan Phase 3). Android 14+
 * requires `startForeground` with the projection type *before* `createVirtualDisplay`; the service starts
 * with the consent result and stops when the projection ends.
 */
class ProjectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ProjectionHolder.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startAsForeground()
                val pending = ProjectionHolder.consumePending()
                if (pending == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val (code, data) = pending
                val manager = getSystemService(MediaProjectionManager::class.java)
                val projection = try {
                    manager.getMediaProjection(code, data)
                } catch (e: RuntimeException) {
                    ProjectionHolder.stop()
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (projection == null) {
                    ProjectionHolder.stop()
                    stopSelf()
                    return START_NOT_STICKY
                }
                val metrics = resources.displayMetrics
                ProjectionHolder.start(projection, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = notification()
        if (ApiGate.sdkInt >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(): Notification {
        val name = getString(app.autohost.capabilities.projection.R.string.projection_channel)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannelGroup(NotificationChannelGroup("runs", "Runs"))
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply { group = "runs" },
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(app.autohost.capabilities.projection.R.string.projection_title))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "app.autohost.projection.STOP"
        const val CHANNEL_ID = "autohost_projection"
        const val NOTIFICATION_ID = 41

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ProjectionService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ProjectionService::class.java).setAction(ACTION_STOP))
        }
    }
}
