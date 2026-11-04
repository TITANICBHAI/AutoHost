package app.autohost.ui.capsule

import app.autohost.core.common.FakeClock
import app.autohost.core.designsystem.theme.Tokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P14-03 / G14-b's JVM half: the approval decision path, label truncation, timeout → deny. */
class ApprovalStateTest {

    private val clock = FakeClock(startMs = 1_000_000L)

    private fun state() = ApprovalState(clock)

    @Test
    fun `given an ask when raised then the three shared choices are offered`() {
        assertEquals(
            listOf("Allow once", "Allow for this run", "Deny"),
            ApprovalState.CHOICES,
        )
    }

    @Test
    fun `given a long node label when raised then it is truncated with an ellipsis`() {
        val ask = state().raise("r1", "c1", "device.ui", "click", "L".repeat(80), "com.x", cautious = false)
        assertEquals(40, ask.label!!.length)
        assertTrue(ask.label!!.endsWith("…"))
    }

    @Test
    fun `given a cautious app when raised then no label is shown at all`() {
        val ask = state().raise("r1", "c1", "device.ui", "click", "secret button", "com.bank", cautious = true)
        assertNull(ask.label)
    }

    @Test
    fun `given a multi-line label when raised then newlines collapse to spaces`() {
        val ask = state().raise("r1", "c1", "device.ui", "click", "line1\nline2", "com.x", cautious = false)
        assertEquals("line1 line2", ask.label)
    }

    @Test
    fun `given allow once when resolved then the pending ask clears and the decision returns`() {
        val s = state()
        s.raise("r1", "c1", "device.ui", "click", "btn", "com.x", cautious = false)
        val ask = s.resolve("c1", ApprovalState.Decision.ALLOW_ONCE)
        assertNotNull(ask)
        assertNull(s.pending)
        assertFalse(s.isAwaiting)
    }

    @Test
    fun `given a stale callId when resolved then nothing happens`() {
        val s = state()
        s.raise("r1", "c1", "device.ui", "click", "btn", "com.x", cautious = false)
        assertNull(s.resolve("c2", ApprovalState.Decision.DENY))
        assertNotNull(s.pending)
    }

    @Test
    fun `given no answer when the timeout passes then tick denies and stops`() {
        val s = state()
        s.raise("r1", "c1", "device.ui", "click", "btn", "com.x", cautious = false)
        clock.advance(Tokens.APPROVAL_TIMEOUT_MS - 1)
        assertNull("one ms before the deadline nothing is decided", s.tick())
        clock.advance(1)
        assertEquals(ApprovalState.Decision.DENY, s.tick())
        assertNull("the expired ask is consumed", s.pending)
    }

    @Test
    fun `given no pending ask when ticked then null`() {
        assertNull(state().tick())
    }
}
