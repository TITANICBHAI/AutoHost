package app.autohost.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3 selector features: bounds modes, drawing order, index relation, BFS ordering (P3-01). */
class SelectorEngineP3Test {
    private val tree = listOf(
        node(0, text = "Continue", bounds = Bounds(0, 0, 1080, 300), flags = setOf("clickable"), drawingOrder = 0),
        node(1, parent = 0, depth = 1, text = "Continue", bounds = Bounds(10, 100, 500, 200), drawingOrder = 1),
        node(2, text = "Later", bounds = Bounds(0, 500, 100, 600), drawingOrder = 0),
        node(3, text = "Overlay", bounds = Bounds(20, 110, 200, 190), drawingOrder = 2),
    )

    private fun idx(s: Selector, nodes: List<UiNode> = tree) = SelectorEngine.findAll(nodes, s).map { it.index }

    // --- bounds modes ----------------------------------------------------------------------------------------

    @Test fun `bounds inside keeps only fully contained nodes`() {
        val f = BoundsFilter(left = 0, top = 0, right = 1080, bottom = 300)
        // findAll keeps deepest matches; the fully-contained parent is suppressed by its child.
        assertEquals(listOf(1, 3), idx(Selector(terms = listOf(SelectorTerm(bounds = f)))))
    }

    @Test fun `bounds overlaps matches any intersection`() {
        val f = BoundsFilter(left = 15, top = 105, right = 100, bottom = 150, mode = "overlaps")
        assertEquals(listOf(1, 3), idx(Selector(terms = listOf(SelectorTerm(bounds = f)))))
    }

    @Test fun `open sides are unbounded`() {
        val f = BoundsFilter(top = 400)
        assertEquals(listOf(2), idx(Selector(terms = listOf(SelectorTerm(bounds = f)))))
    }

    @Test fun `touching edges do not overlap but identical rects do`() {
        val f = BoundsFilter(left = 0, top = 300, right = 10, bottom = 400, mode = "overlaps")
        assertTrue(SelectorEngine.boundsHold(Bounds(0, 300, 50, 400), f)) // identical rect overlaps
        assertFalse(SelectorEngine.boundsHold(Bounds(0, 0, 100, 300), f)) // only shares an edge
    }

    // --- drawing order ----------------------------------------------------------------------------------------

    @Test fun `drawing_order filter picks the node drawn at that z-position`() {
        val f = Selector(terms = listOf(SelectorTerm(drawingOrder = 2)))
        assertEquals(listOf(3), idx(f))
    }

    // --- index relation ----------------------------------------------------------------------------------------

    @Test fun `index relation selects the nth anchor match in document order`() {
        val anchors = Selector(terms = listOf(SelectorTerm(text = Match(listOf("continue")))))
        val first = Selector(terms = listOf(SelectorTerm(relation = Relation("index", anchors, n = 0))))
        val second = Selector(terms = listOf(SelectorTerm(relation = Relation("index", anchors, n = 1))))
        val third = Selector(terms = listOf(SelectorTerm(relation = Relation("index", anchors, n = 2))))
        assertEquals(listOf(0), idx(first))
        assertEquals(listOf(1), idx(second))
        assertEquals(emptyList<Int>(), idx(third))
    }

    // --- BFS -----------------------------------------------------------------------------------------------------

    @Test fun `bfs prefers the shallowest match across branches`() {
        // Document order puts the deep node first; BFS re-orders shallowest-first. The two matches are in
        // different branches, so the deepest-match rule does not collapse them.
        val nodes = listOf(
            node(0, id = "app.example:id/root"),
            node(1, parent = 0, depth = 1, text = "Go", bounds = Bounds(0, 200, 10, 210)),
            node(2, text = "Go", bounds = Bounds(0, 0, 10, 10)),
        )
        val s = textSel("Go")
        assertEquals(listOf(1, 2), idx(s, nodes)) // dfs: document order
        assertEquals(listOf(2, 1), idx(s.copy(algorithm = "bfs"), nodes)) // bfs: depth 0 before depth 1
    }

    @Test fun `deepest-match rule still applies under bfs`() {
        val nodes = listOf(
            node(0, text = "Continue"),
            node(1, parent = 0, depth = 1, text = "Continue"),
        )
        assertEquals(listOf(1), idx(textSel("Continue").copy(algorithm = "bfs"), nodes))
    }

    private fun textSel(vararg labels: String): Selector =
        Selector(terms = listOf(SelectorTerm(text = Match(labels.toList()))))
}
