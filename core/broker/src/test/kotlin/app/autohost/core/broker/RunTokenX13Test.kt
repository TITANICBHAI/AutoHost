package app.autohost.core.broker

import app.autohost.core.broker.interceptor.AuthInterceptor
import app.autohost.core.common.Clock
import app.autohost.core.common.FakeClock
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X-13 (ADR-099): the run token is a bearer capability — unpredictable, bound to one run/attempt/
 * grant snapshot, expiring, revoked on terminal/cancel, never echoed. These tests pin the broker
 * side; the WAITING_USER semantics are pinned in core:engine (RunStateMachineX13Test).
 */
class RunTokenX13Test {

    private fun grants(
        runId: String = "run-1",
        attempt: Int = 1,
        expiresAtMs: Long = 0L,
    ) = RunGrants(
        runId = runId,
        taskId = "task-1",
        profileId = null,
        grants = mapOf("device.ui" to JsonObject(emptyMap())),
        issuedMs = 1_000L,
        attempt = attempt,
        expiresAtMs = expiresAtMs,
    )

    // -- unpredictability and shape ---------------------------------------------------------------

    @Test
    fun `given two issues when tokens compared then distinct and well formed`() {
        val store = RunTokenStore()
        val a = store.issue(grants())
        val b = store.issue(grants(attempt = 2))
        assertNotEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(store.isWellFormed(a))
        assertFalse(store.isWellFormed(a.uppercase()))
        assertFalse(store.isWellFormed(a.dropLast(1)))
        assertFalse(store.isWellFormed(""))
    }

    // -- strict resolution -------------------------------------------------------------------------

    @Test
    fun `given a live token when resolved then grants come back`() {
        val store = RunTokenStore()
        val token = store.issue(grants())
        val resolved = store.resolve(token, nowMs = 2_000L)
        assertNotNull(resolved)
        assertEquals("run-1", resolved!!.runId)
    }

    @Test
    fun `given an unknown or revoked token when resolved then null`() {
        val store = RunTokenStore()
        assertNull(store.resolve("f".repeat(64), nowMs = 1L))
        val token = store.issue(grants())
        store.revoke(token)
        assertNull(store.resolve(token, nowMs = 1L))
    }

    @Test
    fun `given an expired token when strictly resolved then null but audit still sees it`() {
        val store = RunTokenStore()
        val token = store.issue(grants(expiresAtMs = 5_000L))
        assertNotNull(store.resolve(token, nowMs = 4_999L))
        assertNull(store.resolve(token, nowMs = 5_000L))
        // audit/display may still see the mapping; the gate never does
        assertNotNull(store.resolveIgnoreExpiry(token))
    }

    @Test
    fun `given a malformed token when strictly resolved then null without a lookup`() {
        val store = RunTokenStore()
        assertNull(store.resolve("not-a-token", nowMs = 1L))
        assertNull(store.resolve("F".repeat(64), nowMs = 1L)) // uppercase is malformed by contract
    }

    // -- run and attempt binding -----------------------------------------------------------------

    @Test
    fun `given revokeRun for another run when resolved then this token survives`() {
        val store = RunTokenStore()
        val token = store.issue(grants(runId = "run-1"))
        store.revokeRun("run-OTHER")
        assertNotNull(store.resolve(token, nowMs = 1L))
        assertEquals(1, store.revokeRun("run-1"))
        assertNull(store.resolve(token, nowMs = 1L))
    }

    @Test
    fun `given two attempts of one run when attempt one is revoked then attempt two survives`() {
        val store = RunTokenStore()
        val attempt1 = store.issue(grants(attempt = 1))
        val attempt2 = store.issue(grants(attempt = 2))
        assertNotEquals(attempt1, attempt2)
        store.revoke(attempt1) // the dead attempt's credential is withdrawn
        assertNull(store.resolve(attempt1, nowMs = 1L))
        assertNotNull(store.resolve(attempt2, nowMs = 1L))
        assertEquals("run-1", store.resolve(attempt2, nowMs = 1L)!!.runId)
    }

    // -- the broker gate (AuthInterceptor) ---------------------------------------------------------

    private suspend fun gate(store: RunTokenStore, clock: Clock, token: String): Envelope {
        val interceptor = AuthInterceptor(store, clock)
        val call = PipelineCall(token, "device.ui", "snapshot", JsonObject(emptyMap()), startedMonotonicMs = 0)
        return interceptor.intercept(call) { Envelope.ok() }
    }

    @Test
    fun `given a malformed token at the gate then refused with a reason and the token never echoed`() = runTest {
        val store = RunTokenStore()
        val bad = "X".repeat(64)
        val envelope = gate(store, FakeClock(), bad)
        assertEquals(ErrorCode.PERMISSION_DENIED.name, envelope.error?.code)
        assertEquals("MALFORMED_RUN_TOKEN", envelope.error?.reason)
        assertFalse(envelope.error!!.message.contains(bad))
    }

    @Test
    fun `given an expired token at the gate then UNKNOWN_RUN_TOKEN`() = runTest {
        val store = RunTokenStore()
        val token = store.issue(grants(expiresAtMs = 2_000L))
        val clock = FakeClock(startMs = 10_000L)
        val envelope = gate(store, clock, token)
        assertEquals("UNKNOWN_RUN_TOKEN", envelope.error?.reason)
    }

    @Test
    fun `given a live token at the gate then the call proceeds with the bound context`() = runTest {
        val store = RunTokenStore()
        val token = store.issue(grants(runId = "run-9", attempt = 3))
        val clock = FakeClock()
        val interceptor = AuthInterceptor(store, clock)
        val call = PipelineCall(token, "device.ui", "snapshot", JsonObject(emptyMap()), startedMonotonicMs = 0)
        val envelope = interceptor.intercept(call) { Envelope.ok() }
        assertTrue(envelope.ok)
        assertEquals("run-9", call.context?.runId)
    }
}
