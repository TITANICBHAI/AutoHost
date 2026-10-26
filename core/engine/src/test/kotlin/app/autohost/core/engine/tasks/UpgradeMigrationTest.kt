package app.autohost.core.engine.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P11-05 (written; awaiting Gradle): the upgrade/migration contract (Gate 11-e's written half,
 * plan §18 P11). Manifest migrations must stay pure and versioned (v1 is a no-op today; a
 * newer-than-host manifest is HOST_TOO_OLD, never guessed at); SemVer ordering (upgrades) must
 * follow semver §11 — pre-release sorts before the release, build metadata is ignored. When a
 * schema v2 lands, its migration step gets registered in the chain and pinned here.
 */
class UpgradeMigrationTest {

    // --- manifest migrations -------------------------------------------------------------------

    @Test
    fun `a current-schema manifest passes through untouched`() {
        val manifest = """{"schema":1,"id":"app.example.task","version":"1.0.0","runtime":{"type":"python","entry":"main.py"}}"""
        val (migrated, applied) = ManifestMigrations.migrate(manifest)
        assertEquals(emptyList<String>(), applied)
        assertEquals("app.example.task", (migrated["id"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `a manifest newer than the host is refused, never guessed at`() {
        try {
            ManifestMigrations.migrate("""{"schema":99,"id":"app.example.task"}""")
            fail("expected MigrationException")
        } catch (e: ManifestMigrations.MigrationException) {
            assertTrue(e.message!!.contains("newer than host"))
        }
    }

    @Test
    fun `a malformed manifest fails loudly instead of migrating`() {
        try {
            ManifestMigrations.migrate("{ nope")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // the message shape is part of the contract: why the manifest could not migrate
            assertTrue(e.message!!.contains("JSON object"))
        }
    }

    // --- semver ordering (upgrade comparisons) ---------------------------------------------------

    @Test
    fun `patch minor and major ordering`() {
        assertTrue(SemVer.compare("1.0.0", "1.0.1") < 0)
        assertTrue(SemVer.compare("1.0.9", "1.1.0") < 0)
        assertTrue(SemVer.compare("1.9.9", "2.0.0") < 0)
        assertEquals(0, SemVer.compare("2.0.0", "2.0.0"))
    }

    @Test
    fun `pre-release sorts before the release and compares numerically`() {
        assertTrue(SemVer.compare("1.0.0-alpha", "1.0.0") < 0)
        assertTrue(SemVer.compare("1.0.0-alpha", "1.0.0-alpha.1") < 0)
        assertTrue(SemVer.compare("1.0.0-1", "1.0.0-alpha") < 0) // numeric < alphanumeric
        assertTrue(SemVer.compare("1.0.0-beta", "1.0.0-alpha.2") > 0)
    }

    @Test
    fun `build metadata is ignored by comparisons`() {
        assertEquals(0, SemVer.compare("1.0.0+build.1", "1.0.0+build.2"))
        assertTrue(SemVer.compare("1.0.0+build.1", "1.0.1") < 0)
    }

    @Test
    fun `the migration chain is pure and deterministic`() {
        val manifest = """{"schema":1,"id":"app.example.task","version":"2.0.0+build.7"}"""
        val first = ManifestMigrations.migrate(manifest)
        val second = ManifestMigrations.migrate(manifest)
        assertEquals(first, second)
    }
}
