package app.autohost.runtime.agent

import app.autohost.core.common.AhJson
import app.autohost.core.model.Bounds
import app.autohost.core.model.Selector
import app.autohost.core.model.SelectorEngine
import app.autohost.core.model.UiNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * INT-A5: the model names an element by the words it shows; the phone service takes a structured selector. The
 * conversion is checked against the SAME engine the phone service uses to find elements on the screen.
 */
class SelectorTextTest {

    private fun node(index: Int, text: String = "", desc: String = "") = UiNode(
        index = index, parent = -1, depth = 0, pkg = "com.example.app", cls = "android.widget.Button",
        id = "", text = text, desc = desc, bounds = Bounds(0, index * 60, 100, index * 60 + 50), flags = setOf("clickable"),
    )

    private val screen = listOf(
        node(0, text = "Next page"),
        node(1, desc = "Next"),
        node(2, text = "Cancel"),
    )

    /** The selector the phone service is sent for [words], as JSON. */
    private fun sentFor(words: String): JsonObject =
        SelectorText.forPhoneService(buildJsonObject { put("selector", words) })["selector"] as JsonObject

    /** What the phone service does with it: decode the JSON, then search the screen. Returns the indexes found. */
    private fun found(sent: JsonObject): Set<Int> {
        val selector = AhJson.json.decodeFromJsonElement(Selector.serializer(), sent)
        return SelectorEngine.findAll(screen, selector).map { it.index }.toSet()
    }

    @Test fun `words find an element by its text and by its description, whatever the case`() {
        assertEquals(setOf(0, 1), found(sentFor("NEXT")))
    }

    @Test fun `part of the text is enough`() {
        assertEquals(setOf(0), found(sentFor("page")))
    }

    @Test fun `words that no element shows find nothing`() {
        assertTrue(found(sentFor("Upload")).isEmpty())
    }

    @Test fun `spaces around the words do not matter`() {
        assertEquals(setOf(2), found(sentFor("  Cancel ")))
    }

    @Test fun `the structured form survives the wire unchanged`() {
        val sent = sentFor("Next")
        val back = AhJson.json.decodeFromJsonElement(Selector.serializer(), sent)
        assertEquals(SelectorText.toSelector("Next"), back)
    }

    @Test fun `selector and expect both become structured and other arguments stay as they are`() {
        val out = SelectorText.forPhoneService(
            buildJsonObject {
                put("selector", "Next")
                put("expect", "Saved")
                put("limit", "3")
            },
        )
        assertTrue(out["selector"] is JsonObject)
        assertTrue(out["expect"] is JsonObject)
        assertEquals("3", (out["limit"] as JsonPrimitive).content)
    }

    @Test fun `empty words are left out`() {
        val out = SelectorText.forPhoneService(
            buildJsonObject {
                put("selector", "  ")
                put("expect", "")
                put("limit", "3")
            },
        )
        assertFalse(out.containsKey("selector"))
        assertFalse(out.containsKey("expect"))
        assertEquals("3", (out["limit"] as JsonPrimitive).content)
    }

    @Test fun `arguments with no selector at all are returned as they are`() {
        val args = buildJsonObject { put("text", "hello") }
        assertEquals(args, SelectorText.forPhoneService(args))
    }

    @Test fun `a value that is already structured is not touched`() {
        val structured = buildJsonObject { put("op", "and") }
        val args = buildJsonObject { put("selector", structured) }
        assertEquals(structured, SelectorText.forPhoneService(args)["selector"])
    }
}
