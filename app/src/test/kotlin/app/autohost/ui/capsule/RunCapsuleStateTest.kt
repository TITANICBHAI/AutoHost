package app.autohost.ui.capsule

import app.autohost.core.common.FakeClock
import app.autohost.core.designsystem.theme.Tokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P14-02 / G14-a: every capsule state reachable through the holder; **Stop visible in every
 * running state**; "Stopping…" immediate; Done auto-hides; Error stays until dismissed;
 * touchability is dynamic (a visible capsule claims only its own bounds).
 */
class RunCapsuleStateTest {

    private val clock = FakeClock(startMs = 1_000_000L)

    private fun state() = RunCapsuleState(clock)

    @Test
    fun `given no run when inspected then hidden with no label and no touch claim`() {
        val s = state()
        assertTrue(s.mode is RunCapsuleState.Mode.Hidden)
        assertNull(s.label)
        assertFalse(s.claimsPoint(10f, 10f, RunCapsuleState.Rect(0f, 0f, 100f, 100f)))
    }

    @Test
    fun `given a running run when started then Stop is visible and the task name is the label`() {
        val s = state()
        s.start("r1", "Press button")
        assertTrue(s.mode is RunCapsuleState.Mode.Running)
        assertTrue(s.stopVisible)
        assertEquals("Press button", s.label)
        assertFalse(s.isStopping)
    }

    @Test
    fun `given a stage update when applied then the label shows the stage`() {
        val s = state()
        s.start("r1", "Press button")
        s.stageUpdate("r1", "pressing")
        assertEquals("pressing", s.label)
    }

    @Test
    fun `given the user taps Stop when applied then Stopping appears immediately`() {
        val s = state()
        s.start("r1", "Press button")
        s.stopRequested()
        assertTrue(s.isStopping)
        assertEquals("Stopping…", s.label)
        assertFalse("Stop must disappear once stopping", s.stopVisible)
    }

    @Test
    fun `given a stop for another run when applied then it is ignored`() {
        val s = state()
        s.start("r1", "Press button")
        s.start("r2", "Other")
        s.stopRequested()
        // only the current run's capsule reflects it; a stale runId cannot flip the mode
        s.finished("r1", succeeded = true)
        assertTrue(s.mode is RunCapsuleState.Mode.Running || s.mode is RunCapsuleState.Mode.Stopping)
    }

    @Test
    fun `given success when finished then Done and it auto-hides after the token delay`() {
        val s = state()
        s.start("r1", "Press button")
        s.finished("r1", succeeded = true)
        assertTrue(s.mode is RunCapsuleState.Mode.Done)
        clock.advance(Tokens.CAPSULE_DONE_AUTO_HIDE_MS - 1)
        s.tick()
        assertTrue("Done must still be visible one ms before the deadline", s.mode is RunCapsuleState.Mode.Done)
        clock.advance(1)
        s.tick()
        assertTrue("Done must auto-hide at the deadline", s.mode is RunCapsuleState.Mode.Hidden)
    }

    @Test
    fun `given a failure when finished then Error stays until dismissed`() {
        val s = state()
        s.start("r1", "Press button")
        s.finished("r1", succeeded = false, reason = "EXCEPTION", message = "boom")
        assertTrue(s.mode is RunCapsuleState.Mode.Error)
        assertEquals("boom", s.label)
        clock.advance(Tokens.CAPSULE_DONE_AUTO_HIDE_MS * 10)
        s.tick()
        assertTrue("Error must NOT auto-hide", s.mode is RunCapsuleState.Mode.Error)
        s.dismissError()
        assertTrue(s.mode is RunCapsuleState.Mode.Hidden)
    }

    @Test
    fun `given agent turns when reported then the count rides the running mode`() {
        val s = state()
        s.start("r1", "Ask run")
        s.agentTurns("r1", 3)
        assertEquals(3, (s.mode as RunCapsuleState.Mode.Running).agentTurns)
    }

    @Test
    fun `given touchability when checked then a visible capsule claims only its own bounds`() {
        val s = state()
        s.start("r1", "Press button")
        val bounds = RunCapsuleState.Rect(0f, 0f, 200f, 64f)
        assertTrue(s.claimsPoint(100f, 32f, bounds))
        assertFalse("a collapsed capsule never swallows taps outside itself", s.claimsPoint(300f, 32f, bounds))
    }
}
