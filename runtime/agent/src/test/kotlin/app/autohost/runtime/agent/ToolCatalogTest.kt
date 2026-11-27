package app.autohost.runtime.agent

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
            "run_task", "task_status",
            "ask_user", "finish", "todo_write", "wait",
        )
        assertEquals(expected, catalog.names())
    }

    @Test
    fun `given run_task when inspected then it starts a saved task through tasks run, from the run's own task list only`() {
        val tool = catalog.find("run_task")!!
        assertEquals("tasks.run", tool.capability)
        assertEquals("run", tool.method)
        assertEquals("tasks.run", tool.permission)
        assertEquals("run_tasks", tool.args["task"]!!.jsonObject["enum_of"]!!.jsonPrimitive.content)
        assertEquals("120000", tool.args["timeout_ms?"]!!.jsonObject["max"]!!.jsonPrimitive.content)
        assertEquals("8", tool.args["inputs?"]!!.jsonObject["pairs"]!!.jsonPrimitive.content)
        // the model never sees the broker's other knobs: no profile, no free-form args object (its values go in `inputs`)
        assertEquals(setOf("task", "inputs?", "timeout_ms?"), tool.args.keys)
    }

    @Test
    fun `given task_status when inspected then it is a host tool that takes a run id and a bounded wait`() {
        val tool = catalog.find("task_status")!!
        assertNull("a host tool has no broker capability", tool.capability)
        assertNull(tool.method)
        assertEquals(setOf("run_id", "timeout_ms?"), tool.args.keys)
        assertEquals("120000", tool.args["timeout_ms?"]!!.jsonObject["max"]!!.jsonPrimitive.content)
        // the model is told which name to hand over: the field run_task's own result calls run_id
        assertTrue(tool.description, tool.description.contains("run_id"))
        assertTrue(catalog.find("run_task")!!.description.contains("task_status"))
    }

    @Test
    fun `given a run with no saved tasks when the task tools are hidden then neither run_task nor task_status is left`() {
        assertEquals(setOf("run_task", "task_status"), ToolPolicy.TASK_TOOLS)
        val trimmed = catalog.without(ToolPolicy.TASK_TOOLS)
        assertEquals(catalog.names() - ToolPolicy.TASK_TOOLS, trimmed.names())
        assertNull(trimmed.find("task_status"))
        assertNull(trimmed.find("run_task"))
    }

    @Test
    fun `given ask_user when inspected then choices is a list of at most four short texts, which is what the prompt screen reads`() {
        val tool = catalog.find("ask_user")!!
        assertEquals("ui.prompt", tool.capability)
        assertEquals("ask", tool.method)
        assertEquals("4", tool.args["choices?"]!!.jsonObject["list"]!!.jsonPrimitive.content)
        assertEquals(setOf("message", "kind", "choices?"), tool.args.keys)
    }

    @Test
    fun `given a catalog without run_task when listed then the model sees every other tool and not that one`() {
        val trimmed = catalog.without(setOf("run_task"))
        assertEquals(catalog.names() - "run_task", trimmed.names())
        assertNull(trimmed.find("run_task"))
        assertEquals(trimmed.names().size, trimmed.specs().size)
        assertEquals(trimmed.names().size, trimmed.wireSpecs().size)
        assertTrue("the original is untouched", "run_task" in catalog.names())
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
            val parameters = fn["parameters"]!!.jsonObject
            assertEquals("object", parameters["type"]!!.jsonPrimitive.content)
            assertTrue(parameters.containsKey("properties"))
            // INT-A1: no additionalProperties (Gemini's own API refuses the key); ArgValidator refuses unknown arguments
            assertFalse(parameters.containsKey("additionalProperties"))
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
