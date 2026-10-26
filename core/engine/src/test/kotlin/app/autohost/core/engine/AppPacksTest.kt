package app.autohost.core.engine

import app.autohost.core.engine.tasks.AppPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P11-04 (ADR-080): the pure app-pack contract — pack.json parsing, resolution, the config
 * payload, and the label fallback. The install/store half is the app-side PackController.
 */
class AppPacksTest {

    private val packJson = """
        {
          "schema": 1,
          "id": "com.example.presser-pack",
          "target": "com.example.presser",
          "version": "1.2.0",
          "title": "Presser pack",
          "selectors": {
            "login_button": {"text": "Log in"},
            "confirm": {"id": "com.example.presser:id/confirm"}
          },
          "screens": {
            "login": {"markers": ["Log in", "Username"]}
          },
          "labels": {
            "en": {"login_button": "Log in", "confirm": "Confirm"},
            "de": {"login_button": "Anmelden"},
            "de-AT": {"login_button": "Anmelden"}
          },
          "tips": ["The login screen auto-focuses the username field."]
        }
    """.trimIndent()

    // ---- parsing -----------------------------------------------------------------------------

    @Test
    fun parsesAWellFormedPack() {
        val result = AppPacks.parse(packJson)
        assertTrue(result.issues.toString(), result.ok)
        val pack = result.pack!!
        assertEquals("com.example.presser-pack", pack.id)
        assertEquals("com.example.presser", pack.target)
        assertEquals("1.2.0", pack.version)
        assertEquals(2, pack.selectors.size)
        assertEquals(1, pack.screens.size)
        assertEquals(3, pack.labels.size)
        assertEquals(listOf("The login screen auto-focuses the username field."), pack.tips)
    }

    @Test
    fun malformedPacksAreRejectedFailClosed() {
        for (bad in listOf(
            "not json",
            """[]""",
            """{"schema":2,"id":"a.b","target":"a.b.C","version":"1.0.0"}""",           // schema
            """{"schema":1,"target":"a.b.C","version":"1.0.0"}""",                      // id missing
            """{"schema":1,"id":"Bad_ID","target":"a.b.C","version":"1.0.0"}""",        // id pattern
            """{"schema":1,"id":"a.b","version":"1.0.0"}""",                            // target missing
            """{"schema":1,"id":"a.b","target":"not a package","version":"1.0.0"}""",   // target pattern
            """{"schema":1,"id":"a.b","target":"a.b.C","version":"1"}""",               // semver
            """{"schema":1,"id":"a.b","target":"a.b.C","version":"1.0.0","selectors":{"Bad Name":{}}}""", // selector name
            """{"schema":1,"id":"a.b","target":"a.b.C","version":"1.0.0","labels":{"not_a_locale":{}}}""", // locale tag
            """{"schema":1,"id":"a.b","target":"a.b.C","version":"1.0.0","tips":[1,2]}""", // tips type
            """{"schema":1,"id":"a.b","target":"a.b.C","version":"1.0.0","extra":true}""", // unknown key (note) — stays a note
        )) {
            val result = AppPacks.parse(bad)
            if (bad.contains("\"extra\"")) {
                // Unknown keys are notes; the pack still parses.
                assertTrue("unknown key should not block: $bad", result.ok)
            } else {
                assertFalse("expected rejection: $bad", result.ok)
            }
        }
    }

    // ---- resolution --------------------------------------------------------------------------

    @Test
    fun resolveKeepsTaskOrderAndReportsMissingHonestly() {
        val a = AppPacks.parse(packJson).pack!!
        val b = a.copy(id = "com.example.other-pack", target = "com.example.other")
        val resolution = AppPacks.resolve(listOf(b.id, a.id, "com.example.nope"), mapOf(a.id to a, b.id to b))
        assertEquals(listOf(b, a), resolution.packs)
        assertEquals(listOf("com.example.nope"), resolution.missing)
        assertFalse(resolution.ok)
        val complete = AppPacks.resolve(listOf(a.id), mapOf(a.id to a))
        assertTrue(complete.ok)
        assertTrue(complete.missing.isEmpty())
    }

    @Test
    fun configPayloadIsDeterministicAndNamespacedPerPack() {
        val a = AppPacks.parse(packJson).pack!!
        val b = a.copy(id = "com.example.other-pack", target = "com.example.other")
        val payload = AppPacks.configPayload(AppPacks.resolve(listOf(b.id, a.id), mapOf(a.id to a, b.id to b)))
        assertEquals(listOf("com.example.other-pack", "com.example.presser-pack"), payload.keys.toList())
        val entry = payload["com.example.presser-pack"]!!
        assertEquals("com.example.presser", (entry as kotlinx.serialization.json.JsonObject)["target"].toString().trim('"'))
        assertTrue(entry.toString().contains("\"selectors\""))
    }

    // ---- lookups -----------------------------------------------------------------------------

    @Test
    fun selectorAndScreenLookupsFailHonestlyWhenAbsent() {
        val pack = AppPacks.parse(packJson).pack!!
        assertEquals("""{"text":"Log in"}""", AppPacks.selector(pack, "login_button").toString())
        assertNull(AppPacks.selector(pack, "nope"))
        assertNotNull(AppPacks.screen(pack, "login"))
        assertNull(AppPacks.screen(pack, "nope"))
    }

    @Test
    fun labelFallbackIsExactThenLanguageThenEnglishThenFirstAvailable() {
        val pack = AppPacks.parse(packJson).pack!!
        assertEquals("Anmelden", AppPacks.label(pack, "login_button", "de-DE"))   // language fallback
        assertEquals("Anmelden", AppPacks.label(pack, "login_button", "de-AT"))  // exact
        assertEquals("Log in", AppPacks.label(pack, "login_button", "fr"))       // en fallback
        assertEquals("Confirm", AppPacks.label(pack, "confirm", "de"))           // key missing in de → en
        assertNull(AppPacks.label(pack, "nobody_has_this", null))
        assertEquals("Log in", AppPacks.label(pack, "login_button", null))       // first-locale scan order (en first here)
    }
}
