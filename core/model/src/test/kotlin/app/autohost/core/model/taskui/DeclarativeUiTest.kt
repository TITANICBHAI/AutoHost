package app.autohost.core.model.taskui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** P7-01: declarative task-UI document — parse, validate, bind (pure JVM). */
class DeclarativeUiTest {

    private val json = Json

    private fun parse(text: String): DeclarativeUi = DeclarativeUi.parse(json.decodeFromString(text))

    @Test
    fun parses_full_document() {
        val doc = parse(
            """
            {"version": 1, "title": "Demo", "body": [
              {"type": "text", "value": "Hello {{config.name}}", "style": "headline"},
              {"type": "card", "title": "Controls", "children": [
                {"type": "input", "name": "who", "label": "Who", "default": "world"},
                {"type": "toggle", "name": "loud", "label": "Loud"},
                {"type": "select", "name": "mode", "options": ["fast", {"value": "s", "label": "Slow"}], "default": "s"},
                {"type": "button", "label": "Greet", "action": {"type": "call", "handler": "greet",
                     "args": {"n": 2}}}
              ]},
              {"type": "tabs", "items": [{"title": "A", "children": [{"type": "stat", "label": "Runs", "value": "{{run.status}}"}]}]},
              {"type": "table", "columns": ["k", "v"], "rows": [["a", "{{config.name}}"]]},
              {"type": "button", "label": "Start", "style": "outlined", "action": {"type": "run_stage", "name": "go"}},
              {"type": "button", "label": "Wipe", "action": {"type": "confirm", "title": "Sure?",
                   "message": "This resets the counter.", "action": {"type": "call", "handler": "reset"}}},
              {"type": "progress"},
              {"type": "logview"},
              {"type": "profile-picker", "name": "target"}
            ]}
            """,
        )
        assertEquals(1, doc.version)
        assertEquals("Demo", doc.title)
        assertEquals(10, doc.body.size)
        val card = doc.body[1] as UiWidget.Card
        val button = card.children[3] as UiWidget.Button
        val call = button.action as UiAction.Call
        assertEquals("greet", call.handler)
        assertEquals("2", (call.args["n"] as JsonPrimitive).content)
        val select = card.children[2] as UiWidget.Select
        assertEquals(1, select.defaultIndex)
        assertEquals("Slow", select.options[1].label)
        assertTrue((doc.body[6] as UiWidget.Button).action is UiAction.Run)
        val confirm = (doc.body[7] as UiWidget.Button).action as UiAction.Confirm
        assertTrue(confirm.action is UiAction.Call)
    }

    @Test
    fun rejects_unknown_version_type_and_widgets() {
        val e1 = assertThrows(DeclarativeUi.UiDocError::class.java) { parse("""{"body": []}""") }
        assertTrue(e1.message!!.contains("version"))
        assertThrows(DeclarativeUi.UiDocError::class.java) {
            parse("""{"version": 2, "body": [{"type": "text", "value": "x"}]}""")
        }
        val e3 = assertThrows(DeclarativeUi.UiDocError::class.java) {
            parse("""{"version": 1, "body": [{"type": "chart"}]}""")
        }
        assertTrue(e3.message!!.contains("unknown widget"))
        assertThrows(DeclarativeUi.UiDocError::class.java) { parse("""{"version": 1, "body": []}""") }
    }

    @Test
    fun rejects_bad_actions_and_inputs() {
        assertThrows(DeclarativeUi.UiDocError::class.java) {
            parse("""{"version": 1, "body": [{"type": "button", "label": "B"}]}""")
        }
        assertThrows(DeclarativeUi.UiDocError::class.java) {
            parse("""{"version": 1, "body": [{"type": "button", "label": "B", "action": {"type": "call"}}]}""")
        }
        assertThrows(DeclarativeUi.UiDocError::class.java) {
            parse("""{"version": 1, "body": [{"type": "input", "label": "NoName"}]}""")
        }
        val nav = assertThrows(DeclarativeUi.UiDocError::class.java) {
            parse("""{"version": 1, "body": [{"type": "button", "label": "B", "action": {"type": "navigate", "route": "https://evil"}}]}""")
        }
        assertTrue(nav.message!!.contains("route"))
        assertThrows(DeclarativeUi.UiDocError::class.java) {
            parse("""{"version": 1, "body": [{"type": "select", "name": "s", "options": []}]}""")
        }
    }

    @Test
    fun depth_and_size_caps() {
        var inner = """{"type": "text", "value": "leaf"}"""
        repeat(13) { inner = """{"type": "column", "children": [$inner]}""" }
        val e = assertThrows(DeclarativeUi.UiDocError::class.java) {
            parse("""{"version": 1, "body": [$inner]}""")
        }
        assertTrue(e.message!!.contains("nesting"))
    }

    @Test
    fun whole_string_binding_keeps_native_type() {
        val scope = UiBindingScope(
            config = buildJsonObject { put("count", 3); put("name", "demo") },
            run = mapOf("status" to "RUNNING"),
        )
        assertEquals(JsonPrimitive(3), UiBindings.bind("{{config.count}}", scope).value)
        assertEquals(JsonPrimitive("3 items"), UiBindings.bind("{{config.count}} items", scope).value)
        assertEquals(JsonPrimitive("RUNNING"), UiBindings.bind("{{run.status}}", scope).value)
    }

    @Test
    fun unresolved_bindings_warn_and_render_empty() {
        val bound = UiBindings.bind("Value: {{state.missing}}!", UiBindingScope())
        assertEquals("Value: !", (bound.value as JsonPrimitive).content)
        assertEquals(listOf("state.missing"), bound.unresolved)
        // unknown scope roots resolve to nothing too
        assertTrue(UiBindings.bind("{{evil.deep}}", UiBindingScope()).unresolved.isNotEmpty())
    }

    @Test
    fun run_scope_is_whitelisted() {
        val scope = UiBindingScope(run = mapOf("status" to "FAILED"))
        assertEquals(JsonPrimitive("FAILED"), UiBindings.bind("{{run.status}}", scope).value)
        assertTrue(UiBindings.bind("{{run.taskId}}", scope).unresolved.isNotEmpty())
        assertTrue(UiBindings.bind("{{run}}", scope).unresolved.isNotEmpty())
    }

    @Test
    fun input_scope_feeds_calls() {
        val scope = UiBindingScope(
            input = buildJsonObject { put("who", "AutoHost") },
            state = buildJsonObject { put("count", 7) },
        )
        assertEquals(JsonPrimitive("AutoHost"), UiBindings.bind("{{input.who}}", scope).value)
        assertEquals(JsonPrimitive(7), UiBindings.bind("{{state.count}}", scope).value)
    }

    @Test
    fun visibility_evaluates_truthiness() {
        val scope = UiBindingScope(
            config = buildJsonObject { put("on", true); put("off", false); put("empty", ""); put("zero", 0) },
            profile = buildJsonObject { put("ready", true) },
        )
        assertTrue(UiBindings.isVisible(UiExpr("config.on"), scope))
        assertTrue(UiBindings.isVisible(UiExpr("profile.ready"), scope))
        assertTrue(!UiBindings.isVisible(UiExpr("config.off"), scope))
        assertTrue(!UiBindings.isVisible(UiExpr("config.empty"), scope))
        assertTrue(!UiBindings.isVisible(UiExpr("config.zero"), scope))
        assertTrue(!UiBindings.isVisible(UiExpr("state.missing"), scope))
    }

    @Test
    fun table_rows_accept_static_arrays_and_bindings() {
        val static = parse(
            """{"version": 1, "body": [{"type": "table", "columns": ["k"], "rows": [["a"]]}]}""",
        )
        val table = static.body[0] as UiWidget.Table
        assertEquals(listOf(listOf("a")), table.rows)
        assertEquals(null, table.rowsBinding)

        val dynamic = parse(
            """{"version": 1, "body": [{"type": "table", "columns": ["k"], "rows": "{{state.items}}"}]}""",
        )
        val dynamicTable = dynamic.body[0] as UiWidget.Table
        assertEquals("state.items", dynamicTable.rowsBinding)
        assertTrue(dynamicTable.rows.isEmpty())
    }
}
