package app.autohost.runtime.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P11-01: the pure engine-cap decisions — memory/stack/deadline and the interrupt rule. */
class JsRunPolicyTest {

    @Test
    fun `caps use the default memory and the run timeout as the deadline`() {
        val caps = JsRunPolicy.caps(lowRam = false, timeoutMinutes = 5)
        assertEquals(JsRunPolicy.DEFAULT_MEMORY_BYTES, caps.maxMemoryBytes)
        assertEquals(JsRunPolicy.STACK_BYTES, caps.maxStackBytes)
        assertEquals(5 * 60_000L, caps.deadlineMs)
    }

    @Test
    fun `low-ram devices get the tighter cap`() {
        val caps = JsRunPolicy.caps(lowRam = true, timeoutMinutes = 10)
        assertEquals(JsRunPolicy.LOW_RAM_MEMORY_BYTES, caps.maxMemoryBytes)
    }

    @Test
    fun `absurd timeouts clamp to one day`() {
        assertEquals(24 * 60 * 60_000L, JsRunPolicy.caps(lowRam = false, timeoutMinutes = 100_000).deadlineMs)
        assertEquals(60_000L, JsRunPolicy.caps(lowRam = false, timeoutMinutes = 0).deadlineMs)
    }

    @Test
    fun `interrupt fires on cancel before the deadline`() {
        assertTrue(JsRunPolicy.shouldInterrupt(cancelled = true, startedMonotonicMs = 0, nowMonotonicMs = 1, deadlineMs = 60_000))
    }

    @Test
    fun `interrupt fires at the deadline and not before`() {
        assertFalse(JsRunPolicy.shouldInterrupt(cancelled = false, startedMonotonicMs = 0, nowMonotonicMs = 59_999, deadlineMs = 60_000))
        assertTrue(JsRunPolicy.shouldInterrupt(cancelled = false, startedMonotonicMs = 0, nowMonotonicMs = 60_000, deadlineMs = 60_000))
    }
}
