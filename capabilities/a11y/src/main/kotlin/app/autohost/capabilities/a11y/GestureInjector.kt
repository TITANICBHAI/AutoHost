package app.autohost.capabilities.a11y

// Adapted from closepaw platform/AccessibilityGestureInjector.kt (Apache-2.0, Copyright 2026
// ClosePaw Authors). Changes: the injector is shaped for AutoHost's stroke-parameter seam (the
// GestureDescription building stays on the Android side of the dispatcher); the typed Result
// replaces exceptions; the tap timing is an injected source (`ViewConfiguration.getTapTimeout()`
// in production) so the await logic is pure JVM (docs/REUSE_MATRIX.md RM-A2, P12-06).

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * P12-06 (A3, RM-A2): AWAITED gesture injection — the fire-and-forget
 * `dispatchGesture(gesture, null, null)` is gone for every caller that uses this injector.
 *
 * One call = dispatch → await the OS callback → a typed [GestureInjector.Result]:
 *  - the OS refused outright (`dispatched == false`) → `Refused`
 *  - the completion callback never fires within the timeout → `TimedOut`
 *  - the callback reports cancellation → `Cancelled`
 *  - the callback reports completion → `Injected`
 * A `CancellationException` from the surrounding coroutine is rethrown, never swallowed.
 */
object GestureInjector {

    /** The default gesture-await budget — 5 s (docs/MODEL_GATEWAY §5's placeholder convention). */
    const val DEFAULT_TIMEOUT_MS = 5_000L

    sealed class Result {
        /** The OS accepted the gesture and the completion callback reported success. */
        data object Injected : Result()

        /** The OS refused the gesture outright (`dispatchGesture` returned false). */
        data object Refused : Result()

        /** The completion callback reported cancellation. */
        data object Cancelled : Result()

        /** The completion callback never fired within the timeout. */
        data object TimedOut : Result()
    }

    /**
     * The dispatcher seam. The production implementation builds the `GestureDescription` from the
     * stroke parameters and calls `AccessibilityService.dispatchGesture(gesture, callback, null)`;
     * the fake scripts callback behavior for the JVM tests (RM-A2).
     */
    fun interface Dispatcher {
        /**
         * Starts the gesture. Returns false when the OS refused outright. [onDone] fires ONCE:
         * `true` = the gesture completed, `false` = it was cancelled. Implementations must
         * guarantee the callback fires (or the await times out).
         */
        fun dispatchStroke(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, onDone: (Boolean) -> Unit): Boolean
    }

    /** The tap-timing source: production reads `ViewConfiguration.getTapTimeout()`. */
    fun interface TapTimeoutSource {
        fun tapTimeoutMs(): Long
    }

    /**
     * Awaits one stroke. The minimum duration is the ViewConfiguration tap timeout (a tap shorter
     * than it can be misread as a rejected touch — RM-A2's timing rule). The wait is bounded by
     * [timeoutMs]; the surrounding coroutine's cancellation propagates.
     */
    suspend fun awaitStroke(
        dispatcher: Dispatcher,
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long,
        tapTimeoutSource: TapTimeoutSource,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result {
        val effective = durationMs.coerceAtLeast(tapTimeoutSource.tapTimeoutMs())
        val completed = CompletableDeferred<Boolean>()
        val dispatched = try {
            dispatcher.dispatchStroke(x1, y1, x2, y2, effective) { ok -> completed.complete(ok) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            return Result.Refused
        }
        if (!dispatched) return Result.Refused
        return when (completed.awaitResult(timeoutMs)) {
            true -> Result.Injected
            false -> Result.Cancelled
            null -> Result.TimedOut
        }
    }

    private suspend fun CompletableDeferred<Boolean>.awaitResult(timeoutMs: Long): Boolean? =
        withTimeoutOrNull(timeoutMs) { await() }
}

/**
 * The production dispatcher over `AccessibilityService.dispatchGesture` — builds the stroke from
 * the parameters and reports the OS callback through [GestureInjector.Dispatcher]'s contract.
 */
class ServiceGestureDispatcher(
    private val service: () -> android.accessibilityservice.AccessibilityService?,
) : GestureInjector.Dispatcher {

    override fun dispatchStroke(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long,
        onDone: (Boolean) -> Unit,
    ): Boolean {
        val active = service() ?: return false
        val path = android.graphics.Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return try {
            active.dispatchGesture(
                gesture,
                object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        onDone(true)
                    }

                    override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        onDone(false)
                    }
                },
                null,
            )
        } catch (e: RuntimeException) {
            false
        }
    }
}
