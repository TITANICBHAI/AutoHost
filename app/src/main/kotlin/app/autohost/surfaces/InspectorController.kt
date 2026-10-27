package app.autohost.surfaces

import android.content.Context
import app.autohost.capabilities.a11y.A11yBridge
import app.autohost.capabilities.a11y.SimpleA11yReader
import app.autohost.core.common.AhLog
import app.autohost.core.common.Clock
import app.autohost.core.common.ForegroundTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P7-11: owns an inspector session (plan §5.6 "on-device overlay draws node bounds, ids and
 * indices and copies a selector on tap"). The scope is the *currently foreground* package —
 * tracked live ([ForegroundTracker]), so the overlay follows the user between apps but never
 * draws on AutoHost itself. Refresh rides the a11y window-change stream (throttled) plus a slow
 * poll, because some trees change without emitting window events. Starting requires the a11y
 * service; the honest outcome is logged and reported.
 */
class InspectorController(
    private val context: Context,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {

    @Volatile
    private var overlay: InspectorOverlay? = null

    private var refreshJob: Job? = null

    @Volatile
    var inspecting: Boolean = false
        private set

    /** Current inspected package (foreground, excluding AutoHost); empty while off. */
    @Volatile
    var inspectedPackage: String = ""
        private set

    private val reader = SimpleA11yReader({ A11yBridge.service.value }, clock)

    /** The nodes of the inspected package (bounded by the reader's own caps), in screen coords. */
    fun nodes(): List<app.autohost.core.model.UiNode> {
        if (!inspecting) return emptyList()
        val pkg = inspectedPackage
        if (pkg.isEmpty()) return emptyList()
        return runCatching {
            reader.read(setOf(pkg)).nodes
        }.getOrDefault(emptyList())
    }

    fun start(): Boolean {
        if (inspecting) return true
        if (!A11yBridge.connected) {
            AhLog.w(TAG, "inspector refused: accessibility off", buildJsonObject {})
            return false
        }
        inspectedPackage = currentPkg()
        val overlay = InspectorOverlay(
            nodesProvider = { nodes() },
            onExit = { stop() },
        )
        this.overlay = overlay
        overlay.show()
        if (!overlay.showing) {
            this.overlay = null
            AhLog.w(TAG, "inspector overlay denied", buildJsonObject {})
            return false
        }
        inspecting = true
        overlay.refresh()
        startRefreshLoop()
        AhLog.i(TAG, "inspector started", buildJsonObject { put("package", inspectedPackage) })
        return true
    }

    fun stop() {
        if (!inspecting && overlay == null) return
        inspecting = false
        refreshJob?.cancel()
        refreshJob = null
        overlay?.hide()
        overlay = null
        AhLog.i(TAG, "inspector stopped", buildJsonObject { put("package", inspectedPackage) })
        inspectedPackage = ""
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            var lastChangeSeen = 0L
            while (isActive && inspecting) {
                // Follow the user across apps, but never inspect AutoHost itself.
                val fg = currentPkg()
                if (fg != inspectedPackage && !fg.isEmpty()) {
                    inspectedPackage = fg
                    overlay?.updateInfo("scope: $fg — tap a node")
                }
                // Refresh on window changes (throttled) or every second as a fallback.
                val changed = A11yBridge.lastWindowChangeMs != lastChangeSeen
                if (changed) {
                    lastChangeSeen = A11yBridge.lastWindowChangeMs
                    overlay?.refresh()
                } else if ((clock.monotonicMs() / 1_000) % 2 == 0L) {
                    overlay?.refresh()
                }
                delay(REFRESH_MS)
            }
        }
    }

    private fun currentPkg(): String {
        val fg = ForegroundTracker.state.value?.pkg.orEmpty()
        return if (fg == context.packageName) "" else fg
    }

    private companion object {
        const val TAG = "inspector"
        const val REFRESH_MS = 300L
    }
}
