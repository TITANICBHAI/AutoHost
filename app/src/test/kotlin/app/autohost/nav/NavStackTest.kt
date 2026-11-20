package app.autohost.nav

import app.autohost.Routes
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

    // This test used to say that "SETUP" was a tab. It was one before UI v3 (Tasks / Setup / Schedules); the bottom bar is
    // now one route and "What this phone allows" is opened from Settings, so the rule moved to the tab bar itself.
    @Test fun `choosing the tab bar replaces the whole stack`() {
        val stack = NavStack().navigate("SETTINGS").navigate("HEALTH").navigate("LIBRARY")
        assertEquals(listOf(NavEntry("LIBRARY")), stack.entries)
    }

    @Test fun `only the tab bar is a root, so Back on any other screen returns instead of closing the app`() {
        for (route in Routes.entries.filter { it != Routes.LIBRARY }) {
            val stack = NavStack().navigate(route.name)
            assertEquals("${route.name} is pushed on top of the tab bar", listOf("LIBRARY", route.name), stack.entries.map { it.route })
            assertTrue("Back must work on ${route.name}", stack.canPop)
            assertEquals("Back from ${route.name} returns to the tab bar", NavStack(), stack.pop())
        }
    }

    @Test fun `a screen opened from a task returns to that task`() {
        val stack = NavStack().navigate("TASK_DETAIL", "t1").navigate("SETUP")
        assertEquals(listOf("LIBRARY", "TASK_DETAIL", "SETUP"), stack.entries.map { it.route })
        assertEquals("TASK_DETAIL", stack.pop().top.route)
        assertEquals("t1", stack.pop().top.arg)
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
