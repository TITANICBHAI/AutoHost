package app.autohost.core.engine.surfaces

/**
 * P7-06: when the floating control bubble is visible (plan §10.3 — "Status + Stop visible over
 * other apps; default on whenever a run uses `device.ui.act`"). Pure decision, unit-tested.
 *
 * Modes (user-settable in Surfaces settings):
 * - `auto` (default): show while at least one *active* run is UI-acting (the "visible and
 *   stoppable" rule applies to runs that touch the screen; other runs have the notification).
 * - `always`: show while any run is active.
 * - `off`: never show (the notification + volume-key stop remain).
 */
object BubblePolicy {

    const val MODE_AUTO = "auto"
    const val MODE_ALWAYS = "always"
    const val MODE_OFF = "off"
    val MODES = listOf(MODE_AUTO, MODE_ALWAYS, MODE_OFF)

    data class BubbleRun(val runId: String, val active: Boolean, val uiActing: Boolean)

    fun decide(mode: String, runs: List<BubbleRun>): Boolean = when (mode) {
        MODE_OFF -> false
        MODE_ALWAYS -> runs.any { it.active }
        else -> runs.any { it.active && it.uiActing }
    }
}
