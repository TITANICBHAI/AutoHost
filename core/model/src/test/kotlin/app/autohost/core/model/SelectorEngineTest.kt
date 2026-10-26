package app.autohost.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorEngineTest {
    private val nodes = listOf(
        node(0, text = "Example app", id = "app.example:id/fx_title"),
        node(1, text = "Continue", id = "app.example:id/fx_btn_continue", cls = "android.widget.Button",
            bounds = Bounds(0, 200, 100, 300), flags = setOf("clickable", "enabled")),
        node(2, text = "Weiter", bounds = Bounds(0, 320, 100, 420)),
        node(3, text = "Net\u2019s status", bounds = Bounds(0, 440, 100, 540)),
    )

    private fun idx(s: Selector) = SelectorEngine.findAll(nodes, s).map { it.index }

    @Test fun `contains is case insensitive`() = assertEquals(listOf(1), idx(textSel("continue")))

    @Test fun `eq is exact`() {
        assertEquals(emptyList<Int>(), idx(textSel("Continu", mode = "eq")))
        assertEquals(listOf(1), idx(textSel("Continue", mode = "eq")))
    }

    @Test fun `any label matches`() = assertEquals(listOf(1, 2), idx(textSel("Weiter", "Continue")))

    @Test fun `typographic apostrophe matches ascii`() = assertEquals(listOf(3), idx(textSel("Net's status")))

    @Test fun `empty label never matches anything`() = assertEquals(emptyList<Int>(), idx(textSel("")))

    @Test fun `id matches full id and short name`() {
        assertEquals(listOf(1), idx(idSel("app.example:id/fx_btn_continue")))
        assertEquals(listOf(1), idx(idSel("fx_btn_continue")))
        assertEquals(emptyList<Int>(), idx(idSel("FX_BTN_CONTINUE")))
    }

    @Test fun `state filter`() = assertEquals(listOf(1), idx(stateSel("clickable")))

    @Test fun `and requires every term, or requires any`() {
        val both = Selector(terms = listOf(SelectorTerm(sel = textSel("Continue")), SelectorTerm(sel = stateSel("clickable"))))
        assertEquals(listOf(1), idx(both))
        val either = Selector(op = "or", terms = listOf(SelectorTerm(sel = textSel("Weiter")), SelectorTerm(sel = idSel("fx_title"))))
        assertEquals(listOf(0, 2), idx(either))
    }

    @Test fun `below relation uses bounds`() {
        val s = Selector(terms = listOf(SelectorTerm(relation = Relation("below", idSel("fx_title")))))
        assertEquals(listOf(1, 2, 3), idx(s))
    }

    @Test fun `nth picks one result`() =
        assertEquals(listOf(1), SelectorEngine.findAll(nodes, textSel("e").copy(nth = 1)).map { it.index })

    @Test fun `deepest match wins over its ancestor`() {
        val tree = listOf(
            node(0, text = "Continue", flags = setOf("clickable")),
            node(1, parent = 0, depth = 1, text = "Continue"),
        )
        assertEquals(listOf(1), SelectorEngine.findAll(tree, textSel("Continue")).map { it.index })
    }

    @Test fun `within restricts the search to descendants`() {
        val tree = listOf(
            node(0, id = "app.example:id/list"),
            node(1, parent = 0, depth = 1, text = "Row"),
            node(2, text = "Row"),
        )
        val s = textSel("Row").copy(within = idSel("list"))
        assertEquals(listOf(1), SelectorEngine.findAll(tree, s).map { it.index })
    }

    @Test fun `child_of and contains_child use the tree`() {
        val tree = listOf(
            node(0, id = "app.example:id/box"),
            node(1, parent = 0, depth = 1, text = "Inside"),
            node(2, text = "Outside"),
        )
        val childOf = Selector(terms = listOf(SelectorTerm(relation = Relation("child_of", idSel("box")))))
        assertEquals(listOf(1), SelectorEngine.findAll(tree, childOf).map { it.index })
        val containsChild = Selector(terms = listOf(SelectorTerm(relation = Relation("contains_child", textSel("Inside")))))
        assertEquals(listOf(0), SelectorEngine.findAll(tree, containsChild).map { it.index })
    }

    @Test fun `regex mode is case insensitive by default`() {
        assertTrue(idx(textSel("^cont.*e$", mode = "regex")).contains(1))
    }

    @Test fun `text normalizer is stable`() = assertEquals("hello world", TextNormalizer.normalize("  Hello\u00a0World "))
}
