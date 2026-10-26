package app.autohost.fixture

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.core.app.NotificationCompat

/** NotificationScreen (docs/FIXTURE_APP.md): posts a notification on demand — notification trigger tests. */
class NotificationScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification)
        Screens.bind(this, "notification")

        findViewById<Button>(R.id.fx_btn_notify).setOnClickListener { post() }
    }

    private fun post() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Fixture notifications", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
            return
        }
        show(manager)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            show(getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
        }
    }

    private fun show(manager: NotificationManager) {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Fixture notification")
            .setContentText("posted at ${System.currentTimeMillis()}")
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        FixtureState.lastEvent = "notification:posted"
        Screens.refresh(this)
    }

    companion object {
        const val CHANNEL = "fixture_notifications"
        const val NOTIFICATION_ID = 9001
        const val REQUEST_CODE = 78
    }
}
