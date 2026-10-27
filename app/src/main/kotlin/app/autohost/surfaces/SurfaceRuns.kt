package app.autohost.surfaces

import androidx.glance.appwidget.updateAll
import app.autohost.AppGraph
import app.autohost.core.common.AhLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.launch

/**
 * P7-06..10: the one path every system surface uses to start a run (tiles, shortcuts, widget,
 * bubble, external intent). All of them produce the same thing a trigger produces (plan §11.1):
 * an enqueued run with `surface`/`external` trigger metadata and a small payload naming the entry
 * point, so history and Dev API show *where* a run came from.
 */
class SurfaceRuns(private val graph: AppGraph) {

    /**
     * Starts [taskId] (optionally for one [profileId], optionally as a [stage] run). Returns the
     * runId, or null when the task is unknown/not runnable — logged honestly, never thrown.
     */
    suspend fun start(
        taskId: String,
        profileId: String? = null,
        stage: String? = null,
        triggerType: String = TRIGGER_SURFACE,
        source: String,
    ): String? {
        val payload: JsonObject = buildJsonObject {
            put("source", source)
            if (!stage.isNullOrEmpty()) put("stage", stage)
        }
        val runId = graph.startRun(taskId, profileId, triggerType, payload)
        if (runId == null) {
            AhLog.w(TAG, "surface run refused", buildJsonObject {
                put("task_id", taskId)
                put("source", source)
            })
            refreshWidgets(graph)
            return null
        }
        AhLog.i(TAG, "surface run started", buildJsonObject {
            put("run_id", runId)
            put("task_id", taskId)
            put("source", source)
            put("trigger", triggerType)
        })
        refreshWidgets(graph)
        return runId
    }

    /** Trigger type for user-built system entries (tile/shortcut/widget/bubble-adjacent actions). */
    companion object {
        private const val TAG = "surfaces"
        const val TRIGGER_SURFACE = "surface"
        const val TRIGGER_EXTERNAL = "external"

        /**
         * P7-09: refresh home widgets after run-state changes (throttled — a widget refresh is
         * only cheap enough when nothing is animating, so failures are ignored).
         */
        fun refreshWidgets(graph: AppGraph) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastWidgetRefreshMs < WIDGET_REFRESH_THROTTLE_MS) return
            lastWidgetRefreshMs = now
            graph.appScope.launch { runCatching { TaskRunsWidget().updateAll(graph.appContext) } }
        }

        @Volatile
        private var lastWidgetRefreshMs = 0L

        private const val WIDGET_REFRESH_THROTTLE_MS = 1_000L
    }
}
