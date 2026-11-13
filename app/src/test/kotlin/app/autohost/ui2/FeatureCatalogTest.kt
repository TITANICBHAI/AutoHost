package app.autohost.ui2

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureCatalogTest {

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("repo file not found from ${System.getProperty("user.dir")}: $relative")
    }

    /** capability id -> its tiers, from the contract the host is generated from. */
    private fun specs(): Map<String, Set<String>> {
        val root = Json.parseToJsonElement(repoFile("schemas/capability-specs.w1.json").readText()).jsonObject
        val caps: JsonArray = root["capabilities"]!!.jsonArray
        return caps.associate { c ->
            val o: JsonObject = c.jsonObject
            o["id"]!!.jsonPrimitive.content to o["tiers"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        }
    }

    @Test fun `every capability in the contract has exactly one entry in the catalog and vice versa`() {
        val spec = specs().keys
        val catalog = FeatureCatalog.all.map { it.capabilityId }
        assertEquals("duplicates in catalog", catalog.size, catalog.toSet().size)
        assertEquals("in the contract but not the catalog", emptySet<String>(), spec - catalog.toSet())
        assertEquals("in the catalog but not the contract", emptySet<String>(), catalog.toSet() - spec)
    }

    @Test fun `a capability whose only tier is the shell tier is advanced and nothing else is`() {
        val tiers = specs()
        for (cap in FeatureCatalog.all) {
            val t = tiers.getValue(cap.capabilityId)
            val shellOnly = t == setOf("T3")
            if (shellOnly) assertEquals(cap.capabilityId, Need.ADVANCED, cap.need)
            // device.settings: reads are free, only writes need the grant or shell, so it is the one advanced entry with T0.
            if (cap.need == Need.ADVANCED && !shellOnly) assertEquals(cap.capabilityId, "device.settings", cap.capabilityId)
        }
    }

    @Test fun `groups keep display order and cover every entry`() {
        val groups = FeatureCatalog.groups()
        assertEquals(FeatureCatalog.all.size, groups.sumOf { it.second.size })
        assertEquals(groups.map { it.first }, FeatureGroup.entries.filter { g -> groups.any { it.first == g } })
    }

    @Test fun `most of the app works without advanced access`() {
        val advanced = FeatureCatalog.all.count { it.need == Need.ADVANCED }
        assertTrue("advanced=$advanced of ${FeatureCatalog.all.size}", advanced <= 8)
        assertNotNull(FeatureCatalog.need("device.ui"))
        assertEquals(null, FeatureCatalog.need("nope"))
    }

    @Test fun `with nothing set up ready features are ready and toggles wait for their own switch`() {
        val none = LiveAccess()
        assertEquals(Verdict.READY, Readiness.of(FeatureCatalog.need("net.http")!!, none))
        assertEquals(Verdict.ASKS_ON_USE, Readiness.of(FeatureCatalog.need("camera")!!, none))
        assertEquals(Verdict.NEEDS_SETUP, Readiness.of(FeatureCatalog.need("device.ui")!!, none))
        assertEquals(Verdict.NEEDS_SWITCH, Readiness.of(FeatureCatalog.need("device.sms")!!, none))
        assertEquals(Verdict.NEEDS_ADVANCED, Readiness.of(FeatureCatalog.need("device.shell")!!, none))

        val a11yOnly = LiveAccess(accessibilityOn = true)
        assertEquals(Verdict.READY, Readiness.of(FeatureCatalog.need("device.ui")!!, a11yOnly))
        assertEquals(Verdict.NEEDS_SETUP, Readiness.of(FeatureCatalog.need("device.notifications")!!, a11yOnly))
        assertEquals(Verdict.NEEDS_SETUP, Readiness.of(FeatureCatalog.need("device.ime")!!, a11yOnly))
    }

    @Test fun `the one-time secure settings grant satisfies system settings without any shell`() {
        val granted = LiveAccess(secureSettingsGranted = true)
        assertEquals(Verdict.READY, Readiness.of(FeatureCatalog.need("device.settings")!!, granted))
        assertEquals(Verdict.NEEDS_ADVANCED, Readiness.of(FeatureCatalog.need("device.shell")!!, granted))
        assertEquals(Verdict.READY, Readiness.of(FeatureCatalog.need("device.shell")!!, LiveAccess(advancedLive = true)))
    }

    @Test fun `an unsafe switch that is on makes its capability ready`() {
        val live = LiveAccess(unsafeSwitchesOn = setOf("device.sms"))
        assertEquals(Verdict.READY, Readiness.of(FeatureCatalog.need("device.sms")!!, live))
        assertEquals(Verdict.NEEDS_SWITCH, Readiness.of(FeatureCatalog.need("device.telephony")!!, live))
    }

    @Test fun `summary counts add up to the catalog size`() {
        val s = Readiness.summary(LiveAccess())
        assertEquals(FeatureCatalog.all.size, s.values.sum())
        assertFalse(s.getOrDefault(Verdict.READY, 0) == 0)
    }
}
