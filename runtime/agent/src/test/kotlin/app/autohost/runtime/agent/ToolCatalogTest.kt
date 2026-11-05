package app.autohost.runtime.agent

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P13-09: the catalog snapshot — the byte-stable allowlist is the model's whole surface. */
class ToolCatalogTest {

    private val catalog = ToolCatalog.load()

    @Test
    fun `given the generated catalog when loaded then the Stage-A tool set is exactly as documented`() {
        val expected = setOf(
            "ui_snapshot", "ui_find", "ui_wait_for", "ui_wait_for_settle",
            "ui_click", "ui_long_click", "ui_set_text", "ui_scroll_until", "ui_press", "ui_hide_keyboard",
            "app_launch", "app_foreground", "app_query", "app_wait_foreground",
            "ask_user", "finish", "todo_write", "wait",
        )
        assertEquals(expected, catalog.names())
    }

    @Test
    fun `given the excluded tools when looked up then they do not exist for the model`() {
        for (name in listOf("ui_tap", "ui_swipe", "ui_dump", "app_list", "app_stop", "device_shell", "secrets_get")) {
            assertNull("excluded tool leaked into the catalog: $name", catalog.find(name))
        }
    }

    @Test
    fun `given click when inspected then the arg whitelist carries no strategy or secret knobs`() {
        val args = catalog.find("ui_click")!!.args
        val argNames = args.keys
        assertFalse("strategy must never reach the model", "strategy" in argNames)
        assertFalse("retry_if_no_change must never reach the model", "retry_if_no_change" in argNames)
        assertFalse("secret must never reach the model", "secret" in argNames)
    }

    @Test
    fun `given the wire specs when generated then every entry is a function with object parameters`() {
        val specs = catalog.wireSpecs()
        assertEquals(catalog.names().size, specs.size)
        for (spec in specs) {
            val fn = spec.jsonObject["function"]!!.jsonObject
            assertEquals("function", spec.jsonObject["type"]!!.jsonPrimitive.content)
            assertTrue(fn["parameters"]!!.jsonObject.containsKey("properties"))
            assertEquals("false", fn["parameters"]!!.jsonObject["additionalProperties"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `given the host tools when inspected then they declare no capability`() {
        for (name in listOf("finish", "todo_write", "wait")) {
            assertNull(catalog.find(name)!!.capability)
        }
        assertNotNull(catalog.find("ui_click")!!.capability)
    }

    @Test
    fun `given ui_press when inspected then only back is enum-allowed`() {
        val key = catalog.find("ui_press")!!.args["key"]!!.jsonObject
        val allowed = key["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("back"), allowed)
    }
}
