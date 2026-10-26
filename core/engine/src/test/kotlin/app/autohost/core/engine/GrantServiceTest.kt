package app.autohost.core.engine

import app.autohost.core.broker.PermissionIndex
import app.autohost.core.engine.tasks.GrantService
import app.autohost.core.engine.tasks.ImportReport
import app.autohost.core.model.RuntimeSpec
import app.autohost.core.model.TaskManifest
import app.autohost.core.model.Needs
import app.autohost.core.model.CapabilityNeed
import app.autohost.core.model.AppNeed
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrantServiceTest {

    private val index = PermissionIndex.parse(
        """
        {"capabilities":[
          {"id":"device.ui","permissions":[{"id":"device.ui.observe"},{"id":"device.ui.act"}]},
          {"id":"notify.post","permissions":[{"id":"notify.post"}]}
        ]}
        """.trimIndent(),
    )
    private val service = GrantService(index)

    private fun manifest(vararg capabilities: String, scopes: Map<String, kotlinx.serialization.json.JsonObject> = emptyMap()) =
        TaskManifest(
            id = "com.example.g",
            name = "G",
            runtime = RuntimeSpec("python", "main.py"),
            needs = Needs(
                capabilities = capabilities.map { CapabilityNeed(it, scopes[it]) },
            ),
        )

    @Test
    fun `a capability need becomes one grant row per permission with the templated scope`() {
        val scope = buildJsonObject {
            putJsonArray("packages") { add("com.target") }
        }
        val records = service.resolve(
            manifest("device.ui", scopes = mapOf("device.ui" to scope)),
            config = buildJsonObject { put("unused", 1) },
            profile = kotlinx.serialization.json.JsonObject(emptyMap()),
        )
        assertEquals(setOf("device.ui.observe", "device.ui.act"), records.map { it.permission }.toSet())
        assertTrue(records.all { it.capability == "device.ui" })
        assertTrue(records.all { it.required })
        // fingerprint differs when the scope differs
        val other = service.resolve(
            manifest("device.ui", scopes = mapOf("device.ui" to buildJsonObject { putJsonArray("packages") { add("com.other") } })),
            config = kotlinx.serialization.json.JsonObject(emptyMap()),
            profile = kotlinx.serialization.json.JsonObject(emptyMap()),
        )
        assertTrue(records.first().fingerprint != other.first().fingerprint)
    }

    @Test
    fun `optional needs are marked not required`() {
        val records = service.resolve(
            TaskManifest(
                id = "com.example.g",
                name = "G",
                runtime = RuntimeSpec("python", "main.py"),
                needs = Needs(optional = listOf(CapabilityNeed("notify.post"))),
            ),
            config = kotlinx.serialization.json.JsonObject(emptyMap()),
            profile = kotlinx.serialization.json.JsonObject(emptyMap()),
        )
        assertEquals(1, records.size)
        assertFalse(records.first().required)
    }

    @Test
    fun `unresolved placeholders stay literal in scopes (fail closed)`() {
        val scope = buildJsonObject { putJsonArray("packages") { add("${'$'}{config.target_package}") } }
        val records = service.resolve(
            manifest("device.ui", scopes = mapOf("device.ui" to scope)),
            config = kotlinx.serialization.json.JsonObject(emptyMap()), // target_package missing!
            profile = kotlinx.serialization.json.JsonObject(emptyMap()),
        )
        val packages = records.first().scope["packages"] as kotlinx.serialization.json.JsonArray
        assertEquals("${'$'}{config.target_package}", (packages.first() as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `diff reports added and removed permissions`() {
        val empty = emptyList<GrantService.GrantRecord>()
        val one = service.resolve(manifest("device.ui"), config = kotlinx.serialization.json.JsonObject(emptyMap()), profile = kotlinx.serialization.json.JsonObject(emptyMap()))
        val added = service.diff(empty, one)
        assertTrue(added.all { it.change == ImportReport.GrantDiffRow.ADDED })

        val removed = service.diff(one, empty)
        assertTrue(removed.all { it.change == ImportReport.GrantDiffRow.REMOVED })
        assertTrue(service.needsReview(empty, one))
    }

    @Test
    fun `scope widening triggers a review, narrowing does not`() {
        fun recordsFor(pkg: String?) = service.resolve(
            manifest(
                "device.ui",
                scopes = mapOf(
                    "device.ui" to (if (pkg == null) {
                        kotlinx.serialization.json.JsonObject(emptyMap()) // no packages key → unrestricted
                    } else {
                        buildJsonObject { putJsonArray("packages") { add(pkg) } }
                    }),
                ),
            ),
            config = kotlinx.serialization.json.JsonObject(emptyMap()),
            profile = kotlinx.serialization.json.JsonObject(emptyMap()),
        )

        val scoped = recordsFor("com.target")
        val wider = recordsFor(null) // unrestricted

        val widen = service.diff(scoped, wider)
        assertTrue(widen.any { it.change == ImportReport.GrantDiffRow.WIDENED })

        val narrow = service.diff(wider, scoped)
        assertTrue(narrow.any { it.change == ImportReport.GrantDiffRow.NARROWED })

        val addPkg = service.diff(
            scoped,
            service.resolve(
                manifest(
                    "device.ui",
                    scopes = mapOf("device.ui" to buildJsonObject { putJsonArray("packages") { add("com.target"); add("com.extra") } }),
                ),
                config = kotlinx.serialization.json.JsonObject(emptyMap()),
                profile = kotlinx.serialization.json.JsonObject(emptyMap()),
            ),
        )
        assertTrue(addPkg.any { it.change == ImportReport.GrantDiffRow.WIDENED })

        // unchanged scope → no diff, no re-review
        assertFalse(service.needsReview(scoped, recordsFor("com.target")))
    }

    @Test
    fun `grants json round-trips`() {
        val records = service.resolve(manifest("device.ui", "notify.post"), config = kotlinx.serialization.json.JsonObject(emptyMap()), profile = kotlinx.serialization.json.JsonObject(emptyMap()))
        val json = service.grantsJson(records)
        val back = service.grantsFromJson(json)
        assertEquals(records.map { it.permission to it.fingerprint }, back.map { it.permission to it.fingerprint })
        assertEquals(records.map { it.required }, back.map { it.required })
    }
}
