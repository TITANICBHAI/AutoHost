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

    @Test fun `crop clips to the image and ignores degenerate regions`() {
        val bmp = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        assertEquals(50, Screenshotter.crop(bmp, listOf(25, 25, 75, 75)).width)
        assertEquals(100, Screenshotter.crop(bmp, null).width)
        // region beyond the image is clipped, not fatal
        assertEquals(25, Screenshotter.crop(bmp, listOf(75, 75, 500, 500)).width)
    }

    @Test fun `scale keeps bitmaps at or above one pixel`() {
        val bmp = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        assertEquals(50, Screenshotter.scale(bmp, 0.5).width)
        assertEquals(100, Screenshotter.scale(bmp, 1.0).width)
        assertEquals(1, Screenshotter.scale(bmp, 0.001).width)
    }
}
