package app.autohost.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavStackTest {

    @Test fun `a new stack is the Library root and Back would leave the app`() {
        val stack = NavStack()
        assertEquals(NavEntry("LIBRARY"), stack.top)
        assertFalse(stack.canPop)
        assertEquals(stack, stack.pop())
    }

    @Test fun `three screens pushed are popped in reverse order`() {
        val stack = NavStack().navigate("TASK_DETAIL", "t1").navigate("RUN", "r1").navigate("SETTINGS")
        assertEquals(listOf("LIBRARY", "TASK_DETAIL", "RUN", "SETTINGS"), stack.entries.map { it.route })
        val one = stack.pop()
        assertEquals("RUN", one.top.route)
        assertEquals("r1", one.top.arg)
        val two = one.pop()
        assertEquals("TASK_DETAIL", two.top.route)
        val three = two.pop()
        assertEquals("LIBRARY", three.top.route)
        assertFalse(three.canPop)
    }

    @Test fun `choosing a tab replaces the whole stack`() {
        val stack = NavStack().navigate("SETTINGS").navigate("HEALTH").navigate("SETUP")
        assertEquals(listOf(NavEntry("SETUP")), stack.entries)
    }

    @Test fun `an on-screen back button returns to the screen already in the stack`() {
        val stack = NavStack().navigate("SETTINGS").navigate("HEALTH").navigate("SETTINGS")
        assertEquals(listOf("LIBRARY", "SETTINGS"), stack.entries.map { it.route })
        assertTrue(stack.canPop)
    }

    @Test fun `going to a screen already shown updates its argument instead of stacking a copy`() {
        val stack = NavStack().navigate("RUN", "r1").navigate("RUN", "r2")
        assertEquals(listOf(NavEntry("LIBRARY"), NavEntry("RUN", "r2")), stack.entries)
    }

    @Test fun `the stack survives being saved and restored`() {
        val stack = NavStack().navigate("TASK_DETAIL", "app.x.y").navigate("RUN", "01ABC")
        val restored = NavStack.decode(stack.encode()) { true }
        assertEquals(stack, restored)
    }

    @Test fun `an argument containing the separator is restored whole`() {
        val stack = NavStack().navigate("TASK_DETAIL", "a|b|c")
        assertEquals(stack, NavStack.decode(stack.encode()) { true })
    }

    @Test fun `damaged or unknown saved state gives the default stack`() {
        assertEquals(NavStack(), NavStack.decode(null) { true })
        assertEquals(NavStack(), NavStack.decode(emptyList()) { true })
        assertEquals(NavStack(), NavStack.decode(listOf("GONE_SCREEN|x")) { false })
        val partly = NavStack.decode(listOf("LIBRARY", "GONE_SCREEN", "RUN|r1")) { it != "GONE_SCREEN" }
        assertEquals(listOf("LIBRARY", "RUN"), partly.entries.map { it.route })
    }
}
