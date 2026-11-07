package app.autohost.surfaces

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P12-07 (ACTION_HARDENING §5): the overlay audit as a test. Every surface's flag set must
 * keep the no-swallowing property: an overlay either (a) passes touches through OUTSIDE its
 * bounds (`FLAG_NOT_TOUCH_MODAL`), (b) never receives touches at all (`FLAG_NOT_TOUCHABLE`),
 * or (c) is the full-screen inspector, interactive by design. The constants are compile-time
 * `static final int`s in android.jar, so the JVM test sees their real values.
 */
class OverlayWindowFlagsTest {

    private val NOT_FOCUSABLE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    private val NOT_TOUCH_MODAL = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    private val NOT_TOUCHABLE = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

    @Test
    fun `given the bubble when audited then it is NOT_TOUCH_MODAL (the P12-07 swallowing fix)`() {
        // The regression: the bubble carried only NOT_FOCUSABLE, and without NOT_TOUCH_MODAL the
        // window receives EVERY touch on screen — the bubble blocked the whole display while up.
        assertEquals(NOT_FOCUSABLE or NOT_TOUCH_MODAL, OverlayWindowFlags.BUBBLE)
        assertTrue(OverlayWindowFlags.BUBBLE and NOT_TOUCH_MODAL != 0)
    }

    @Test
    fun `given the transient strips when audited then they pass touches through outside their bounds`() {
        for ((name, flags) in mapOf(
            "prompt text" to OverlayWindowFlags.PROMPT_TEXT,
            "recorder" to OverlayWindowFlags.RECORDER,
        )) {
            assertTrue("$name: NOT_TOUCH_MODAL missing", flags and NOT_TOUCH_MODAL != 0)
            assertTrue("$name: NOT_FOCUSABLE missing", flags and NOT_FOCUSABLE != 0)
        }
    }

    @Test
    fun `given the visual-only task overlays when audited then they never receive touches`() {
        assertEquals(NOT_TOUCHABLE or NOT_FOCUSABLE, OverlayWindowFlags.TASK_VISUAL)
    }

    @Test
    fun `given the inspector when audited then it stays interactive-by-design and full-screen`() {
        assertEquals(
            NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            OverlayWindowFlags.INSPECTOR,
        )
    }

    @Test
    fun `given every overlay when audited then none takes window focus (rule 7 — the service keeps input)`() {
        for ((name, flags) in mapOf(
            "bubble" to OverlayWindowFlags.BUBBLE,
            "inspector" to OverlayWindowFlags.INSPECTOR,
            "prompt text" to OverlayWindowFlags.PROMPT_TEXT,
            "recorder" to OverlayWindowFlags.RECORDER,
            "task visual" to OverlayWindowFlags.TASK_VISUAL,
        )) {
            assertFalse("$name steals focus", flags and NOT_FOCUSABLE == 0)
        }
    }

    @Test
    fun `given the shared type when audited then it is the accessibility overlay`() {
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, OverlayWindowFlags.TYPE)
    }
}
