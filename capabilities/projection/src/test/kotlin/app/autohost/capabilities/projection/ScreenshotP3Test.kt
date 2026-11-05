package app.autohost.capabilities.projection

import app.autohost.core.common.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Screenshot throttle + chain failure mapping (P3-13/P3-14/P3-15, pure parts). */
class ScreenshotP3Test {

    class FakeClock(var now: Long = 1_000L) : Clock {
        override fun nowMs(): Long = now
        override fun monotonicMs(): Long = now
    }

    @Test fun `throttle enforces the minimum interval`() {
        val clock = FakeClock()
        val policy = ScreenshotThrottlePolicy(minIntervalMs = 1_000)
        assertTrue(policy.evaluate(clock.now) is ScreenshotThrottlePolicy.Decision.Allow)
        val limited = policy.evaluate(clock.now + 200) as ScreenshotThrottlePolicy.Decision.Limit
        assertEquals(800, limited.retryAfterMs)
        assertTrue(policy.evaluate(clock.now + 1_000) is ScreenshotThrottlePolicy.Decision.Allow)
    }

    @Test fun `throttle burst budget blocks the fourth shot in a minute`() {
        val policy = ScreenshotThrottlePolicy(minIntervalMs = 10, burstSize = 3)
        var t = 0L
        repeat(3) {
            assertTrue(policy.evaluate(t) is ScreenshotThrottlePolicy.Decision.Allow)
            t += 100
        }
        assertTrue(policy.evaluate(t) is ScreenshotThrottlePolicy.Decision.Limit)
    }

    // P15/X-16-era test fix: crop/scale GEOMETRY is pure (the Bitmap call is the only Android
    // touch and is covered by instrumented runs) — the unit tests pin the geometry.
    @Test fun `crop geometry clips to the image and ignores degenerate regions`() {
        assertEquals(Screenshotter.CropRect(25, 25, 50, 50), Screenshotter.cropRegion(100, 100, listOf(25, 25, 75, 75)))
        assertEquals(null, Screenshotter.cropRegion(100, 100, null)) // identity: no crop
        // region beyond the image is clipped, not fatal
        assertEquals(Screenshotter.CropRect(75, 75, 25, 25), Screenshotter.cropRegion(100, 100, listOf(75, 75, 500, 500)))
        // degenerate regions are identity (fail closed to the full image)
        assertEquals(null, Screenshotter.cropRegion(100, 100, listOf(50, 50, 50, 50)))
    }

    @Test fun `scale geometry keeps dimensions at or above one pixel`() {
        assertEquals(Pair(50, 50), Screenshotter.scaledSize(100, 100, 0.5))
        assertEquals(null, Screenshotter.scaledSize(100, 100, 1.0)) // identity
        assertEquals(Pair(1, 1), Screenshotter.scaledSize(100, 100, 0.001)) // at least one pixel
    }
}
