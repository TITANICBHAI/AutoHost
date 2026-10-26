package app.autohost.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.autohost.AutoHostApp
import app.autohost.core.common.AhLog
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Notification actions (plan §5.2/§10.3). Stop is the global kill switch (wave 1); P7-05 adds
 * ACTION_RUN_HANDLER: a notification action tap routes to the task's @ui.handler through the
 * `:ui` worker (30 s cap). Handler results are logged — the notification itself is the receipt —
 * and a failed call never crashes the host (goAsync keeps the process alive for the 30 s window).
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_ALL -> {
                val graph = AutoHostApp.of(context).graph
                graph.orchestrator.cancelAll()
            }
            ACTION_RUN_HANDLER -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
                val handler = intent.getStringExtra(EXTRA_HANDLER) ?: return
                val argsJson = intent.getStringExtra(EXTRA_ARGS) ?: "{}"
                val args = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(argsJson) as? JsonObject
                }.getOrNull() ?: JsonObject(emptyMap())
                val pendingResult = goAsync()
                val graph = AutoHostApp.of(context).graph
                graph.appScope.launch {
                    try {
                        val outcome = graph.uiHandlers.call(taskId, handler, args)
                        if (!outcome.ok) {
                            AhLog.w(
                                TAG,
                                "handler call failed: task=$taskId handler=$handler " +
                                    "reason=${outcome.reason} message=${outcome.message}",
                            )
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "notify-action"
        const val ACTION_STOP_ALL = "app.autohost.action.STOP_ALL"
        const val ACTION_RUN_HANDLER = "app.autohost.action.RUN_HANDLER"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_HANDLER = "handler"
        const val EXTRA_ARGS = "args"
    }
}
