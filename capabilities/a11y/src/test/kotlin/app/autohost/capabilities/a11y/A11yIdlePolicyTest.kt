package app.autohost.capabilities.a11y

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X-20 (ADR-100): the idle-cost guard — the EXPENSIVE event-path work (the auto-accept tree
 * consult) runs only while a host flow is pending or a run is active; the cheap counters never
 * stop (they ARE the settle machinery). The phone CPU sample is the D-25-skipped half; the
 * worker/interpreter laziness is enforced by X-19's architecture conformance suite.
 */
class A11yIdlePolicyTest {

    @Test
    fun `given nothing running and no pending flow when consulted then NO tree work (events are counted, not processed)`() {
        assertFalse(A11yIdlePolicy.mayConsultTree(AutoAcceptPolicy.Flow.NONE, runActive = false))
    }

    @Test
    fun `given a pending host flow when consulted then the tree consult may run (the auto-accept reason it exists)`() {
        for (flow in AutoAcceptPolicy.Flow.entries.filter { it != AutoAcceptPolicy.Flow.NONE }) {
            assertTrue("flow $flow", A11yIdlePolicy.mayConsultTree(flow, runActive = false))
        }
    }

    @Test
    fun `given a run active when consulted then the tree consult may run even with no pending flow`() {
        assertTrue(A11yIdlePolicy.mayConsultTree(AutoAcceptPolicy.Flow.NONE, runActive = true))
    }

    @Test
    fun `given the cheap counters when idle then they NEVER stop (wait_for_settle is built on them)`() {
        assertTrue(A11yIdlePolicy.countsEventsWhileIdle())
    }
}
