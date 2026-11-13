package app.autohost.ui2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePlanTest {

    @Test fun `a fresh phone needs the three switches and every feature appears once`() {
        val plan = PhonePlan.build(LiveAccess(), sdkInt = 30)
        assertEquals(
            listOf(Readiness.Toggle.ACCESSIBILITY, Readiness.Toggle.NOTIFICATION_ACCESS, Readiness.Toggle.KEYBOARD),
            plan.steps.map { it.toggle },
        )
        assertEquals(FeatureCatalog.all.size, plan.totalCount)
        assertTrue(plan.steps.all { it.unlocks > 0 })
        assertTrue(plan.advancedNeeded)
    }

    @Test fun `accessibility unlocks the most features and is listed first`() {
        val plan = PhonePlan.build(LiveAccess(), 30)
        val a11y = plan.steps.single { it.toggle == Readiness.Toggle.ACCESSIBILITY }
        assertTrue(a11y.unlocks >= 4)
        assertEquals(plan.steps.maxOf { it.unlocks }, a11y.unlocks)
        assertEquals("OPEN_ACCESSIBILITY_SETTINGS", a11y.fixAction)
    }

    @Test fun `turning a switch on removes its step and raises the ready count`() {
        val before = PhonePlan.build(LiveAccess(), 30)
        val after = PhonePlan.build(LiveAccess(accessibilityOn = true), 30)
        assertFalse(after.steps.any { it.toggle == Readiness.Toggle.ACCESSIBILITY })
        assertTrue(after.readyCount > before.readyCount)
    }

    @Test fun `everything on leaves no steps and only the opt-in switches pending`() {
        val live = LiveAccess(true, true, true, advancedLive = true, secureSettingsGranted = true)
        val plan = PhonePlan.build(live, 34)
        assertTrue(plan.steps.isEmpty())
        assertFalse(plan.advancedNeeded)
        val pending = plan.groups.flatMap { it.rows }.filter { it.verdict != Verdict.READY && it.verdict != Verdict.ASKS_ON_USE }
        assertTrue(pending.all { it.verdict == Verdict.NEEDS_SWITCH })
    }

    @Test fun `fix actions only exist where there is something to press`() {
        val live = LiveAccess()
        assertNull(PhonePlan.fixActionFor(FeatureCatalog.need("net.http")!!, live))
        assertNull(PhonePlan.fixActionFor(FeatureCatalog.need("camera")!!, live))
        assertNull(PhonePlan.fixActionFor(FeatureCatalog.need("device.shell")!!, live)) // opens the advanced sheet instead
        assertEquals("SELECT_IME", PhonePlan.fixActionFor(FeatureCatalog.need("device.ime")!!, live))
        assertEquals("OPEN_INSECURE_SWITCH", PhonePlan.fixActionFor(FeatureCatalog.need("device.sms")!!, live))
    }

    @Test fun `routes follow the phone's android version and never put a computer first`() {
        val plan = PhonePlan.build(LiveAccess(), 29)
        assertEquals(AccessRoute.ROOT, plan.routes.first().route)
        assertFalse(plan.routes.first().needsComputer)
    }

    @Test fun `every verdict has a plain line`() {
        Verdict.entries.forEach { assertTrue(it.line().isNotBlank()) }
    }
}
