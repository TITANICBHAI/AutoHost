package app.autohost.runtime.flow

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6-07 (written; awaiting Gradle): the recorder core — selector stability ranking, text
 * coalescing, secret placeholders, label/sidecar consistency and the round trip through
 * [FlowParser] (a recorded flow document must parse byte-for-byte like a hand-written one).
 */
class RecorderTest {

    private fun node(
        id: String = "", text: String = "", desc: String = "", cls: String = "android.widget.Button",
        bounds: List<Int> = listOf(0, 0, 100, 100), editable: Boolean = false, password: Boolean = false,
        clickable: Boolean = true,
    ) = RecordedNode(id, text, desc, cls, bounds, editable, password, clickable)

    private fun screen(vararg nodes: RecordedNode, pkg: String = "com.example.app", activity: String = "MainActivity") =
        RecordedScreen(pkg, activity, nodes.toList())

    private fun click(node: RecordedNode, screen: RecordedScreen, ts: Long = 0) =
        RecordedEvent(RecordedKind.CLICK, node, screen, ts)

    @Test
    fun `unique resource-id wins the ranking`() {
        val target = node(id = "com.example.app/ok", text = "OK")
        val result = Recorder.record(listOf(click(target, screen(node(id = "other/row"), target))))
        assertEquals(1, result.steps.size)
        val step = result.steps[0]
        assertEquals("tapOn", step.command)
        assertEquals("id", step.selectorRank)
        assertFalse(step.needsConfirmation)
        assertEquals("com.example.app/ok", step.body["id"]!!.jsonPrimitive.content)
        // label lives inside the body (modifiers are body keys) and matches the sidecar entry
        assertEquals("tap_1", step.label)
        assertEquals("tap_1", step.body["label"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a repeated id falls through to unique text`() {
        val target = node(id = "com.example.app/row", text = "Second")
        val row1 = node(id = "com.example.app/row", text = "First")
        val result = Recorder.record(listOf(click(target, screen(row1, target))))
        assertEquals("text", result.steps[0].selectorRank)
        assertEquals("Second", result.steps[0].body["text"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `unique desc ranks between id and text`() {
        val target = node(text = "OK", desc = "confirm action", id = "")
        val result = Recorder.record(listOf(click(target, screen(node(text = "other"), target))))
        assertEquals("desc", result.steps[0].selectorRank)
    }

    @Test
    fun `non-unique text with a unique anchor becomes a relational selector`() {
        val anchor = node(text = "Username", cls = "android.widget.TextView", bounds = listOf(0, 0, 300, 60))
        val field = node(text = "", cls = "android.widget.EditText", bounds = listOf(0, 70, 300, 130), clickable = false)
        val otherField = node(text = "", cls = "android.widget.EditText", bounds = listOf(0, 700, 300, 760), clickable = false)
        // The second EditText is below the anchor's column too only if it overlaps horizontally —
        // it does, so the recorder must see two candidates and refuse the relational rank.
        val result = Recorder.record(listOf(click(field, screen(anchor, field, otherField), ts = 5)))
        assertEquals("bounds", result.steps[0].selectorRank)
        assertTrue(result.steps[0].needsConfirmation)
        val bounds = result.steps[0].body["bounds"]!!.jsonObject
        assertEquals(0, bounds["left"]!!.jsonPrimitive.content.toInt())
        assertEquals(70, bounds["top"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `lone field below a unique label gets the relational rank`() {
        val anchor = node(text = "Username", cls = "android.widget.TextView", bounds = listOf(0, 0, 300, 60))
        val field = node(text = "", cls = "android.widget.EditText", bounds = listOf(0, 70, 300, 130), clickable = false)
        val result = Recorder.record(listOf(click(field, screen(anchor, field), ts = 5)))
        assertEquals("relational", result.steps[0].selectorRank)
        assertFalse(result.steps[0].needsConfirmation)
        assertEquals("android.widget.EditText", result.steps[0].body["cls"]!!.jsonPrimitive.content)
        val below = result.steps[0].body["below"]!!.jsonObject
        assertEquals("Username", below["text"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `bounds-only fallback is flagged for confirmation`() {
        val target = node(text = "", desc = "", id = "", cls = "android.view.View", bounds = listOf(10, 20, 110, 120))
        val result = Recorder.record(listOf(click(target, screen(target))))
        assertEquals("bounds", result.steps[0].selectorRank)
        assertTrue(result.steps[0].needsConfirmation)
        assertEquals(20, result.steps[0].body["bounds"]!!.jsonObject["top"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `consecutive typing on the same field coalesces to one inputText`() {
        val field = node(id = "com.example.app/search", cls = "android.widget.EditText", editable = true)
        val typing = listOf(
            RecordedEvent(RecordedKind.TEXT, field, screen(field), 0, text = "h"),
            RecordedEvent(RecordedKind.TEXT, field, screen(field), 50, text = "he"),
            RecordedEvent(RecordedKind.TEXT, field, screen(field), 100, text = "hello"),
        )
        val result = Recorder.record(typing)
        assertEquals(1, result.steps.size)
        assertEquals("inputText", result.steps[0].command)
        assertEquals("hello", result.steps[0].body["text"]!!.jsonPrimitive.content)
        assertEquals("com.example.app/search", result.steps[0].body["into"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `password input never stores text - it becomes a secret placeholder`() {
        val field = node(id = "com.example.app/pw", cls = "android.widget.EditText", editable = true, password = true)
        val result = Recorder.record(
            listOf(RecordedEvent(RecordedKind.TEXT, field, screen(field), 0, text = "hunter2")),
        )
        val body = result.steps[0].body
        assertEquals("\${secret.INPUT_1}", body["text"]!!.jsonPrimitive.content)
        assertEquals(true, body["secret"]!!.jsonPrimitive.content == "true")
        assertEquals(listOf("INPUT_1"), result.steps[0].secretNames)
        assertFalse(result.steps[0].let { it.body["text"]!!.jsonPrimitive.content.contains("hunter2") })
    }

    @Test
    fun `keys and scrolls become their commands with no selector work`() {
        val empty = screen(node(text = "x"))
        val result = Recorder.record(
            listOf(
                RecordedEvent(RecordedKind.SCROLL, null, empty, 0, direction = "up"),
                RecordedEvent(RecordedKind.KEY, null, empty, 10, key = "back"),
                RecordedEvent(RecordedKind.KEY, null, empty, 20, key = "meta_shift_left"),
            ),
        )
        assertEquals(2, result.steps.size) // unknown key dropped with a warning
        assertEquals("scroll", result.steps[0].command)
        assertEquals("up", result.steps[0].body["direction"]!!.jsonPrimitive.content)
        assertEquals("pressKey", result.steps[1].command)
        assertEquals("back", result.steps[1].body["key"]!!.jsonPrimitive.content)
        assertTrue(result.warnings.any { it.contains("meta_shift_left") })
    }

    @Test
    fun `sidecar labels match the step labels and pre-states carry semantic keys`() {
        val target = node(id = "com.example.app/ok", text = "OK")
        val result = Recorder.record(listOf(click(target, screen(target), ts = 42)))
        val sidecar = result.sidecar("my-flow")
        assertEquals("my-flow", sidecar.flowName)
        assertEquals(1, sidecar.steps.size)
        assertEquals("tap_1", sidecar.steps[0].label)
        assertEquals("id", sidecar.steps[0].selectorRank)
        assertEquals(listOf("Button|com.example.app/ok|OK|"), sidecar.steps[0].snapshot.keys)
        assertEquals("com.example.app", sidecar.steps[0].snapshot.packageName)
    }

    @Test
    fun `recorded flow document parses with the real FlowParser`() {
        val target = node(id = "com.example.app/ok", text = "OK")
        val field = node(id = "com.example.app/pw", cls = "android.widget.EditText", editable = true, password = true)
        val events = listOf(
            RecordedEvent(RecordedKind.TEXT, field, screen(field), 0, text = "hunter2"),
            click(target, screen(target, node(text = "other")), ts = 100),
        )
        val result = Recorder.record(events)
        val doc = Recorder.toFlowDocument(result, "recorded")
        val parsed = FlowParser.fromJson(doc) // throws on any recorder/flow-format drift
        assertEquals(2, parsed.steps.size)
        assertEquals("inputText", parsed.steps[0].command)
        assertEquals("input_1", parsed.steps[0].label)
        assertEquals("tapOn", parsed.steps[1].command)
        assertEquals(true, parsed.steps[1].body["waitToSettle"]!!.jsonPrimitive.content == "true")
        // the sidecar round-trips too
        val sidecar = result.sidecar("recorded")
        val restored = RecordingSidecar.fromJson(sidecar.toJson())
        assertEquals(sidecar, restored)
    }
}
