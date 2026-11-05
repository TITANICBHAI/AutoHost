package app.autohost.ui.capsule

import app.autohost.core.common.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P14-08: the visualizer is default-OFF and renders nothing while disabled. */
class ActionVisualizerStateTest {

    @Test
    fun `given a fresh state when inspected then disabled with no highlight`() {
        val s = ActionVisualizerState(FakeClock())
        assertFalse(s.enabled)
        assertNull(s.currentAction)
    }

    @Test
    fun `given a disabled visualizer when an action is shown then it is ignored`() {
        val s = ActionVisualizerState(FakeClock())
        s.showAction("click Continue")
        assertNull("an OFF visualizer renders nothing, ever", s.currentAction)
    }

    @Test
    fun `given an enabled visualizer when an action is shown then the truncated label is held`() {
        val s = ActionVisualizerState(FakeClock())
        s.setEnabled(true)
        s.showAction("L".repeat(80))
        assertEquals(40, s.currentAction!!.label.length)
    }

    @Test
    fun `given enabled then disabled when cleared then no highlight remains`() {
        val s = ActionVisualizerState(FakeClock())
        s.setEnabled(true)
        s.showAction("click")
        s.setEnabled(false)
        assertNull(s.currentAction)
    }
}
