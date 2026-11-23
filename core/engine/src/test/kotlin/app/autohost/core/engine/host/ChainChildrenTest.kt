package app.autohost.core.engine.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** INT-E3: the memory of which run started which tasks, so that stopping a run can stop what it started. */
class ChainChildrenTest {

    @Test fun `a run hands out the tasks it started, once`() {
        val children = ChainChildren()
        children.started("parent", "child-1")
        children.started("parent", "child-2")
        assertEquals(setOf("child-1", "child-2"), children.take("parent"))
        assertTrue("the entry is gone after it was handed out", children.take("parent").isEmpty())
    }

    @Test fun `a run that started nothing has no children`() {
        assertTrue(ChainChildren().take("nobody").isEmpty())
    }

    @Test fun `runs are kept apart and one child told twice counts once`() {
        val children = ChainChildren()
        children.started("a", "x")
        children.started("b", "y")
        children.started("a", "x")
        assertEquals(setOf("x"), children.take("a"))
        assertEquals(setOf("y"), children.take("b"))
    }

    @Test fun `a child that started tasks of its own is its own parent`() {
        val children = ChainChildren()
        children.started("agent", "macro")
        children.started("macro", "inner")
        assertEquals(setOf("macro"), children.take("agent"))
        assertEquals(setOf("inner"), children.take("macro"))
    }
}
