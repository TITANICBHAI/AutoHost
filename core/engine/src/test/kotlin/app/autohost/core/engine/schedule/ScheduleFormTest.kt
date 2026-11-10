package app.autohost.core.engine.schedule

import app.autohost.core.common.FakeClock
import app.autohost.core.model.FanOut
import app.autohost.core.model.ScheduleLimits
import app.autohost.core.model.ScheduleRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleFormTest {

    private val daily = ScheduleRule(id = "a", task = "t", type = "daily", timezone = "Asia/Kolkata", times = listOf("09:00"))

    @Test fun `a plain daily rule can be shown on the form`() {
        val form = ScheduleForm.fromRule(daily)
        assertNotNull(form)
        assertEquals("daily", form!!.repeat)
        assertEquals(listOf("09:00"), form.times)
    }

    @Test fun `rules the form cannot show are refused so they stay in the developer section`() {
        assertNull(ScheduleForm.fromRule(ScheduleRule(id = "a", task = "t", type = "cron", cron = "0 9 * * *")))
        assertNull(ScheduleForm.fromRule(daily.copy(limits = ScheduleLimits(maxTotalRuns = 3))))
        assertNull(ScheduleForm.fromRule(daily.copy(fanout = FanOut(mode = "parallel"))))
        assertNull(ScheduleForm.fromRule(ScheduleRule(id = "a", task = "t", type = "interval", everyMinutes = 5, from = "previous_end")))
        assertNull(ScheduleForm.fromRule(daily.copy(timezone = "Mars/Olympus")))
    }

    @Test fun `whole hours are shown as hours and the rest as minutes`() {
        val hours = ScheduleForm.fromRule(ScheduleRule(id = "a", task = "t", type = "interval", everyMinutes = 120))!!
        assertEquals(2, hours.every)
        assertEquals(ScheduleForm.HOURS, hours.unit)
        val minutes = ScheduleForm.fromRule(ScheduleRule(id = "a", task = "t", type = "interval", everyMinutes = 90))!!
        assertEquals(90, minutes.every)
        assertEquals(ScheduleForm.MINUTES, minutes.unit)
    }

    @Test fun `an unusable form is refused instead of producing a rule`() {
        var refused = false
        try {
            ScheduleForm.toRule(ScheduleForm.Form(task = "", zone = "Asia/Kolkata", repeat = "daily"), "sch_x", 0L)
        } catch (_: IllegalArgumentException) {
            refused = true
        }
        assertTrue(refused)
    }

    @Test fun `rule ids look like sch_ plus eight lower-case characters and come from the clock`() {
        val id = ScheduleForm.newRuleId(FakeClock(1_792_940_400_000L))
        assertTrue(id, Regex("^sch_[0-9a-z]{8}$").matches(id))
    }
}
