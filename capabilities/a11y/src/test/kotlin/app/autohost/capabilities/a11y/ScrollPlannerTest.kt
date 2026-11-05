package app.autohost.capabilities.a11y

import app.autohost.core.model.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P12-08 (A4/RM-A7): the scroll-planner table — the three display sizes the A4 finding names
 * (720×1600 / 1080×2340 / 1080×2400), the nested-scrollable bounds, accessibility-first, and
 * the clamp that keeps every stroke inside its region.
 */
class ScrollPlannerTest {

    @Test
    fun `given a down scroll on the real 720x1600 display when planned then the stroke stays on screen`() {
        // A4: on a 720-wide display the OLD screen-centre math (1080×2400 defaults) started
        // swipes below the screen. The planner uses the injected real size.
        val plan = ScrollPlanner.plan(720, 1600, scrollable = null, scrollableNode = false, direction = "down")
        val s = plan.stroke!!
        assertTrue("x stays on the 720-wide screen", s.x1 in 0f..720f && s.x2 in 0f..720f)
        assertTrue("y stays on the 1600-tall screen", s.y1 in 0f..1600f && s.y2 in 0f..1600f)
        assertTrue("content moves up for a down scroll", s.y1 > s.y2)
    }

    @Test
    fun `given the three A4 display sizes when planned down then every stroke is inside the display`() {
        for ((w, h) in listOf(720 to 1600, 1080 to 2340, 1080 to 2400)) {
            val plan = ScrollPlanner.plan(w, h, null, false, "down")
            val s = plan.stroke!!
            assertTrue("$w×$h x", s.x1 in 0f..w && s.x2 in 0f..w)
            assertTrue("$w×$h y", s.y1 in 0f..h && s.y2 in 0f..h)
        }
    }

    @Test
    fun `given a nested scrollable when planned then the gesture is INSIDE its bounds`() {
        val scrollable = Bounds(100, 200, 600, 900) // a list inside a scroll view
        val plan = ScrollPlanner.plan(1080, 2400, scrollable, scrollableNode = false, direction = "down")
        val s = plan.stroke!!
        assertTrue("x inside the scrollable", s.x1 in 100f..600f && s.x2 in 100f..600f)
        assertTrue("y inside the scrollable", s.y1 in 200f..900f && s.y2 in 200f..900f)
    }

    @Test
    fun `given a scrollable node when planned then accessibility-first and no gesture`() {
        val plan = ScrollPlanner.plan(1080, 2400, Bounds(0, 0, 1080, 2400), scrollableNode = true, direction = "down")
        assertTrue(plan.accessibilityFirst)
        assertNull(plan.stroke)
    }

    @Test
    fun `given an up scroll when planned then the stroke reverses (content moves down)`() {
        val plan = ScrollPlanner.plan(1080, 2400, null, false, "up")
        val s = plan.stroke!!
        assertTrue("content moves down for an up scroll", s.y1 < s.y2)
    }

    @Test
    fun `given a horizontal scroll when planned then the stroke moves horizontally`() {
        val right = ScrollPlanner.plan(1080, 2400, null, false, "right").stroke!!
        assertTrue("content moves left for a right scroll", right.x1 > right.x2)
        assertEquals(right.y1, right.y2)
    }

    @Test
    fun `given strokes when clamped then they never touch the 8 dp screen-edge margin`() {
        // A tall scrollable spanning the full screen: the centre ± 45% reaches the edges —
        // the clamp keeps the stroke inside with a margin (the A4 off-screen start defect).
        val plan = ScrollPlanner.plan(720, 1600, Bounds(0, 0, 720, 1600), false, "down")
        val s = plan.stroke!!
        assertTrue(s.y1 >= 8f && s.y2 >= 8f)
        assertTrue(s.y1 <= 1592f && s.y2 <= 1592f)
    }

    @Test
    fun `given an unknown direction when planned then refused`() {
        var failed = false
        try {
            ScrollPlanner.plan(1080, 2400, null, false, "diagonal")
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
