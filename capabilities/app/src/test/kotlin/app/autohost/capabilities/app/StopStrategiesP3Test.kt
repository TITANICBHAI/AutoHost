package app.autohost.capabilities.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stop chain policy (P3-20): honest results, ordered strategies, shell refused upstream. */
class StopStrategiesP3Test {

    @Test fun `auto runs kill first then ui then best effort`() {
        assertEquals(StopStrategies.Method.KILL_BACKGROUND, (StopStrategies.next(emptyList(), "auto", uiAvailable = true, foregroundLeft = false) as StopStrategies.Decision.Next).method)
        val afterKill = StopStrategies.next(listOf(StopStrategies.Attempt(StopStrategies.Method.KILL_BACKGROUND, true)), "auto", true, false)
        assertEquals(StopStrategies.Method.UI_FORCE_STOP, (afterKill as StopStrategies.Decision.Next).method)
        val afterUi = StopStrategies.next(
            listOf(
                StopStrategies.Attempt(StopStrategies.Method.KILL_BACKGROUND, true),
                StopStrategies.Attempt(StopStrategies.Method.UI_FORCE_STOP, true),
            ),
            "auto", true, false,
        )
        assertEquals(StopStrategies.BEST_EFFORT, (afterUi as StopStrategies.Decision.Done).result)
    }

    @Test fun `verified only when the target actually left the foreground`() {
        val done = StopStrategies.next(listOf(StopStrategies.Attempt(StopStrategies.Method.KILL_BACKGROUND, true)), "auto", true, foregroundLeft = true)
        assertEquals(StopStrategies.VERIFIED, (done as StopStrategies.Decision.Done).result)
        // ...and even with zero attempts (someone else closed it)
        assertEquals(StopStrategies.VERIFIED, (StopStrategies.next(emptyList(), "auto", false, true) as StopStrategies.Decision.Done).result)
    }

    @Test fun `ui-only strategy fails honestly without ui wiring`() {
        val decision = StopStrategies.next(emptyList(), "ui", uiAvailable = false, foregroundLeft = false)
        assertEquals(StopStrategies.FAILED, (decision as StopStrategies.Decision.Done).result)
    }

    @Test fun `kill-only strategy reports best effort without verification`() {
        val decision = StopStrategies.next(listOf(StopStrategies.Attempt(StopStrategies.Method.KILL_BACKGROUND, true)), "kill_background", false, false)
        assertEquals(StopStrategies.BEST_EFFORT, (decision as StopStrategies.Decision.Done).result)
    }
}
