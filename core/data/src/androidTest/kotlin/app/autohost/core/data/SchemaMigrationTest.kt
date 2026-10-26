package app.autohost.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * P2-06: schema/migration tests (plan §16). They run against the exported schema JSONs in
 * `core/data/schemas/` — those files are produced by KSP on the first Gradle build of this module
 * (`room.schemaLocation` in build.gradle.kts), so the first device session must build once before
 * this suite has anything to validate against.
 *
 * v1 is the first schema, so today the suite only opens and validates v1 and proves the (empty)
 * migration chain in [app.autohost.core.data.db.Migrations] is current. Every future schema version
 * appends: an exported `N.json`, a `Migration` in `Migrations`, and a case here — old cases are
 * never edited (they are the record that old installs can still climb the chain).
 */
class SchemaMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        app.autohost.core.data.db.AutoHostDatabase::class.java,
    )

    /** The exported v1 schema opens cleanly and carries the expected table set. */
    @Test
    fun schemaV1Opens() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.query("SELECT count(*) FROM sqlite_master WHERE type = 'table'").use { cursor ->
                cursor.moveToFirst()
                check(cursor.getInt(0) >= 13) { "v1 schema is missing tables" }
            }
        }
    }

    /** Walking from v1 with the shipped migration chain validates (chain empty at v1 by definition). */
    @Test
    fun migrationChainIsCurrent() {
        helper.runMigrationsAndValidate(DB_NAME, 1, true, *app.autohost.core.data.db.Migrations.ALL)
    }

    private companion object {
        const val DB_NAME = "autohost-migration-test.db"
    }
}
