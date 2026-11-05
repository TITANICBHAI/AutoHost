package app.autohost.ui.capsule

import app.autohost.core.common.Clock

/**
 * P14-08 (UI_SPEC §10): the optional edge-glow / action visualizer. **Default OFF** — and it
 * stays off unless the user enables it; the S7 measurements (overlay jank / touch loss) can
 * retire the whole feature later without a settings migration (an off feature renders nothing).
 *
 * Pure state holder so the default and the persistence round-trip are testable without Compose.
 */
class ActionVisualizerState(
    private val clock: Clock,
) {
    /** The user's master switch. Default **false**; nothing renders while false. */
    @Volatile var enabled: Boolean = false
        private set

    /** The last action highlight, or null. Only meaningful while enabled. */
    @Volatile var currentAction: Highlight? = null
        private set

    data class Highlight(val label: String, val startedAtMs: Long)

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) currentAction = null // an off visualizer renders nothing, ever
    }

    /** Shows a highlight for [label]; ignored while disabled. */
    fun showAction(label: String) {
        if (enabled) currentAction = Highlight(label.take(40), clock.nowMs())
    }

    fun clearAction() {
        currentAction = null
    }
}
