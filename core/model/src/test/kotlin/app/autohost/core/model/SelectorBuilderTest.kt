package app.autohost.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7-11: the inspector's selector building must round-trip — `SelectorEngine.findAll` on the
 * built selector re-finds exactly the picked node, or the copied selector is a lie.
 */
class SelectorBuilderTest {

    private fun node(
        index: Int,
        parent: Int,
        depth: Int,
        pkg: String = "com.target",
        cls: String = "android.widget.TextView",
        id: String = "",
        text: String = "",
        desc: String = "",
        l: Int = 0,
        t: Int = 0,
        r: Int = 100,
        b: Int = 40,
    ) = UiNode(
        index = index,
        parent = parent,
        depth = depth,
        pkg = pkg,
        cls = cls,
        id = id,
        text = text,
        desc = desc,
        bounds = Bounds(l, t, r, b),
        flags = emptySet(),
    )

    @Test fun `resource id wins when present`() {
        val nodes = listOf(
            node(0, -1, 0, id = "com.target:id/root"),
            node(1, 0, 1, id = "com.target:id/ok", text = "OK"),
        )
        val built = SelectorBuilder.forNode(nodes[1], nodes)
        assertEquals("resource-id", built.basis)
        val found = SelectorEngine.findAll(nodes, built.selector)
        assertEquals(listOf(nodes[1].index), found.map { it.index })
    }

    @Test fun `unique text round trips`() {
        val nodes = listOf(
            node(0, -1, 0, text = "Settings"),
            node(1, 0, 1, text = "Bluetooth"),
        )
        val built = SelectorBuilder.forNode(nodes[1], nodes)
        assertEquals("text", built.basis)
        assertEquals(listOf(1), SelectorEngine.findAll(nodes, built.selector).map { it.index })
    }

    @Test fun `ambiguous text falls back to class plus index`() {
        val nodes = listOf(
            node(0, -1, 0, text = "Row", r = 50),
            node(1, 0, 1, text = "Row", l = 50, r = 100),
            node(2, -1, 0, text = "Other", t = 100, b = 140),
        )
        val built = SelectorBuilder.forNode(nodes[1], nodes)
        assertEquals("class+index", built.basis)
        val found = SelectorEngine.findAll(nodes, built.selector)
        assertEquals(listOf(1), found.map { it.index })
    }

    @Test fun `desc is used when text is missing`() {
        val nodes = listOf(
            node(0, -1, 0),
            node(1, 0, 1, desc = "Play button"),
        )
        val built = SelectorBuilder.forNode(nodes[1], nodes)
        assertEquals("content-desc", built.basis)
        assertEquals(listOf(1), SelectorEngine.findAll(nodes, built.selector).map { it.index })
    }

    @Test fun `anonymous child with text-anchored parent round trips`() {
        // Two anonymous siblings under a text-bearing parent; no id/text/desc on the leaves.
        val nodes = listOf(
            node(0, -1, 0, cls = "android.widget.LinearLayout", text = "Account"),
            node(1, 0, 1, cls = "android.view.View", l = 0, r = 50),
            node(2, 0, 1, cls = "android.view.View", l = 50, r = 100),
        )
        val built = SelectorBuilder.forNode(nodes[2], nodes)
        assertEquals("class+index", built.basis)
        assertTrue(built.json.contains("child_of"))
        assertEquals(listOf(2), SelectorEngine.findAll(nodes, built.selector).map { it.index })
    }

    @Test fun `built json parses back into the same selector`() {
        val nodes = listOf(node(0, -1, 0, id = "com.target:id/ok"))
        val built = SelectorBuilder.forNode(nodes[0], nodes)
        val parsed = SelectorBuilderJson.decode(built.json)
        assertEquals(built.selector, parsed)
    }
}

/** Small decode helper kept next to the test (the wire format is the broker schema's Selector). */
private object SelectorBuilderJson {
    fun decode(json: String): Selector =
        kotlinx.serialization.json.Json.decodeFromString(Selector.serializer(), json)
}
