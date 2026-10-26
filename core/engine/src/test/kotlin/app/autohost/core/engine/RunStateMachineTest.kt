package app.autohost.core.engine

import app.autohost.core.engine.orchestrator.RunStateMachine
import app.autohost.core.model.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunStateMachineTest {
    @Test
    fun `given queued run when starting then legal`() {
        assertTrue(RunStateMachine.canTransition(RunStatus.QUEUED, RunStatus.STARTING))
        assertEquals(RunStatus.STARTING, RunStateMachine.transition(RunStatus.QUEUED, RunStatus.STARTING).getOrNull())
    }

    @Test
    fun `given starting run when heartbeat arrives then running`() {
        assertTrue(RunStateMachine.canTransition(RunStatus.STARTING, RunStatus.RUNNING))
    }

    @Test
    fun `given succeeded run when anything then illegal`() {
        for (status in RunStatus.entries) {
            if (status == RunStatus.SUCCEEDED) continue
            assertFalse("SUCCEEDED -> $status must be illegal", RunStateMachine.canTransition(RunStatus.SUCCEEDED, status))
        }
    }

    @Test
    fun `given same state when transitioned then no-op success`() {
        assertEquals(RunStatus.RUNNING, RunStateMachine.transition(RunStatus.RUNNING, RunStatus.RUNNING).getOrNull())
    }

    @Test
    fun `given queued run when finished directly then illegal`() {
        assertFalse(RunStateMachine.canTransition(RunStatus.QUEUED, RunStatus.SUCCEEDED))
        assertTrue(RunStateMachine.transition(RunStatus.QUEUED, RunStatus.SUCCEEDED).errorOrNull() != null)
    }

    @Test
    fun `given every non terminal state when cancelled then legal`() {
        for (status in listOf(RunStatus.QUEUED, RunStatus.STARTING, RunStatus.RUNNING, RunStatus.PAUSED, RunStatus.WAITING_USER)) {
            assertTrue("$status -> CANCELLED", RunStateMachine.canTransition(status, RunStatus.CANCELLED))
        }
    }

    // ---- P5-05/P5-06: resume = a new attempt of the same run id (plan §12.3) ----

    @Test
    fun `WAITING_USER re-opens as STARTING for a resumed attempt`() {
        assertTrue(RunStateMachine.canTransition(RunStatus.WAITING_USER, RunStatus.STARTING))
        assertEquals(RunStatus.STARTING, RunStateMachine.transition(RunStatus.WAITING_USER, RunStatus.STARTING).getOrNull())
    }

    @Test
    fun `INTERRUPTED re-opens as STARTING for an auto-resumed attempt`() {
        assertTrue(RunStateMachine.canTransition(RunStatus.INTERRUPTED, RunStatus.STARTING))
        assertEquals(RunStatus.STARTING, RunStateMachine.transition(RunStatus.INTERRUPTED, RunStatus.STARTING).getOrNull())
    }

    @Test
    fun `FAILED and terminal completions stay closed`() {
        for (status in listOf(RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.SKIPPED, RunStatus.SUCCEEDED)) {
            assertFalse("$status must not resume", RunStateMachine.canTransition(status, RunStatus.STARTING))
        }
    }
}
