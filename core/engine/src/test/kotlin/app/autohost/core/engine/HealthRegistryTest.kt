package app.autohost.core.engine

import app.autohost.core.engine.health.CheckResult
import app.autohost.core.engine.health.HealthEnv
import app.autohost.core.engine.health.HealthRegistry
import app.autohost.core.engine.health.InitialChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HealthRegistryTest {

    private lateinit var registry: HealthRegistry

    @Before
    fun setUp() {
        registry = HealthRegistry()
        InitialChecks.registerAll(registry)
    }

    private fun healthy(sdkInt: Int = 29) = HealthEnv(
        sdkInt = sdkInt,
        accessibilityEnabled = true,
        accessibilityConnected = true,
        notificationsPermission = true,
        batteryExemption = true,
        workerAlive = true,
        exactAlarmsAllowed = true,
    )

    @Test
    fun `a healthy API 29 device has no required issues`() {
        val grouped = registry.runGrouped(healthy())
        assertFalse(grouped.hasMajorIssues)
        assertTrue(grouped.needsFix.isEmpty())
        // exact_alarms check does not apply below API 31
        assertEquals(0, registry.runAll(healthy(sdkInt = 29)).count { it.check.id == "exact_alarms" })
        // but applies on 33+
        assertEquals(1, registry.runAll(healthy(sdkInt = 33)).count { it.check.id == "exact_alarms" })
    }

    @Test
    fun `accessibility states produce distinct reason codes`() {
        val off = registry.runAll(healthy().copy(accessibilityEnabled = false)).first { it.check.id == "accessibility" }
        assertEquals("A11Y_SERVICE_DISABLED", (off.result as CheckResult.Issue).reasonCode)

        val notConnected = registry.runAll(healthy().copy(accessibilityConnected = false))
            .first { it.check.id == "accessibility" }
        assertEquals("A11Y_SERVICE_NOT_CONNECTED", (notConnected.result as CheckResult.Issue).reasonCode)

        val blocked = registry.runAll(healthy().copy(accessibilityBlockedByPolicy = true))
            .first { it.check.id == "accessibility" }
        assertEquals("A11Y_BLOCKED_ADVANCED_PROTECTION", (blocked.result as CheckResult.Issue).reasonCode)
        assertTrue(blocked.check.required) // policy block is a required row
    }

    @Test
    fun `notifications check only applies on API 33+`() {
        val on29 = registry.runAll(healthy(sdkInt = 29))
        assertTrue(on29.none { it.check.id == "notifications" })
        val deniedOn33 = registry.runAll(healthy(sdkInt = 33).copy(notificationsPermission = false))
        val row = deniedOn33.first { it.check.id == "notifications" }
        assertEquals("NOTIFICATIONS_DENIED", (row.result as CheckResult.Issue).reasonCode)
        assertTrue(registry.runGrouped(healthy(sdkInt = 33).copy(notificationsPermission = false)).hasMajorIssues)
    }

    @Test
    fun `optional failing checks land in the optional group, not needs-fix`() {
        val grouped = registry.runGrouped(healthy().copy(batteryExemption = false, shellTier = HealthEnv.ShellTier.NONE, devApiEnabled = false))
        val ids = grouped.optional.map { it.check.id }.toSet()
        assertTrue("battery" in ids)
        assertTrue("shell_tier" in ids)
        assertTrue("dev_api" in ids)
        assertFalse(grouped.hasMajorIssues)
    }

    @Test
    fun `a required failure lands in needsFix and flips the CLI exit code rule`() {
        val grouped = registry.runGrouped(healthy().copy(accessibilityEnabled = false))
        assertTrue(grouped.hasMajorIssues)
        assertEquals(listOf("accessibility"), grouped.needsFix.map { it.check.id })
    }

    @Test
    fun `worker-not-started is a required row that only reports until first run`() {
        val grouped = registry.runGrouped(healthy().copy(workerAlive = false))
        assertTrue(grouped.needsFix.any { it.check.id == "worker" })
    }

    @Test
    fun `no duplicate ids and every check has a title`() {
        val all = registry.all()
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.all { it.title.isNotBlank() })
        assertTrue(all.size >= 8)
    }
}
