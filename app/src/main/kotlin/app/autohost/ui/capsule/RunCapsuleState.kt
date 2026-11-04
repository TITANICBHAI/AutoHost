package app.autohost.ui.capsule

import app.autohost.core.common.Clock
import app.autohost.core.designsystem.theme.Tokens

/**
 * P14-02 (S28, ADR-094): the one state holder behind the run capsule — used both in-app and as
 * the overlay. Pure and synchronous so `TokensTest`-style JVM tests pin every rule
 * (`docs/GATES.md` G14-a): **Stop is visible in every running state; "Stopping…" appears
 * immediately** when the user taps Stop (never waits for the worker to acknowledge); Done
 * auto-hides; Error stays until dismissed; touchability is dynamic (a collapsed capsule never
 * claims taps outside its own bounds). The run notification with Stop still exists — this
 * replaces nothing (AGENTS rule 7).
 */
class RunCapsuleState(
    private val clock: Clock,
) {
    private fun nowMs(): Long = clock.nowMs()

    sealed interface Mode {
        /** Nothing to show (no run, or Done auto-hidden). */
        data object Hidden : Mode

        /** A run is active. `stage` is the current stage label or null. Stop is always visible. */
        data class Running(
            val runId: String,
            val taskName: String,
            val stage: String?,
            val agentTurns: Int? = null,
        ) : Mode

        /** The user tapped Stop — shown IMMEDIATELY, before the worker acknowledges. */
        data class Stopping(val runId: String, val taskName: String) : Mode

        /** Terminal success; auto-hides after [Tokens.CAPSULE_DONE_AUTO_HIDE_MS]. */
        data class Done(val runId: String, val taskName: String, val shownAtMs: Long) : Mode

        /** Terminal failure/interrupt; stays until dismissed. */
        data class Error(val runId: String, val taskName: String, val reason: String?, val message: String) : Mode
    }

    @Volatile var mode: Mode = Mode.Hidden
        private set

    /** Whether the capsule currently claims touches at a point (dynamic touchability). */
    fun claimsPoint(x: Float, y: Float, bounds: Rect): Boolean = when (val m = mode) {
        is Mode.Hidden -> false
        else -> bounds.contains(x, y) // inside its own bounds only, in every visible mode
    }

    fun start(runId: String, taskName: String) {
        mode = Mode.Running(runId, taskName, stage = null)
    }

    fun stageUpdate(runId: String, stage: String?) {
        val m = mode
        if (m is Mode.Running && m.runId == runId) mode = m.copy(stage = stage)
    }

    /** Agent runs report their turn count into the capsule (Phase 13 event feed). */
    fun agentTurns(runId: String, turns: Int) {
        val m = mode
        if (m is Mode.Running && m.runId == runId) mode = m.copy(agentTurns = turns)
    }

    /** The user tapped Stop: "Stopping…" appears immediately; Stop disappears (already stopping). */
    fun stopRequested() {
        val m = mode
        if (m is Mode.Running) mode = Mode.Stopping(m.runId, m.taskName)
    }

    fun finished(runId: String, succeeded: Boolean, reason: String? = null, message: String = "") {
        val m = mode
        val name = when (m) {
            is Mode.Running -> if (m.runId == runId) m.taskName else return
            is Mode.Stopping -> if (m.runId == runId) m.taskName else return
            else -> return
        }
        mode = if (succeeded) {
            Mode.Done(runId, name, shownAtMs = nowMs())
        } else {
            Mode.Error(runId, name, reason = reason, message = message.ifBlank { reason ?: "failed" })
        }
    }

    /** Drives the Done auto-hide; called from the composition's clock or the overlay loop. */
    fun tick() {
        val m = mode
        if (m is Mode.Done && nowMs() - m.shownAtMs >= Tokens.CAPSULE_DONE_AUTO_HIDE_MS) {
            mode = Mode.Hidden
        }
    }

    fun dismissError() {
        if (mode is Mode.Error) mode = Mode.Hidden
    }

    /** Whether the capsule should render a Stop control right now (every running state). */
    val stopVisible: Boolean
        get() = mode is Mode.Running

    /** Whether the capsule is mid-stop ("Stopping…" shown immediately). */
    val isStopping: Boolean
        get() = mode is Mode.Stopping

    /** The visible label, per mode. */
    val label: String?
        get() = when (val m = mode) {
            is Mode.Running -> m.stage ?: m.taskName
            is Mode.Stopping -> "Stopping…"
            is Mode.Done -> "Done"
            is Mode.Error -> m.message
            Mode.Hidden -> null
        }

    /** Simple rectangle for the touchability contract (px space, Compose-agnostic). */
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
    }
}
