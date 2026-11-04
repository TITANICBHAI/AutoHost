package app.autohost.core.engine.orchestrator

import app.autohost.core.model.RunStatus
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X-13/X-16 (ADR-098 (6), AGENT_RUNTIME §9): WAITING_USER semantics pinned where the state
 * machine lives — WAITING_USER is NOT terminal (a waiting run keeps its credential), so nothing
 * may revoke the run token while a run waits for its user.
 */
class RunStateMachineX13Test {

    @Test
    fun `given WAITING_USER when the user answers then the run continues on the same state machine`() {
        assertTrue(RunStateMachine.canTransition(RunStatus.RUNNING, RunStatus.WAITING_USER))
        assertTrue(RunStateMachine.canTransition(RunStatus.WAITING_USER, RunStatus.RUNNING))
        assertTrue(RunStateMachine.canTransition(RunStatus.WAITING_USER, RunStatus.STARTING))
        // terminal states have no outgoing transitions; WAITING_USER must not be among them
        assertTrue(RunStateMachine.canTransition(RunStatus.WAITING_USER, RunStatus.FAILED))
    }
}
