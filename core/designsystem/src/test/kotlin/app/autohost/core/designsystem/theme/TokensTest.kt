package app.autohost.core.designsystem.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P14-01: the token contracts — grid membership, radii/motion sets, the reducedMotion rule. */
class TokensTest {

    @Test
    fun `given every spacing token when checked then all sit on the 4 dp grid`() {
        assertTrue(Tokens.spacingDp.all { Tokens.onGrid(it) })
    }

    @Test
    fun `given off-grid values when checked then the grid contract refuses them`() {
        assertFalse(Tokens.onGrid(0))
        assertFalse(Tokens.onGrid(-4))
        assertFalse(Tokens.onGrid(6))
        assertFalse(Tokens.onGrid(18))
        assertTrue(Tokens.onGrid(16))
    }

    @Test
    fun `given the token sets when inspected then the candidate sets are exactly as documented`() {
        assertEquals(listOf(8, 10, 16), Tokens.radiiDp)
        assertEquals(listOf(120, 240, 480, 900), Tokens.motionMs)
    }

    @Test
    fun `given reduced motion when a duration is resolved then movement disappears`() {
        assertEquals(0, Tokens.motionDurationMs(Tokens.MOTION_STANDARD_MS, reducedMotion = true))
        assertEquals(0, Tokens.motionDurationMs(Tokens.MOTION_EXPRESSIVE_MS, reducedMotion = true))
    }

    @Test
    fun `given normal motion when a duration is resolved then the preferred value survives`() {
        assertEquals(240, Tokens.motionDurationMs(240, reducedMotion = false))
        assertEquals(90, Tokens.motionDurationMs(90, reducedMotion = false))
    }

    @Test
    fun `given an oversized duration when resolved then it is clamped to the token set`() {
        assertEquals(Tokens.motionMs.max(), Tokens.motionDurationMs(5_000, reducedMotion = false))
    }
}
