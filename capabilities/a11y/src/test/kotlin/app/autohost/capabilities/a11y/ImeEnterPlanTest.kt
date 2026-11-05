package app.autohost.capabilities.a11y

import org.junit.Assert.assertEquals
import org.junit.Test

/** P12-10 (RM-A1): the IME-Enter plan — API 30+ gets ACTION_IME_ENTER, below that the click fallback. */
class ImeEnterPlanTest {

    @Test
    fun `given API 30 plus when planned then IME_ENTER`() {
        assertEquals(ImeEnterPlan.Plan.ImeEnter, ImeEnterPlan.plan(30))
        assertEquals(ImeEnterPlan.Plan.ImeEnter, ImeEnterPlan.plan(31))
        assertEquals(ImeEnterPlan.Plan.ImeEnter, ImeEnterPlan.plan(34))
    }

    @Test
    fun `given API 29 when planned then the click fallback`() {
        assertEquals(ImeEnterPlan.Plan.ClickFallback, ImeEnterPlan.plan(29))
    }
}
