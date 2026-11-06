package app.autohost.capabilities.a11y

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P12-07 (A3/RM-A2 integration): the click chain's coordinate stroke is AWAITED. The JVM-testable
 * parts: the pure stroke→outcome mapping (an awaited stroke that did not Inject is never a verified
 * click), and the [UiActions] seam default that keeps the recording fakes on the legacy chain.
 * `NodeActions.awaitedClick`'s node walk shares `runNodeChain` with the legacy path (the P12-05-era
 * conformance tables cover it through the fakes); the await itself is table-tested in
 * [GestureInjectorTest].
 */
class AwaitedClickChainTest {

    @Test
    fun `given a completed stroke when mapped then the outcome is a verified coordinate click`() {
        val outcome = NodeActions.awaitedOutcome(GestureInjector.Result.Injected)
        assertEquals(NodeActions.Route.COORDINATE, outcome.route)
        assertTrue(outcome.verified)
    }

    @Test
    fun `given each non-completed stroke when mapped then the click is not dispatched`() {
        for (result in listOf(
            GestureInjector.Result.Refused,
            GestureInjector.Result.Cancelled,
            GestureInjector.Result.TimedOut,
        )) {
            val outcome = NodeActions.awaitedOutcome(result)
            assertEquals("$result: route", null, outcome.route)
            assertEquals("$result: verified", false, outcome.verified)
        }
    }

    @Test
    fun `given a fake ui actions when awaited then the default delegates to the recording sync chain`() {
        // The fakes override the sync methods; the awaited default must route through them so the
        // existing conformance tables keep observing the same chain.
        val fake = object : UiActions {
            val calls = mutableListOf<String>()
            override fun click(info: android.view.accessibility.AccessibilityNodeInfo?): NodeActions.ClickOutcome {
                calls.add("click")
                return NodeActions.ClickOutcome(NodeActions.Route.NODE, true)
            }

            override fun longClick(info: android.view.accessibility.AccessibilityNodeInfo?): NodeActions.ClickOutcome {
                calls.add("longClick")
                return NodeActions.ClickOutcome(NodeActions.Route.NODE, true)
            }

            override fun setText(info: android.view.accessibility.AccessibilityNodeInfo?, text: String) = true
            override fun paste(info: android.view.accessibility.AccessibilityNodeInfo?) = true
            override fun tap(x: Float, y: Float) = true
            override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) = true
            override fun global(key: String) = true
        }
        val outcome = runBlocking { fake.awaitedClick(null) }
        assertEquals(NodeActions.Route.NODE, outcome.route)
        assertEquals(listOf("click"), fake.calls)
        runBlocking { fake.awaitedLongClick(null) }
        assertEquals(listOf("click", "longClick"), fake.calls)
    }
}
