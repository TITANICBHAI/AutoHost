package app.autohost.ui2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GetStartedTest {

    @Test fun `a fresh install has exactly one required step and it comes first`() {
        val steps = GetStarted.steps(LiveAccess(), SetupFacts())
        assertEquals(StepId.ACCESSIBILITY, steps.first().id)
        assertEquals(1, steps.count { it.level == Level.REQUIRED })
        assertEquals(1, GetStarted.requiredLeft(steps))
        assertEquals(StepId.ACCESSIBILITY, GetStarted.next(steps)!!.id)
        assertTrue(GetStarted.needsAttention(steps, SetupFacts()))
    }

    @Test fun `no step asks for a computer`() {
        GetStarted.steps(LiveAccess(), SetupFacts()).forEach {
            val text = (it.title + it.why + it.button).lowercase()
            assertFalse(it.id.name, text.contains("computer") || text.contains("adb") || text.contains("usb"))
        }
    }

    @Test fun `accessibility on moves the highlight to the recommended steps`() {
        val steps = GetStarted.steps(LiveAccess(accessibilityOn = true), SetupFacts())
        assertEquals(0, GetStarted.requiredLeft(steps))
        assertEquals(StepId.KEEP_ALIVE, GetStarted.next(steps)!!.id)
    }

    @Test fun `once required and recommended steps are done nothing is highlighted`() {
        val live = LiveAccess(accessibilityOn = true, batteryExempt = true)
        val steps = GetStarted.steps(live, SetupFacts(hasTasks = true, onboarded = true))
        assertNull(GetStarted.next(steps))
        assertFalse(GetStarted.needsAttention(steps, SetupFacts(hasTasks = true, onboarded = true)))
    }

    @Test fun `not having finished the checklist still nudges even when the switch is on`() {
        val steps = GetStarted.steps(LiveAccess(accessibilityOn = true), SetupFacts(onboarded = false))
        assertTrue(GetStarted.needsAttention(steps, SetupFacts(onboarded = false)))
    }

    @Test fun `optional steps never block and counts add up`() {
        val steps = GetStarted.steps(LiveAccess(accessibilityOn = true, notificationAccessOn = true), SetupFacts(aiConfigured = true))
        assertTrue(steps.filter { it.level == Level.OPTIONAL }.none { it.id == StepId.ACCESSIBILITY })
        val (done, total) = GetStarted.counts(steps)
        assertEquals(steps.size, total)
        assertEquals(steps.count { it.done }, done)
        assertEquals(setOf("OPEN_ACCESSIBILITY_SETTINGS", "BATTERY_EXEMPTION", "OPEN_NOTIFICATION_LISTENER_SETTINGS", "SELECT_IME", "OPEN_AI_SETTINGS", "ADD_TASK"), steps.map { it.action }.toSet())
    }
}
