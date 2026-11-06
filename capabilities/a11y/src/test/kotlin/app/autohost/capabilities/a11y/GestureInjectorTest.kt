package app.autohost.capabilities.a11y

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P12-06 (A3/RM-A2): the awaited injection over a fake dispatcher — the four RM-A2 outcomes plus
 * the ViewConfiguration timing rule. Under D-26 these are written, not run.
 */
class GestureInjectorTest {

    private val noTapFloor = GestureInjector.TapTimeoutSource { 0L }

    @Test
    fun `given a dispatcher whose callback fires true when awaited then Injected`() = runTest {
        var fired: ((Boolean) -> Unit)? = null
        val dispatcher = GestureInjector.Dispatcher { _, _, _, _, _, onDone ->
            fired = onDone
            true
        }
        val result = kotlinx.coroutines.async {
            GestureInjector.awaitStroke(dispatcher, 10f, 10f, 50f, 50f, 60L, noTapFloor)
        }
        // let the await start, then complete the gesture
        kotlinx.coroutines.yield()
        fired?.invoke(true)
        assertEquals(GestureInjector.Result.Injected, result.await())
    }

    @Test
    fun `given dispatched false when awaited then Refused (never a silent success)`() = runTest {
        val dispatcher = GestureInjector.Dispatcher { _, _, _, _, _, _ -> false }
        assertEquals(
            GestureInjector.Result.Refused,
            GestureInjector.awaitStroke(dispatcher, 10f, 10f, 50f, 50f, 60L, noTapFloor),
        )
    }

    @Test
    fun `given a callback that reports cancellation when awaited then Cancelled`() = runTest {
        var fired: ((Boolean) -> Unit)? = null
        val dispatcher = GestureInjector.Dispatcher { _, _, _, _, _, onDone ->
            fired = onDone
            true
        }
        val result = kotlinx.coroutines.async {
            GestureInjector.awaitStroke(dispatcher, 10f, 10f, 50f, 50f, 60L, noTapFloor)
        }
        kotlinx.coroutines.yield()
        fired?.invoke(false)
        assertEquals(GestureInjector.Result.Cancelled, result.await())
    }

    @Test
    fun `given a callback that never fires when awaited then TimedOut (bounded, not hung)`() = runTest {
        val dispatcher = GestureInjector.Dispatcher { _, _, _, _, _, _ -> true } // never fires the callback
        val result = withTimeoutOrNull(2_000) {
            GestureInjector.awaitStroke(dispatcher, 10f, 10f, 50f, 50f, 60L, noTapFloor, timeoutMs = 200)
        }
        // the injector's own timeout (200 ms) resolves the await to TimedOut well inside 2 s
        assertEquals(GestureInjector.Result.TimedOut, result)
    }

    @Test
    fun `given a tap shorter than the ViewConfiguration timeout when awaited then the duration is raised`() = runTest {
        var seenDuration: Long? = null
        val tapFloor = GestureInjector.TapTimeoutSource { 100L }
        val dispatcher = GestureInjector.Dispatcher { _, _, _, _, duration, _ ->
            seenDuration = duration
            true
        }
        GestureInjector.awaitStroke(dispatcher, 0f, 0f, 0f, 0f, durationMs = 10L, tapTimeoutSource = tapFloor)
        assertEquals(100L, seenDuration) // raised to the ViewConfiguration tap timeout
    }

    @Test
    fun `given a duration already above the floor when awaited then it is unchanged`() = runTest {
        var seenDuration: Long? = null
        val tapFloor = GestureInjector.TapTimeoutSource { 100L }
        val dispatcher = GestureInjector.Dispatcher { _, _, _, _, duration, _ ->
            seenDuration = duration
            true
        }
        GestureInjector.awaitStroke(dispatcher, 0f, 0f, 0f, 0f, durationMs = 600L, tapTimeoutSource = tapFloor)
        assertEquals(600L, seenDuration)
    }

    @Test
    fun `given a dispatcher that throws when awaited then Refused (the RuntimeException shape)`() = runTest {
        val dispatcher = GestureInjector.Dispatcher { _, _, _, _, _, _ -> throw RuntimeException("no service") }
        assertEquals(
            GestureInjector.Result.Refused,
            GestureInjector.awaitStroke(dispatcher, 0f, 0f, 0f, 0f, 60L, noTapFloor),
        )
    }

    private val noTapFloor: GestureInjector.TapTimeoutSource get() = GestureInjector.TapTimeoutSource { 0L }
}
