package app.autohost.runtime.agent

import app.autohost.core.common.AhResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P13-10 (AGENT_RUNTIME §7.3): the model's observation — generation-local ids and the ViewIndex. */
class AgentViewTest {

    private val view = AgentView(screenWidth = 1080, screenHeight = 2400)

    private fun node(
        ref: String, cls: String, id: String, text: String,
        flags: String = "clickable", bounds: String = "[40,812,1040,940]", pkg: String = "com.x",
    ): JsonObject = Json.parseToJsonElement(
        """{"ref":"$ref","pkg":"$pkg","class":"android.widget.$cls","id":"$id","text":"$text","desc":"","bounds":$bounds,"flags":[${flags.split(",").joinToString(",") { "\"$it\"" }}],"index":0}""",
    ).jsonObject

    private fun snapshot(vararg nodes: JsonObject, pkg: String = "com.x", keyboard: String = "hidden") =
        Json.parseToJsonElement(
            """{"package":"$pkg","keyboard":"$keyboard","node_count":${nodes.size},"nodes":[${nodes.joinToString(",") { it.toString() }}]}""",
        ).jsonObject

    @Test
    fun `given a snapshot when rendered then the header and lines use generation-local ids`() {
        val rendered = view.render(snapshot(node("ref-1", "Switch", "dnd", "Do Not Disturb", flags = "clickable,checked")), generation = 7)
        val lines = rendered.text.split("\n")
        assertEquals("SCREEN gen=7 app=com.x keyboard=hidden shown=1/1 truncated=no", lines[0])
        assertTrue(lines[1].startsWith("[1] Switch "))
        assertTrue(lines[1].contains("\"Do Not Disturb\""))
        assertTrue(lines[1].contains("{clickable,checked}"))
        assertTrue(lines[1].endsWith("(40,812,1040,940)"))
    }

    @Test
    fun `given a rendered view when indexed then n maps to ref, pkg, flags and bounds`() {
        val rendered = view.render(snapshot(node("ref-1", "Switch", "dnd", "Do Not Disturb")), generation = 1)
        val entry = rendered.index[1]!!
        assertEquals("ref-1", entry.ref)
        assertEquals("com.x", entry.pkg)
        assertTrue("clickable" in entry.flags)
        assertEquals(812, entry.bounds.t)
    }

    @Test
    fun `given a password node when rendered then the value is the password marker with no value`() {
        val rendered = view.render(snapshot(node("ref-1", "EditText", "pw", "", flags = "clickable,editable,password")), generation = 1)
        assertTrue(rendered.text.contains("{password}"))
    }

    @Test
    fun `given the keep rule when applied then tiny and off-screen nodes are dropped (the CompactDump rule, not a copy)`() {
        val tiny = node("ref-2", "View", "t", "", bounds = "[0,0,3,3]")
        val offscreen = node("ref-3", "View", "o", "", bounds = "[0,3000,100,3100]")
        val kept = node("ref-1", "Button", "b", "Go")
        val rendered = view.render(snapshot(tiny, offscreen, kept), generation = 1)
        assertEquals(1, rendered.shown)
        assertEquals(3, rendered.total)
        assertEquals("ref-1", rendered.index[1]!!.ref)
    }

    @Test
    fun `given more nodes than the cap when rendered then shown is capped and truncated=yes`() {
        val nodes = (1..150).map { i -> node("ref-$i", "Button", "b$i", "Item $i", bounds = "[0,${i * 15},100,${i * 15 + 10}]") }
        val rendered = view.render(snapshot(*nodes.toTypedArray()), generation = 1)
        assertEquals(120, rendered.shown)
        assertTrue(rendered.truncated)
        assertEquals(150, rendered.total)
    }

    @Test
    fun `given a node id from the previous generation when resolved then STALE_OBSERVATION`() {
        // generation 1 has ids [1] and [2]; generation 2 only has [1] — so [2] existed before and is gone now
        val gen1 = view.render(
            snapshot(node("ref-1", "Button", "b", "Go"), node("ref-2", "Button", "c", "More")),
            generation = 1,
        ).index
        val gen2 = view.render(snapshot(node("ref-9", "Button", "b2", "Other")), generation = 2).index
        val verdict = AgentView.resolveForCall(gen2, gen1, 2)
        assertTrue(verdict is AhResult.Err)
        assertEquals("STALE_OBSERVATION", (verdict as AhResult.Err).error.reason)
    }

    @Test
    fun `given an id that never existed when resolved then INVALID_ARGS (never a guess)`() {
        val gen1 = view.render(snapshot(node("ref-1", "Button", "b", "Go")), generation = 1).index
        val gen2 = view.render(snapshot(node("ref-9", "Button", "b2", "Other")), generation = 2).index
        val verdict = AgentView.resolveForCall(gen2, gen1, 5)
        assertTrue(verdict is AhResult.Err)
        assertEquals("INVALID_ARGS", (verdict as AhResult.Err).error.code)
    }

    @Test
    fun `given the current generation when resolved then the entry resolves`() {
        val gen2 = view.render(snapshot(node("ref-9", "Button", "b2", "Other")), generation = 2).index
        val verdict = AgentView.resolveForCall(gen2, null, 1)
        assertTrue(verdict is AhResult.Ok)
        assertEquals("ref-9", verdict.getOrNull()!!.ref)
    }

    @Test
    fun `given a checked state change when fingerprinted then the fingerprint changes (state-aware, the A2 rule)`() {
        val off = snapshot(node("ref-1", "Switch", "dnd", "DND", flags = "clickable"))
        val on = snapshot(node("ref-1", "Switch", "dnd", "DND", flags = "clickable,checked"))
        assertFalse(view.fingerprint(off) == view.fingerprint(on))
    }

    @Test
    fun `given only the focus moved when fingerprinted then the fingerprint is the same (focused excluded, the RM-A4 rule)`() {
        val a = snapshot(
            node("ref-1", "EditText", "a", ""),
            node("ref-2", "EditText", "b", "", flags = "clickable,focused"),
        )
        val b = snapshot(
            node("ref-1", "EditText", "a", "", flags = "clickable,focused"),
            node("ref-2", "EditText", "b", ""),
        )
        assertEquals(view.fingerprint(a), view.fingerprint(b))
    }
}
