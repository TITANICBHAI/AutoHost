package app.autohost.ui2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskReadinessTest {

    private val nothingOn = LiveAccess()

    @Test fun `a task that needs the screen is blocked until accessibility is on`() {
        val off = TaskReadiness.of(listOf("device.ui"), nothingOn)
        assertFalse(off.ready)
        assertEquals(Verdict.NEEDS_SETUP, off.blockers.single().verdict)
        assertTrue(TaskReadiness.of(listOf("device.ui"), nothingOn.copy(accessibilityOn = true)).ready)
    }

    @Test fun `a task that only opens apps and shows notices is never blocked`() {
        val result = TaskReadiness.of(listOf("device.app", "notify"), nothingOn)
        assertTrue(result.blockers.toString(), result.ready)
        assertEquals("", result.line())
    }

    @Test fun `a capability that merely asks on first use does not warn`() {
        val asks = FeatureCatalog.all.first { it.need == Need.ASK_ON_USE }
        assertTrue(TaskReadiness.of(listOf(asks.capabilityId), nothingOn).ready)
    }

    @Test fun `a switch that is off blocks and the same switch on does not`() {
        val sms = "device.sms"
        assertEquals(Verdict.NEEDS_SWITCH, TaskReadiness.of(listOf(sms), nothingOn).blockers.single().verdict)
        assertTrue(TaskReadiness.of(listOf(sms), nothingOn.copy(unsafeSwitchesOn = setOf(sms))).ready)
    }

    @Test fun `an id the catalog does not know is ignored instead of raising a false alarm`() {
        assertTrue(TaskReadiness.of(listOf("made.up.capability"), nothingOn).ready)
    }

    @Test fun `repeated ids count once and the line names the first blocker plus a count`() {
        val result = TaskReadiness.of(listOf("device.ui", "device.ui", "device.sms"), nothingOn)
        assertEquals(2, result.blockers.size)
        assertTrue(result.line(), result.line().startsWith("Needs: "))
        assertTrue(result.line(), result.line().endsWith("and 1 more"))
    }

    @Test fun `the card line never contains an id`() {
        val line = TaskReadiness.of(listOf("device.ui"), nothingOn).line()
        assertFalse(line, line.contains("device."))
    }
}
