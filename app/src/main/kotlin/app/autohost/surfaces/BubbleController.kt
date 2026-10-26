package app.autohost.surfaces

import android.content.Intent
import app.autohost.AppGraph
import app.autohost.MainActivity
import app.autohost.core.engine.surfaces.BubblePolicy
import app.autohost.capabilities.a11y.A11yBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * P7-06: owns the bubble's visibility. Subscribes to the run store and the accessibility-service
 * connection: the overlay attaches when the [BubblePolicy] says show (auto = some active run is
 * UI-acting — the graph tracks that per run; always = any active run; off = never) and detaches
 * when the last eligible run ends or the service disconnects. Status line refreshes on every run
 * update; Stop is the global kill switch (plan §5.2).
 */
class BubbleController(
    private val graph: AppGraph,
    private val prefs: SurfacesPrefs,
    private val scope: CoroutineScope,
) {

    private var overlay: BubbleOverlay? = null

    /** Per-run "acts on the UI" flag (AppGraph.startRun records it; the bubble policy consumes it). */
    private val uiActing = HashMap<String, Boolean>()

    /** The latest status line; the overlay's status lambda reads this so `updateStatus()` is fresh. */
    @Volatile
    private var currentStatusLine: String = ""

    fun start() {
        scope.launch {
            graph.runStore.runs.collect { runs ->
                val policyRuns = runs.map { BubblePolicy.BubbleRun(it.runId, it.active, uiActing[it.runId] ?: false) }
                val shouldShow = A11yBridge.connected && BubblePolicy.decide(prefs.bubbleMode(), policyRuns)
                if (shouldShow) {
                    showOrUpdate(runs.count { it.active }, runs.firstOrNull { it.active })
                } else {
                    hide()
                }
            }
        }
        scope.launch {
            // The overlay dies with the service (its window is the service's); reattach on reconnect.
            A11yBridge.service.collect { service ->
                if (service == null) {
                    overlay = null
                } else {
                    val active = graph.runStore.runs.value.filter { it.active }
                    val policyRuns = active.map { BubblePolicy.BubbleRun(it.runId, true, uiActing[it.runId] ?: false) }
                    if (BubblePolicy.decide(prefs.bubbleMode(), policyRuns)) showOrUpdate(active.size, active.firstOrNull())
                }
            }
        }
    }

    /** AppGraph.startRun calls this so the policy knows which runs act on the UI. */
    fun noteUiActing(runId: String, uiActing: Boolean) {
        uiActing[runId] = uiActing
    }

    fun forget(runId: String) {
        uiActing.remove(runId)
    }

    private fun showOrUpdate(activeCount: Int, first: app.autohost.core.engine.orchestrator.RunRecord?) {
        currentStatusLine = statusLine(activeCount, first)
        val overlay = this.overlay ?: BubbleOverlay(
            statusText = { currentStatusLine },
            onStop = { graph.globalStop("bubble") },
            onOpenApp = {
                val intent = Intent(graph.appContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { graph.appContext.startActivity(intent) }
            },
        )
        this.overlay = overlay
        if (!overlay.showing) {
            overlay.show()
        } else {
            overlay.updateStatus()
        }
    }

    private fun statusLine(activeCount: Int, first: app.autohost.core.engine.orchestrator.RunRecord?): String {
        val run = first ?: return "$activeCount running"
        val stage = run.stage?.let { " · $it" } ?: ""
        val more = if (activeCount > 1) " (+${activeCount - 1})" else ""
        return "${run.taskName}$stage$more"
    }

    private fun hide() {
        overlay?.hide()
    }
}
