package app.autohost.ui2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPlanTest {

    private val steps = GetStarted.steps(LiveAccess(), SetupFacts())
    private val pages = OnboardingPlan.pages(steps)

    @Test fun `welcome first, one page per step in order, finish last`() {
        assertEquals(OnboardingPlan.Page.Welcome, pages.first())
        assertEquals(OnboardingPlan.Page.Finish, pages.last())
        assertEquals(steps.size + 2, pages.size)
        assertEquals(steps, pages.filterIsInstance<OnboardingPlan.Page.Step>().map { it.step })
    }

    @Test fun `the step label counts steps, not pages`() {
        assertEquals("", OnboardingPlan.label(0, pages))
        assertEquals("Step 1 of ${steps.size}", OnboardingPlan.label(1, pages))
        assertEquals("Step ${steps.size} of ${steps.size}", OnboardingPlan.label(steps.size, pages))
        assertEquals("", OnboardingPlan.label(pages.lastIndex, pages))
    }

    @Test fun `a remembered index can never leave the pages`() {
        assertEquals(0, OnboardingPlan.clamp(-3, pages))
        assertEquals(pages.lastIndex, OnboardingPlan.clamp(99, pages))
        assertEquals(2, OnboardingPlan.clamp(2, pages))
        assertEquals(0, OnboardingPlan.clamp(5, emptyList()))
    }

    @Test fun `skip is honest before a step is done and next after`() {
        val open = steps.first { !it.done }
        assertEquals("Skip for now", OnboardingPlan.nextLabel(open))
        assertEquals("Next", OnboardingPlan.nextLabel(open.copy(done = true)))
    }

    @Test fun `the finish sentence names how many required steps are open and never asks for a computer`() {
        val required = GetStarted.requiredLeft(steps)
        assertTrue("fresh phone has a required step", required >= 1)
        val open = OnboardingPlan.summary(steps)
        assertTrue(open, open.contains("required"))
        val allDone = OnboardingPlan.summary(steps.map { it.copy(done = true) })
        assertTrue(allDone, allDone.contains("Everything AutoHost needs"))
        listOf(open, allDone).forEach {
            val t = it.lowercase()
            assertFalse(it, t.contains("adb") || t.contains("usb") || t.contains("plug"))
        }
    }

    @Test fun `no page text contains an id or a package name`() {
        steps.forEach {
            val text = (it.title + " " + it.why + " " + it.button)
            assertFalse(text, text.contains("device.") || text.contains("com.") || text.contains("StepId"))
        }
    }
}
