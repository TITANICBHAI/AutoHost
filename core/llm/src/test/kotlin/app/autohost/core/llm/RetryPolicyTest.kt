package app.autohost.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P13-04 (`docs/MODEL_GATEWAY.md` §9.3): retry table tests with a fixed jitter. */
class RetryPolicyTest {

    private val fixed = RetryPolicy.Jitter { it } // no jitter: deterministic delays

    @Test
    fun `given a transient failure on attempt 1 when planned then retry after 1 s`() {
        val d = RetryPolicy.plan(RetryPolicy.Outcome(RetryPolicy.OutcomeKind.TRANSIENT, attempt = 1), 60_000, fixed)
        assertEquals(RetryPolicy.Decision.Retry(1_000), d)
    }

    @Test
    fun `given backoff when the attempt advances then the delay doubles`() {
        assertEquals(
            RetryPolicy.Decision.Retry(2_000),
            RetryPolicy.plan(RetryPolicy.Outcome(RetryPolicy.OutcomeKind.TRANSIENT, attempt = 2), 60_000, fixed),
        )
    }

    @Test
    fun `given three attempts when the third fails then give up`() {
        assertEquals(
            RetryPolicy.Decision.GiveUp,
            RetryPolicy.plan(RetryPolicy.Outcome(RetryPolicy.OutcomeKind.TRANSIENT, attempt = 3), 60_000, fixed),
        )
    }

    @Test
    fun `given a Retry-After when planned then it is honoured but capped at 30 s`() {
        assertEquals(
            RetryPolicy.Decision.Retry(10_000),
            RetryPolicy.plan(RetryPolicy.Outcome(RetryPolicy.OutcomeKind.RATE_LIMITED, attempt = 1, retryAfterMs = 10_000), 60_000, fixed),
        )
        assertEquals(
            RetryPolicy.Decision.Retry(30_000),
            RetryPolicy.plan(RetryPolicy.Outcome(RetryPolicy.OutcomeKind.RATE_LIMITED, attempt = 1, retryAfterMs = 120_000), 60_000, fixed),
        )
    }

    @Test
    fun `given a delay that would exceed the run budget when planned then give up`() {
        assertEquals(
            RetryPolicy.Decision.GiveUp,
            RetryPolicy.plan(RetryPolicy.Outcome(RetryPolicy.OutcomeKind.TRANSIENT, attempt = 1, retryAfterMs = 60_000), 30_000, fixed),
        )
    }

    @Test
    fun `given non-retryable kinds when planned then give up immediately`() {
        for (kind in listOf(RetryPolicy.OutcomeKind.AUTH, RetryPolicy.OutcomeKind.INVALID, RetryPolicy.OutcomeKind.CANCELLED)) {
            assertEquals(
                "kind $kind must never retry",
                RetryPolicy.Decision.GiveUp,
                RetryPolicy.plan(RetryPolicy.Outcome(kind, attempt = 1), 60_000, fixed),
            )
        }
    }

    @Test
    fun `given a parsed response with tool calls when planned then never replayed`() {
        assertEquals(
            RetryPolicy.Decision.GiveUp,
            RetryPolicy.plan(
                RetryPolicy.Outcome(RetryPolicy.OutcomeKind.SUCCESS, attempt = 1, hasToolCalls = true),
                60_000, fixed,
            ),
        )
    }

    @Test
    fun `given the jitter seam when a ±20 percent jitter is applied then the delay stays in range`() {
        val j = RetryPolicy.TWENTY_PERCENT
        for (i in 1..50) {
            val delayed = j.apply(1_000)
            assertTrue("delay $delayed outside 800..1200", delayed in 800..1_200)
        }
    }
}
