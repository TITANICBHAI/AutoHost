package app.autohost.core.engine

import app.autohost.core.engine.tasks.CapabilityCatalog
import app.autohost.core.engine.tasks.CapabilityResolver
import app.autohost.core.engine.tasks.DeviceProbe
import app.autohost.core.engine.tasks.ImportReport
import app.autohost.core.model.Availability
import app.autohost.core.model.CapabilityNeed
import app.autohost.core.model.AppNeed
import app.autohost.core.model.Needs
import app.autohost.core.model.RuntimeSpec
import app.autohost.core.model.TaskManifest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityResolverTest {

    private val catalogJson = """
        {
          "capabilities": [
            {"id": "device.ui", "summary": "UI control", "minSdk": 29,
             "permissions": [{"id": "device.ui.observe"}, {"id": "device.ui.act"}],
             "androidAccess": ["ACCESSIBILITY_SERVICE"], "methods": []},
            {"id": "device.ime", "summary": "Keyboard", "minSdk": 33, "permissions": [], "androidAccess": [], "methods": []},
            {"id": "android.api.direct", "summary": "Raw bridge", "minSdk": 29, "permissions": [], "androidAccess": [], "methods": []}
          ]
        }
    """.trimIndent()

    private class FakeProbe(
        override val sdkInt: Int = 29,
        private val apps: Map<String, DeviceProbe.InstalledApp> = emptyMap(),
        private val availability: Map<String, Availability> = emptyMap(),
        private val switches: Map<String, Boolean> = emptyMap(),
    ) : DeviceProbe {
        override fun appInfo(packageName: String): DeviceProbe.InstalledApp? = apps[packageName]
        override fun availabilityOf(capabilityId: String): Availability? = availability[capabilityId]
        override fun switchEnabled(family: String): Boolean? = switches[family]
    }

    private fun manifest(needs: Needs) = TaskManifest(
        id = "com.example.c",
        name = "C",
        runtime = RuntimeSpec("python", "main.py"),
        needs = needs,
    )

    private fun statusOf(rows: List<ImportReport.CompatRow>, id: String) =
        rows.first { it.id == id }.status

    private fun packagesScope(vararg packages: String) = buildJsonObject {
        putJsonArray("packages") { packages.forEach { add(JsonPrimitive(it)) } }
    }

    @Test
    fun `available capability is AVAILABLE with a scope summary`() {
        val probe = FakeProbe(availability = mapOf("device.ui" to Availability.Available))
        val resolver = CapabilityResolver(CapabilityCatalog.parse(catalogJson), probe)
        val rows = resolver.check(
            manifest(Needs(capabilities = listOf(CapabilityNeed("device.ui", scope = packagesScope("com.target"))))),
            configSchema = null,
        )
        assertEquals("AVAILABLE", statusOf(rows, "device.ui"))
        assertEquals("only in com.target", rows.first { it.id == "device.ui" }.detail)
        assertTrue(resolver.canRun(rows))
    }

    @Test
    fun `needs setup surfaces the fix action, unsupported surfaces the reason`() {
        val probe = FakeProbe(
            availability = mapOf(
                "device.ui" to Availability.NeedsSetup("a11y off", "OPEN_ACCESSIBILITY_SETTINGS"),
                "notify.post" to Availability.Unsupported("no listener on this build"),
            ),
        )
        val resolver = CapabilityResolver(CapabilityCatalog.parse(catalogJson), probe)
        val rows = resolver.check(
            manifest(
                Needs(
                    capabilities = listOf(CapabilityNeed("device.ui")),
                    optional = listOf(CapabilityNeed("notify.post")),
                ),
            ),
            configSchema = null,
        )
        assertEquals("NEEDS_SETUP", statusOf(rows, "device.ui"))
        assertEquals("OPEN_ACCESSIBILITY_SETTINGS", rows.first { it.id == "device.ui" }.fixAction)
        assertEquals("UNSUPPORTED", statusOf(rows, "notify.post"))
        assertTrue(resolver.canRun(rows)) // optional failures don't block
    }

    @Test
    fun `api below the capability minimum is UNSUPPORTED and blocks the run`() {
        val resolver = CapabilityResolver(CapabilityCatalog.parse(catalogJson), FakeProbe(sdkInt = 30))
        val rows = resolver.check(
            manifest(Needs(capabilities = listOf(CapabilityNeed("device.ime")))),
            configSchema = null,
        )
        assertEquals("UNSUPPORTED", statusOf(rows, "device.ime"))
        assertTrue(rows.first { it.id == "device.ime" }.detail!!.contains("API 33"))
        assertFalse(resolver.canRun(rows))
    }

    @Test
    fun `unknown capability is UNSUPPORTED with a detail`() {
        val resolver = CapabilityResolver(CapabilityCatalog.parse(catalogJson), FakeProbe())
        val rows = resolver.check(
            manifest(Needs(capabilities = listOf(CapabilityNeed("device.nonexistent")))),
            configSchema = null,
        )
        assertEquals("UNSUPPORTED", statusOf(rows, "device.nonexistent"))
        assertFalse(resolver.canRun(rows))
    }

    @Test
    fun `disabled insecure-feature switch is DENIED with OPEN_INSECURE_SWITCH fix`() {
        // P8-13 semantics: the row uses the FEATURE id — android.api.direct → raw_api — and
        // capabilities without a switch (device.ui) are never gated by one.
        val probe = FakeProbe(switches = mapOf("raw_api" to false), availability = mapOf("android.api.direct" to Availability.Available))
        val resolver = CapabilityResolver(CapabilityCatalog.parse(catalogJson), probe)
        val rows = resolver.check(
            manifest(Needs(capabilities = listOf(CapabilityNeed("android.api.direct"), CapabilityNeed("device.ui")))),
            configSchema = null,
        )
        assertEquals("DENIED", statusOf(rows, "android.api.direct"))
        assertEquals("OPEN_INSECURE_SWITCH", rows.first { it.id == "android.api.direct" }.fixAction)
        assertEquals("AVAILABLE", statusOf(rows, "device.ui")) // no switch feature → not gated
    }

    @Test
    fun `relaxed mode satisfies the switch row until denied`() {
        val probe = FakeProbe(switches = mapOf("raw_api" to true), availability = mapOf("android.api.direct" to Availability.Available))
        val resolver = CapabilityResolver(CapabilityCatalog.parse(catalogJson), probe)
        val rows = resolver.check(
            manifest(Needs(capabilities = listOf(CapabilityNeed("android.api.direct")))),
            configSchema = null,
        )
        assertEquals("AVAILABLE", statusOf(rows, "android.api.direct"))
    }

    @Test
    fun `apps are checked for presence and version`() {
        val probe = FakeProbe(
            apps = mapOf(
                "com.installed" to DeviceProbe.InstalledApp("com.installed", versionCode = 50, versionName = "1.5"),
                "com.old" to DeviceProbe.InstalledApp("com.old", versionCode = 5, versionName = "0.5"),
            ),
        )
        val resolver = CapabilityResolver(CapabilityCatalog.parse(catalogJson), probe)
        val rows = resolver.check(
            manifest(
                Needs(
                    apps = listOf(
                        AppNeed("com.installed"),
                        AppNeed("com.old", minVersionCode = 10),
                        AppNeed("com.missing"),
                    ),
                ),
            ),
            configSchema = null,
        )
        assertEquals("AVAILABLE", statusOf(rows, "com.installed"))
        assertEquals("NEEDS_SETUP", statusOf(rows, "com.old"))
        assertEquals("MISSING", statusOf(rows, "com.missing"))
        assertFalse(resolver.canRun(rows))
    }

    @Test
    fun `config placeholder packages resolve through schema defaults`() {
        val probe = FakeProbe(availability = mapOf("device.ui" to Availability.Available))
        val resolver = CapabilityResolver(CapabilityCatalog.parse(catalogJson), probe)
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("target") {
                    put("type", "string")
                    put("default", "com.default.app")
                }
            }
        }
        val rows = resolver.check(
            manifest(
                Needs(
                    capabilities = listOf(
                        CapabilityNeed("device.ui", scope = packagesScope("${'$'}{config.target}")),
                    ),
                ),
            ),
            configSchema = schema,
        )
        assertEquals("only in com.default.app", rows.first { it.id == "device.ui" }.detail)
    }
}
