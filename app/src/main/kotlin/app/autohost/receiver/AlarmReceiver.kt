package app.autohost.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.autohost.AutoHostApp
import app.autohost.core.common.AhLog
import app.autohost.core.engine.schedule.AlarmBackend

/**
 * P4-05: delivers one armed schedule fire (plan §11.3). Starts the run foreground service path
 * implicitly through the controller (a fired exact alarm is a permitted background start on
 * API 31+; on 29–30 background starts from receivers are allowed — plan §3.5/S3 verifies).
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmBackend.ACTION_FIRE) return
        val fireId = intent.getStringExtra(AlarmBackend.EXTRA_FIRE_ID) ?: return
        val graph = (context.applicationContext as? AutoHostApp)?.graph ?: return
        AhLog.i("scheduler", "alarm fired", app.autohost.core.common.AhJson.meta {
            put("fire_id", fireId)
        })
        // P8-03: `trig-` fireIds belong to time-source trigger rules; everything else is a schedule.
        if (fireId.startsWith(app.autohost.schedule.TriggerController.FIRE_PREFIX)) {
            graph.triggerController.onTimeFire(fireId)
        } else {
            graph.scheduleController.onFire(fireId)
        }
    }
}
