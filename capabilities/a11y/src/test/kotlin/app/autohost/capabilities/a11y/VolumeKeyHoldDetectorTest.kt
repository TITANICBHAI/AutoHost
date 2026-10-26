package app.autohost.capabilities.a11y

import app.autohost.core.common.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P7-12: the volume-key hold gesture (global stop) — a pure state machine, so the a11y service's
 *  onKeyEvent path stays a thin adapter. */
class VolumeKeyHoldDetectorTest {

    private fun detector(holdMs: Long = 1_500, keyCode: Int = 25) =
        VolumeKeyHoldDetector(FakeClock(0), holdMs, keyCode)

    @Test fun `hold crosses the threshold exactly once`() {
        val d = detector()
        assertTrue(d.onKeyDown(25, 0))
        assertFalse(d.poll(1_499))
        assertTrue(d.poll(1_500))
        assertFalse(d.poll(9_999)) // never twice
    }

    @Test fun `release before the threshold does not fire`() {
        val d = detector()
        assertTrue(d.onKeyDown(25, 0))
        assertFalse(d.onKeyUp(25, 800))
        assertFalse(d.poll(5_000)) // key is up: the pending threshold is dead
    }

    @Test fun `release after the threshold stays consumed`() {
        val d = detector()
        assertTrue(d.onKeyDown(25, 0))
        assertTrue(d.poll(1_500))
        assertTrue(d.onKeyUp(25, 2_000)) // consumed: the volume HUD must not pop on release
    }

    @Test fun `other keys pass through untouched`() {
        val d = detector()
        assertFalse(d.onKeyDown(24, 0)) // volume up while the filter watches volume down
        assertFalse(d.onKeyUp(24, 100))
        assertFalse(d.poll(2_000))
    }

    @Test fun `wrong key does not disturb an in-progress hold`() {
        val d = detector()
        assertTrue(d.onKeyDown(25, 0))
        assertFalse(d.onKeyDown(24, 200))
        assertTrue(d.poll(1_500))
    }

    @Test fun `key repeats do not re-arm`() {
        val d = detector()
        assertTrue(d.onKeyDown(25, 0))
        assertTrue(d.onKeyDown(25, 300)) // repeat
        assertTrue(d.onKeyDown(25, 600)) // repeat
        assertTrue(d.poll(1_500))
    }

    @Test fun `configurable key code is honoured`() {
        val d = detector(keyCode = 24)
        assertTrue(d.onKeyDown(24, 0))
        assertTrue(d.poll(1_500))
    }

    @Test fun `reset clears everything`() {
        val d = detector()
        assertTrue(d.onKeyDown(25, 0))
        d.reset()
        assertFalse(d.poll(9_999))
        assertEquals(false, d.isHolding)
        assertTrue(d.onKeyDown(25, 10_000)) // fresh arm works again
        assertTrue(d.poll(11_500))
    }
}
