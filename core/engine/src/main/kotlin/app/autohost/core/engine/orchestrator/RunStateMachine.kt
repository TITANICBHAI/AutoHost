package app.autohost.core.engine.orchestrator

import app.autohost.core.common.AhResult
import app.autohost.core.model.ErrorCode
import app.autohost.core.model.RunStatus

/**
 * The only authority for run state changes. Illegal transitions are refused (fail closed) rather than coerced —
 * a malformed finished event must never mask a hung worker as SUCCEEDED.
 */
object RunStateMachine {

    private val transitions: Map<RunStatus, Set<RunStatus>> = mapOf(
        RunStatus.QUEUED to setOf(RunStatus.STARTING, RunStatus.CANCELLED, RunStatus.SKIPPED),
        RunStatus.STARTING to setOf(RunStatus.RUNNING, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.INTERRUPTED),
        RunStatus.RUNNING to setOf(
            RunStatus.RUNNING, RunStatus.PAUSED, RunStatus.WAITING_USER,
            RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.INTERRUPTED,
        ),
        RunStatus.PAUSED to setOf(RunStatus.RUNNING, RunStatus.CANCELLED, RunStatus.INTERRUPTED),
        RunStatus.WAITING_USER to setOf(
            // P5-06: "Resume" = a new attempt of the same run id (plan §12.3) → back to STARTING
            RunStatus.STARTING, RunStatus.RUNNING, RunStatus.SUCCEEDED, RunStatus.FAILED,
            RunStatus.CANCELLED, RunStatus.INTERRUPTED,
        ),
        // P5-05: a manually/auto-resumed INTERRUPTED run re-opens as a new attempt (plan §12.3)
        RunStatus.INTERRUPTED to setOf(RunStatus.STARTING),
        // terminal states transition to nothing
    )

    fun canTransition(from: RunStatus, to: RunStatus): Boolean = to in transitions[from].orEmpty()

    /** Same-state "transitions" are accepted as no-ops (duplicate heartbeats must not fail the run). */
    fun transition(from: RunStatus, to: RunStatus): AhResult<RunStatus> = when {
        from == to -> AhResult.ok(from)
        canTransition(from, to) -> AhResult.ok(to)
        else -> AhResult.err(
            ErrorCode.INTERNAL,
            "Illegal run state transition ${from.name} -> ${to.name}",
            reason = "ILLEGAL_STATE_TRANSITION",
        )
    }
}
