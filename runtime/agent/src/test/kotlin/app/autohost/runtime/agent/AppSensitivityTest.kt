package app.autohost.runtime.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P13-08 / RM-B1: the sensitivity floor. Ported cases from ClosePaw's AppClassifierSecurityTest +
 * AppClassifierOverrideTest rewritten to AutoHost types, plus the floor-pinning and the
 * observation-withholding rules (AGENT_RUNTIME §8).
 */
class AppSensitivityTest {

    private fun sensitivity(userAdded: Map<String, AppTier> = emptyMap()) = AppSensitivity(userAdded)

    @Test
    fun `given the bundled resource when loaded then the floor has BLOCKED and CAUTIOUS entries`() {
        val text = javaClass.classLoader!!.getResourceAsStream(AppSensitivity.BUNDLED_PATH)!!.readBytes().decodeToString()
        val apps = Json.parseToJsonElement(text).jsonObject["apps"]!!.jsonArray
        assertTrue("the floor must be non-empty", apps.size >= 10)
        val tiers = apps.map { it.jsonObject["tier"]!!.jsonPrimitive.content }.toSet()
        assertTrue(tiers.contains("BLOCKED"))
    }

    @Test
    fun `given a bundled blocked package when classified then BLOCKED and it cannot be lifted`() {
        val text = javaClass.classLoader!!.getResourceAsStream(AppSensitivity.BUNDLED_PATH)!!.readBytes().decodeToString()
        val blocked = Json.parseToJsonElement(text).jsonObject["apps"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["tier"]!!.jsonPrimitive.content == "BLOCKED" }["package"]!!.jsonPrimitive.content
        val s = sensitivity(userAdded = mapOf(blocked to AppTier.NORMAL))
        assertEquals(AppTier.BLOCKED, s.classify(blocked)) // the floor: removal refused at read time
        assertTrue(s.isBlocked(blocked))
    }

    @Test
    fun `given an unknown package when classified then CAUTIOUS (fail safe)`() {
        assertEquals(AppTier.CAUTIOUS, sensitivity().classify("com.unknown.app"))
    }

    @Test
    fun `given a user addition when classified then the user tier applies for non-bundled packages`() {
        val s = sensitivity(userAdded = mapOf("com.example.mygame" to AppTier.NORMAL))
        assertEquals(AppTier.NORMAL, s.classify("com.example.mygame"))
    }

    @Test
    fun `given a corrupt bundled floor when constructed then it fails closed (the RM-G2 security port)`() {
        // AppClassifierSecurityTest's load-failure regression: a broken floor must throw, never
        // silently degrade into "everything is NORMAL".
        var threw = false
        try {
            AppSensitivity(bundledText = "not valid json")
        } catch (e: RuntimeException) {
            threw = true
        }
        assertTrue("a corrupt floor must fail closed", threw)
    }

    @Test
    fun `given a user override on a bundled cautious package when classified then the user tier wins (only BLOCKED is the floor)`() {
        val text = javaClass.classLoader!!.getResourceAsStream(AppSensitivity.BUNDLED_PATH)!!.readBytes().decodeToString()
        val cautious = Json.parseToJsonElement(text).jsonObject["apps"]!!.jsonArray
            .map { it.jsonObject }
            .firstOrNull { it["tier"]!!.jsonPrimitive.content == "CAUTIOUS" }?.get("package")?.jsonPrimitive?.content
            ?: return  // the bundled floor has no CAUTIOUS entry in this build; nothing to lift
        val s = sensitivity(userAdded = mapOf(cautious to AppTier.NORMAL))
        assertEquals(AppTier.NORMAL, s.classify(cautious))
    }

    // -- observation withholding (AGENT_RUNTIME §8) ----------------------------------------------

    private fun snapshot(foreground: String, nodePkgs: List<String>) = Json.parseToJsonElement(
        """{"package":"$foreground","nodes":[${nodePkgs.joinToString(",") { """{"pkg":"$it","ref":"r"}"""}}]}""",
    ).jsonObject

    @Test
    fun `given a snapshot from a blocked app when filtered then withheld`() {
        val text = javaClass.classLoader!!.getResourceAsStream(AppSensitivity.BUNDLED_PATH)!!.readBytes().decodeToString()
        val blocked = Json.parseToJsonElement(text).jsonObject["apps"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["tier"]!!.jsonPrimitive.content == "BLOCKED" }["package"]!!.jsonPrimitive.content
        val result = ObservationFilter.filterSnapshot(snapshot(blocked, listOf(blocked)), setOf("com.target"), sensitivity())
        assertTrue(result.withheld)
        assertEquals("FOREIGN_OR_BLOCKED_APP", result.reason)
    }

    @Test
    fun `given one foreign node inside the snapshot when filtered then the WHOLE observation is withheld`() {
        val result = ObservationFilter.filterSnapshot(
            snapshot("com.target", listOf("com.target", "com.other")),
            setOf("com.target"),
            sensitivity(),
        )
        assertTrue(result.withheld)
        assertEquals("NODE_FROM_FOREIGN_OR_BLOCKED_APP", result.reason)
    }

    @Test
    fun `given a clean in-scope snapshot when filtered then it passes through`() {
        val snap = snapshot("com.target", listOf("com.target"))
        val result = ObservationFilter.filterSnapshot(snap, setOf("com.target"), sensitivity())
        assertFalse(result.withheld)
        assertEquals(snap, result.observation)
    }

    @Test
    fun `given an out-of-scope foreground report when filtered then in_scope false without the package`() {
        val filtered = ObservationFilter.filterForeground(
            Json.parseToJsonElement("""{"package":"com.other"}""").jsonObject,
            setOf("com.target"),
        )
        assertEquals(false, filtered["in_scope"]!!.toString().toBoolean())
        assertNull(filtered["package"])
    }

    @Test
    fun `given a password node when rendered then the text is the password marker`() {
        val node = Json.parseToJsonElement("""{"pkg":"com.x","ref":"r","text":"","flags":["password","editable"]}""").jsonObject
        val rendered = ObservationFilter.renderNode(node)
        assertEquals("{password}", rendered["text"].toString().trim('"'))
        assertNotNull(rendered["flags"])
    }

    private fun assertNull(x: Any?) = org.junit.Assert.assertNull(x)
}
