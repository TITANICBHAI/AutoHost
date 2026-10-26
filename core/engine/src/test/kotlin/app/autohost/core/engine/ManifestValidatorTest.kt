package app.autohost.core.engine

import app.autohost.core.engine.tasks.ManifestValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestValidatorTest {

    /** A known-good manifest matching the schema (mirrors samples/press-button/task.json). */
    private val good = """
        {
          "schema": 1,
          "id": "com.example.press",
          "name": "Press",
          "version": "1.2.3-rc.1",
          "description": "Presses a button in another app.",
          "host_api": ">=1 <2",
          "runtime": {"type": "python", "entry": "main.py"},
          "needs": {
            "capabilities": [
              {"id": "device.ui", "scope": {"packages": ["${'$'}{config.target_package}"]}},
              {"id": "device.app.launch"}
            ],
            "optional": [{"id": "notify.post"}],
            "apps": [{"package": "${'$'}{config.target_package}", "min_version_code": 12}]
          },
          "config_schema": {
            "type": "object",
            "required": ["target_package"],
            "properties": {
              "target_package": {"type": "string", "title": "App", "x-ui": {"widget": "app-picker"}},
              "wait_seconds": {"type": "integer", "default": 20, "minimum": 1, "maximum": 600}
            }
          },
          "profile_schema": {"type": "object", "properties": {"password": {"type": "string", "x-secret": true}}},
          "stages": ["launch", "press"],
          "triggers": {"manual": true, "schedule": {"allowed": true, "default": {"type": "interval", "every_minutes": 30}}},
          "execution": {"ui_exclusive": true, "timeout_minutes": 10, "max_attempts": 3, "verification": "final"},
          "ui": {"mode": "auto"},
          "x-custom": {"anything": true}
        }
    """.trimIndent()

    private fun validate(text: String): ManifestValidator.Result {
        val (manifest, result) = ManifestValidator.validateJson(text)
        return result
    }

    private fun rules(result: ManifestValidator.Result) = result.errors.map { it.rule }.toSet()

    @Test
    fun `valid manifest passes with no errors`() {
        val result = validate(good)
        assertTrue("expected no errors: ${result.errors}", result.ok)
    }

    @Test
    fun `valid manifest parses into the host model`() {
        val (manifest, result) = ManifestValidator.validateJson(good)
        assertTrue(result.ok)
        assertNotNull(manifest)
        assertEquals("com.example.press", manifest!!.id)
        assertEquals(2, manifest.needs.capabilities.size)
        assertEquals("20", (manifest.configSchema!!["properties"]!!.jsonObject["wait_seconds"]!!.jsonObject["default"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }

    @Test
    fun `missing required top-level keys`() {
        val broken = """{"schema": 1, "id": "com.example.a", "name": "A"}"""
        val r = validate(broken)
        assertTrue("PATTERN" in rules(r)) // version missing → PATTERN
        assertTrue("REQUIRED" in rules(r)) // runtime missing → REQUIRED
        assertFalse(r.ok)
    }

    @Test
    fun `bad id pattern is rejected`() {
        val r = validate(good.replace("\"com.example.press\"", "\"Not-An-Id\""))
        assertTrue("PATTERN" in rules(r))
    }

    @Test
    fun `bad semver is rejected`() {
        val r = validate(good.replace("\"1.2.3-rc.1\"", "\"1.2\""))
        assertTrue("PATTERN" in rules(r))
    }

    @Test
    fun `python runtime needs a py entry`() {
        val r = validate(good.replace("\"main.py\"", "\"flow.yaml\""))
        assertTrue("PATTERN" in rules(r))
    }

    @Test
    fun `flow runtime needs yaml or json entry`() {
        val flow = good
            .replace("\"type\": \"python\"", "\"type\": \"flow\"")
            .replace("\"main.py\"", "\"flow.yaml\"")
        assertTrue(validate(flow).ok)
        val wrong = flow.replace("\"flow.yaml\"", "\"main.py\"")
        assertTrue("PATTERN" in rules(validate(wrong)))
    }

    @Test
    fun `unknown top-level key is rejected but x- extensions pass`() {
        val r = validate(good.replace("\"x-custom\"", "\"custom\""))
        assertTrue("UNKNOWN_KEY" in rules(r))
        assertTrue(validate(good).ok)
    }

    @Test
    fun `schema newer than host is HOST_TOO_OLD`() {
        val r = validate(good.replace("\"schema\": 1", "\"schema\": 2"))
        assertTrue("HOST_TOO_OLD" in rules(r))
    }

    @Test
    fun `absolute or parent-traversing entry is rejected`() {
        val abs = validate(good.replace("\"main.py\"", "\"/etc/passwd.py\""))
        assertTrue("PATH" in rules(abs))
        val rel = validate(good.replace("\"main.py\"", "\"../evil.py\""))
        assertTrue("PATH" in rules(rel))
    }

    @Test
    fun `declarative ui requires a file`() {
        val r = validate(good.replace("\"ui\": {\"mode\": \"auto\"}", "\"ui\": {\"mode\": \"declarative\"}"))
        assertTrue("REQUIRED" in rules(r))
        val withFile = good.replace("\"ui\": {\"mode\": \"auto\"}", "\"ui\": {\"mode\": \"declarative\", \"file\": \"ui/main.json\"}")
        assertTrue(validate(withFile).ok)
        val fileOnAuto = good.replace("\"ui\": {\"mode\": \"auto\"}", "\"ui\": {\"mode\": \"auto\", \"file\": \"ui/main.json\"}")
        assertTrue("FORBIDDEN" in rules(validate(fileOnAuto)))
    }

    @Test
    fun `execution bounds are enforced`() {
        val zero = validate(good.replace("\"timeout_minutes\": 10", "\"timeout_minutes\": 0"))
        assertTrue("MINIMUM" in rules(zero))
        val tooBig = validate(good.replace("\"max_attempts\": 3", "\"max_attempts\": 99"))
        assertTrue("MAXIMUM" in rules(tooBig))
        val badVerification = validate(good.replace("\"verification\": \"final\"", "\"verification\": \"sometimes\""))
        assertTrue("ENUM" in rules(badVerification))
        val idleTooLow = validate(good.replace("\"verification\": \"final\"", "\"verification\": \"final\", \"idle_timeout_seconds\": 1"))
        assertTrue("MINIMUM" in rules(idleTooLow))
    }

    @Test
    fun `config schema enforces the objectSchema subset`() {
        val badType = good.replace("\"wait_seconds\": {\"type\": \"integer\"", "\"wait_seconds\": {\"type\": \"password\"")
        assertTrue("ENUM" in rules(validate(badType)))
        val badUiHint = good.replace("\"x-ui\": {\"widget\": \"app-picker\"}", "\"x-ui\": []")
        assertTrue("TYPE" in rules(validate(badUiHint)))
        val secretNotBool = good.replace("\"x-secret\": true", "\"x-secret\": \"yes\"")
        assertTrue("TYPE" in rules(validate(secretNotBool)))
        // profile schema as a non-object (array) is a TYPE error
        val asArray = good.replace(
            "\"profile_schema\": {\"type\": \"object\", \"properties\": {\"password\": {\"type\": \"string\", \"x-secret\": true}}}",
            "\"profile_schema\": []",
        )
        assertTrue("TYPE" in rules(validate(asArray)))
        assertTrue(validate(good).ok) // sanity: the original still passes
    }

    @Test
    fun `needs entries are validated`() {
        val badCap = good.replace("\"id\": \"device.app.launch\"", "\"id\": \"NOT_A_CAP\"")
        assertTrue("PATTERN" in rules(validate(badCap)))
        val extraKey = good.replace("\"id\": \"device.app.launch\"}", "\"id\": \"device.app.launch\", \"oops\": 1}")
        assertTrue("UNKNOWN_KEY" in rules(validate(extraKey)))
        val badApp = good.replace("\"min_version_code\": 12", "\"min_version_code\": \"12\"")
        assertTrue("TYPE" in rules(validate(badApp)))
    }

    @Test
    fun `malformed json produces a JSON_MALFORMED error not an exception`() {
        val (manifest, r) = ManifestValidator.validateJson("{ not json")
        assertEquals(null, manifest)
        assertFalse(r.ok)
        assertEquals("JSON_MALFORMED", r.errors.first().rule)
    }

    @Test
    fun `all sample manifests validate`() {
        val samplesRoots = listOf(
            "samples/hello", "samples/press-button", "samples/settings-reader",
            "samples/http-check", "samples/press-button-flow",
        )
        val dir = System.getProperty("user.dir")
        var checked = 0
        for (sample in samplesRoots) {
            val file = java.io.File(dir, "$sample/task.json")
            if (!file.isFile) continue // samples live at repo root when tests run from there
            val (manifest, result) = ManifestValidator.validateJson(file.readText())
            assertTrue("$sample: ${result.errors}", result.ok)
            assertNotNull(manifest)
            checked += 1
        }
        assertTrue("no samples found from $dir", checked >= 3)
    }
}
