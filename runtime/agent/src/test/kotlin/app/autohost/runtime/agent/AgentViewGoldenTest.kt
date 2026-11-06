package app.autohost.runtime.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * RM-C7 (P13-10): the AgentView render golden vectors — the REAL `AgentView` runs against
 * `testing/golden/agent_view.json`, pinning the §7.3 render contract as data instead of inline
 * asserts: generation-local ids, the CompactDump keep rule, the flag order, the `{password}`
 * marker, the caps and the state-aware fingerprint. If a render change is deliberate, update the
 * golden file in the same commit and say so in the tracker row.
 */
class AgentViewGoldenTest {

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("repo file not found from ${System.getProperty("user.dir")}: $relative")
    }

    private val vectors = Json.parseToJsonElement(repoFile("testing/golden/agent_view.json").readText()).jsonObject

    @Test
    fun `every case renders exactly the golden text and fingerprint`() {
        for (case in vectors["cases"]!!.jsonArray) {
            val c = case.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val view = AgentView(
                screenWidth = vectors["screen_size"]!!.jsonArray[0].jsonPrimitive.content.toInt(),
                screenHeight = vectors["screen_size"]!!.jsonArray[1].jsonPrimitive.content.toInt(),
                maxNodes = c["max_nodes"]?.jsonPrimitive?.content?.toInt() ?: 120,
            )
            val rendered = view.render(c["observation"]!!.jsonObject, c["generation"]!!.jsonPrimitive.content.toInt())
            assertEquals("$name: render", c["expected_render"]!!.jsonPrimitive.content, rendered.text)
            assertEquals("$name: fingerprint", c["expected_fingerprint"]!!.jsonPrimitive.content, view.fingerprint(c["observation"]!!.jsonObject))
        }
    }

    @Test
    fun `every case ViewIndex maps the golden ids to refs, pkgs, flags and bounds`() {
        for (case in vectors["cases"]!!.jsonArray) {
            val c = case.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val view = AgentView(
                screenWidth = vectors["screen_size"]!!.jsonArray[0].jsonPrimitive.content.toInt(),
                screenHeight = vectors["screen_size"]!!.jsonArray[1].jsonPrimitive.content.toInt(),
                maxNodes = c["max_nodes"]?.jsonPrimitive?.content?.toInt() ?: 120,
            )
            val index = view.render(c["observation"]!!.jsonObject, c["generation"]!!.jsonPrimitive.content.toInt()).index
            val expected = c["expected_index"]!!.jsonObject
            assertEquals("$name: index size", expected.size, index.size)
            for ((n, entryJson) in expected) {
                val entry = index[n.toInt()] ?: throw AssertionError("$name: missing id [$n]")
                val e = entryJson.jsonObject
                assertEquals("$name [$n] ref", e["ref"]!!.jsonPrimitive.content, entry.ref)
                assertEquals("$name [$n] pkg", e["pkg"]!!.jsonPrimitive.content, entry.pkg)
                val flags = (e["flags"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
                assertEquals("$name [$n] flags", flags, entry.flags)
                val bounds = (e["bounds"] as JsonArray).map { it.jsonPrimitive.content.toInt() }
                assertEquals("$name [$n] bounds", bounds, listOf(entry.bounds.l, entry.bounds.t, entry.bounds.r, entry.bounds.b))
            }
        }
    }
}
