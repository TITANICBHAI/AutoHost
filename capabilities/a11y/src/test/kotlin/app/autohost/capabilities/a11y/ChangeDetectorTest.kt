package app.autohost.capabilities.a11y

import app.autohost.core.model.Bounds
import app.autohost.core.model.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P12-03 (A2/RM-A4): the three-valued state-aware change detector. The FIRST test is the A2 red
 * demonstration: a toggle that only flips `checked` is INVISIBLE to the old
 * `SettlePolicy.fingerprint` (index:id:text:desc:bounds) and VISIBLE to [ChangeDetector].
 */
class ChangeDetectorTest {

    private fun node(
        index: Int, id: String, text: String = "", flags: Set<String> = emptySet(),
    ) = UiNode(
        index = index, parent = -1, depth = 0, pkg = "com.x", cls = "android.widget.Switch",
        id = id, text = text, desc = "", bounds = Bounds(0, index * 100, 100, index * 100 + 80),
        flags = flags,
    )

    @Test
    fun `given a toggle that only flips checked when detected then Changed (the old fingerprint was blind)`() {
        val pre = listOf(node(0, "dnd_switch", flags = setOf("clickable", "checked")))
        val post = listOf(node(0, "dnd_switch", flags = setOf("clickable"))) // checked flipped OFF
        // RED (A2 demonstration): the OLD index:id:text:desc:bounds formula is blind to checked —
        // inlined here so the defect stays visible after SettlePolicy delegates to the detector.
        fun oldFingerprint(nodes: List<UiNode>): String = nodes.joinToString("|") { n ->
            "\${n.index}:\${n.id}:\${n.text}:\${n.desc}:\${n.bounds.l},\${n.bounds.t},\${n.bounds.r},\${n.bounds.b}"
        }
        assertEquals(
            "the OLD fingerprint is blind to checked — this equality IS the defect",
            oldFingerprint(pre), oldFingerprint(post),
        )
        // the fix: the state-aware detector reads it as Changed
        assertEquals(ChangeDetector.Change.Changed, ChangeDetector.detect(pre, post))
    }

    @Test
    fun `given identical node lists when detected then Unchanged`() {
        val nodes = listOf(node(0, "a"), node(1, "b", text = "x"))
        assertEquals(ChangeDetector.Change.Unchanged, ChangeDetector.detect(nodes, nodes))
    }

    @Test
    fun `given a null snapshot when detected then Unverifiable (never silently Changed)`() {
        assertEquals(ChangeDetector.Change.Unverifiable, ChangeDetector.detect(null, listOf(node(0, "a"))))
        assertEquals(ChangeDetector.Change.Unverifiable, ChangeDetector.detect(listOf(node(0, "a")), null))
        assertEquals(ChangeDetector.Change.Unverifiable, ChangeDetector.detect(null, null))
    }

    @Test
    fun `given a focus shift only when detected then Unchanged (focused is excluded per RM-A4)`() {
        val pre = listOf(node(0, "a", flags = setOf("clickable")))
        val post = listOf(node(0, "a", flags = setOf("clickable", "focused")))
        assertEquals(
            "a focus shift after a click is not a UI change",
            ChangeDetector.Change.Unchanged, ChangeDetector.detect(pre, post),
        )
    }

    @Test
    fun `given enabled or selected flips when detected then Changed (state-aware)`() {
        val base = listOf(node(0, "btn", flags = setOf("enabled")))
        assertTrue(
            ChangeDetector.detect(base, listOf(node(0, "btn", flags = setOf()))) ==
                ChangeDetector.Change.Changed,
        )
        assertTrue(
            ChangeDetector.detect(base, listOf(node(0, "btn", flags = setOf("enabled", "selected")))) ==
                ChangeDetector.Change.Changed,
        )
    }

    @Test
    fun `given a text or bounds change when detected then Changed (structural fields still count)`() {
        val base = listOf(node(0, "a", text = "one"))
        assertEquals(ChangeDetector.Change.Changed, ChangeDetector.detect(base, listOf(node(0, "a", text = "two"))))
    }

    @Test
    fun `given the fingerprint when compared across runs then deterministic (stable hash)`() {
        val nodes = listOf(node(0, "a"), node(1, "b", text = "x"))
        assertEquals(ChangeDetector.fingerprint(nodes), ChangeDetector.fingerprint(nodes))
        // order-independent BY DESIGN (sorted by index) — a different CONTENT is what must differ
        assertNotEquals(ChangeDetector.fingerprint(nodes), ChangeDetector.fingerprint(listOf(node(0, "a"))))
    }
}
