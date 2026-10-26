package app.autohost.core.engine

import app.autohost.core.engine.backup.BackupCodec
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P11-08 (ADR-082): the backup codec contract — round-trips, the secrets-never-exported
 * invariant (names only, forbidden keys refused on decode), schema gates, and the restore plan.
 */
class BackupCodecTest {

    private val manifestJson = """{"schema":1,"id":"com.example.demo","name":"Demo","version":"1.0.0","runtime":{"type":"python","entry":"main.py"}}"""

    private val document = BackupCodec.Document(
        createdAt = "2026-01-01T00:00:00Z",
        appVersion = "1.0.0",
        tasks = listOf(BackupCodec.BackupTask("com.example.demo", manifestJson, "a" * 64, bundleB64 = "YQ==")),
        profiles = listOf(
            BackupCodec.BackupProfile(
                id = "01HX",
                taskId = "com.example.demo",
                name = "Main",
                values = JsonObject(mapOf("room" to kotlinx.serialization.json.JsonPrimitive("kitchen"))),
                secretNames = listOf("api_key"),
                target = null,
            ),
        ),
    )

    // ---- encode ------------------------------------------------------------------------------

    @Test
    fun encodeRoundTripsThroughDecode() {
        val encoded = BackupCodec.encode(document)
        assertTrue(encoded.issues.toString(), encoded.ok)
        val decoded = BackupCodec.decode(encoded.json!!)
        assertTrue(decoded.issues.toString(), decoded.ok)
        val restored = decoded.document!!
        assertEquals(document.tasks, restored.tasks)
        assertEquals(document.profiles, restored.profiles)
        assertEquals("2026-01-01T00:00:00Z", restored.createdAt)
    }

    @Test
    fun secretsRideAsNamesOnlyNeverAsValues() {
        val encoded = BackupCodec.encode(document).json!!
        assertTrue(encoded.contains("\"secret_names\":[\"api_key\"]"))
        assertFalse(encoded.contains("\"secrets\""))
        assertFalse(encoded.contains("api_key_value"))
        // The profile values never grew a secret-shaped key even if the caller passed one.
        val poisoned = document.copy(
            profiles = listOf(
                document.profiles[0].copy(values = JsonObject(mapOf("secrets" to kotlinx.serialization.json.JsonPrimitive("{}")))),
            ),
        )
        val refused = BackupCodec.encode(poisoned)
        assertFalse(refused.ok)
        assertTrue(refused.issues.any { it.code == "SECRET_SHAPED_KEY" })
    }

    // ---- decode ------------------------------------------------------------------------------

    @Test
    fun decodeRefusesDocumentsThatSmuggleSecrets() {
        val smuggled = """{"schema":1,"kind":"autohost-backup","created_at":"x","tasks":[],"profiles":[],"secrets":{"api_key":"boom"}}"""
        val result = BackupCodec.decode(smuggled)
        assertFalse(result.ok)
        assertTrue(result.issues.any { it.code == "SECRET_SHAPED_KEY" })
        assertNull(result.document)
    }

    @Test
    fun decodeGatesSchemaKindAndStructure() {
        for (bad in listOf(
            "not json",
            """[]""",
            """{"schema":2,"kind":"autohost-backup","created_at":"x","tasks":[],"profiles":[]}""",
            """{"schema":1,"kind":"other","created_at":"x","tasks":[],"profiles":[]}""",
            """{"schema":1,"kind":"autohost-backup","tasks":[],"profiles":[]}""", // created_at missing
            """{"schema":1,"kind":"autohost-backup","created_at":"x","tasks":[{"id":"a"}],"profiles":[]}""", // manifest missing
            """{"schema":1,"kind":"autohost-backup","created_at":"x","tasks":[],"profiles":[{"id":"p"}]}""", // task_id/name missing
            """{"schema":1,"kind":"autohost-backup","created_at":"x","tasks":[],"profiles":[],"extra":1}""", // unknown top key (note)
        )) {
            val result = BackupCodec.decode(bad)
            if (bad.contains("\"extra\"")) {
                assertTrue("unknown keys are notes: $bad", result.ok)
            } else {
                assertFalse("expected rejection: $bad", result.ok)
            }
        }
    }

    // ---- restore plan ------------------------------------------------------------------------

    @Test
    fun restorePlanOverwritesOnlyWhenConfirmedAndAlwaysFlagsClearedSecrets() {
        val plan = BackupCodec.planRestore(
            document = document,
            installedTaskIds = setOf("com.example.demo"),
            installedProfileIds = setOf("01HX"),
            allowOverwrite = false,
        )
        assertEquals(BackupCodec.TaskAction.SKIP, plan.tasks[0].action)
        assertEquals(BackupCodec.ProfileAction.SKIP_EXISTS, plan.profiles[0].action)

        val overwrite = BackupCodec.planRestore(document, setOf("com.example.demo"), emptySet(), allowOverwrite = true)
        assertEquals(BackupCodec.TaskAction.OVERWRITE, overwrite.tasks[0].action)
        assertEquals(BackupCodec.ProfileAction.RESTORE_NEW, overwrite.profiles[0].action)
        assertTrue(overwrite.profiles[0].detail.contains("api_key")) // the honest "re-enter" hint

        val fresh = BackupCodec.planRestore(document, emptySet(), emptySet(), allowOverwrite = false)
        assertEquals(BackupCodec.TaskAction.RESTORE_NEW, fresh.tasks[0].action)
        assertEquals(BackupCodec.ProfileAction.RESTORE_NEW, fresh.profiles[0].action)

        // A dev-folder task (no bundle bytes) that is not installed cannot be resurrected — honest skip.
        val devFolder = document.copy(tasks = listOf(document.tasks[0].copy(bundleB64 = null)))
        val honest = BackupCodec.planRestore(devFolder, emptySet(), emptySet(), allowOverwrite = true)
        assertEquals(BackupCodec.TaskAction.SKIP, honest.tasks[0].action)
        assertTrue(honest.tasks[0].detail.contains("dev folder"))
    }
}

/** String-repeat helper so the 64-hex sha rows stay readable. */
private operator fun String.times(n: Int): String = repeat(n)
