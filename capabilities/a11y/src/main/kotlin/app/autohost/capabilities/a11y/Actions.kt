package app.autohost.capabilities.a11y

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * The click chain (HANDOFF §3): try the node's own ACTION_CLICK, then the nearest clickable ancestor, then a
 * gesture tap at the centre of the node. The result is honest about which route worked.
 */
object NodeActions {

    /** Stroke durations for the coordinate fallback (Gestures' legacy defaults, kept identical). */
    const val GESTURE_TAP_MS = 60L
    const val GESTURE_LONG_PRESS_MS = 600L

    enum class Route { NODE, PARENT, COORDINATE }

    data class ClickOutcome(val route: Route?, val verified: Boolean)

    fun click(service: AccessibilityService, info: AccessibilityNodeInfo): ClickOutcome =
        runNodeChain(info, AccessibilityNodeInfo.ACTION_CLICK) { rect ->
            val dispatched = Gestures.tap(service, rect.exactCenterX(), rect.exactCenterY())
            ClickOutcome(if (dispatched) Route.COORDINATE else null, dispatched)
        }

    fun longClick(service: AccessibilityService, info: AccessibilityNodeInfo): ClickOutcome =
        runNodeChain(info, AccessibilityNodeInfo.ACTION_LONG_CLICK) { rect ->
            val dispatched = Gestures.longPress(service, rect.exactCenterX(), rect.exactCenterY())
            ClickOutcome(if (dispatched) Route.COORDINATE else null, dispatched)
        }

    /**
     * P12-07 (A3/RM-A2 integration): the click chain with the coordinate stroke **awaited**.
     * The node/parent routes are identical to [click]; the coordinate fallback goes through
     * [GestureInjector.awaitStroke], so a refused/cancelled/hung stroke is a typed not-dispatched
     * outcome — never "dispatchGesture returned true" mistaken for "the tap happened".
     */
    suspend fun awaitedClick(
        service: AccessibilityService?,
        info: AccessibilityNodeInfo,
        tapTimeoutSource: GestureInjector.TapTimeoutSource = ViewTapTimeoutSource,
        timeoutMs: Long = GestureInjector.DEFAULT_TIMEOUT_MS,
    ): ClickOutcome = runNodeChain(info, AccessibilityNodeInfo.ACTION_CLICK) { rect ->
        awaitedOutcome(
            GestureInjector.awaitStroke(
                ServiceGestureDispatcher { service },
                rect.exactCenterX(), rect.exactCenterY(), rect.exactCenterX(), rect.exactCenterY(),
                durationMs = GESTURE_TAP_MS,
                tapTimeoutSource = tapTimeoutSource,
                timeoutMs = timeoutMs,
            ),
        )
    }

    /** The awaited long-press chain (the coordinate fallback awaits a long-press stroke). */
    suspend fun awaitedLongClick(
        service: AccessibilityService?,
        info: AccessibilityNodeInfo,
        tapTimeoutSource: GestureInjector.TapTimeoutSource = ViewTapTimeoutSource,
        timeoutMs: Long = GestureInjector.DEFAULT_TIMEOUT_MS,
    ): ClickOutcome = runNodeChain(info, AccessibilityNodeInfo.ACTION_LONG_CLICK) { rect ->
        awaitedOutcome(
            GestureInjector.awaitStroke(
                ServiceGestureDispatcher { service },
                rect.exactCenterX(), rect.exactCenterY(), rect.exactCenterX(), rect.exactCenterY(),
                durationMs = GESTURE_LONG_PRESS_MS,
                tapTimeoutSource = tapTimeoutSource,
                timeoutMs = timeoutMs,
            ),
        )
    }

    /**
     * The pure outcome mapping (P12-07): an awaited stroke counts as a coordinate click ONLY when
     * the OS callback reported completion — Refused/Cancelled/TimedOut are all not-dispatched
     * (route=null), which the provider reports as the typed NOT_DISPATCHED error.
     */
    fun awaitedOutcome(result: GestureInjector.Result): ClickOutcome = when (result) {
        GestureInjector.Result.Injected -> ClickOutcome(Route.COORDINATE, true)
        else -> ClickOutcome(null, false)
    }

    /** The shared node→ancestor→coordinate chain; the coordinate step is the caller's policy. */
    private inline fun runNodeChain(
        info: AccessibilityNodeInfo,
        action: Int,
        coordinateFallback: (android.graphics.Rect) -> ClickOutcome,
    ): ClickOutcome {
        if (perform(info, action)) return ClickOutcome(Route.NODE, true)
        var ancestor = safeParent(info)
        var hops = 0
        while (ancestor != null && hops < 8) {
            if (ancestor.isClickable && perform(ancestor, action)) {
                return ClickOutcome(Route.PARENT, true)
            }
            ancestor = safeParent(ancestor)
            hops++
        }
        return coordinateFallback(UiTreeBuilder.safeBounds(info))
    }

    /**
     * ACTION_SET_TEXT then verify by re-reading the field (plan §7.4). Returns verified=true only when the
     * field now holds exactly [text]; TEXT_INPUT_UNVERIFIED is reported by the provider otherwise.
     *
     * P12-05 (A1/RM-A1): the verify path is [setTextVia] — perform → **refresh()** → read. The old
     * order read the SAME un-refreshed node (a stale snapshot could report a successful set as
     * rejected, and the chain then fell through to IME/paste → doubled text).
     */
    fun setText(info: AccessibilityNodeInfo, text: String): Boolean = setTextVia(
        object : SetTextHandle {
            override fun performSetText(newText: String): Boolean {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                }
                return perform(info, AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            override fun refresh(): Boolean = runCatching { info.refresh() }.getOrDefault(false)
            override fun readText(): String = UiTreeBuilder.safeText(info)
        },
        text,
    )

    /**
     * The A1 ORDER, pure and JVM-testable: perform → refresh() → read. [handle] implementations
     * that read before refreshing report stale text and fail verification (the test pins the order).
     */
    fun setTextVia(handle: SetTextHandle, text: String): Boolean {
        if (!handle.performSetText(text)) return false
        handle.refresh()
        return handle.readText() == text
    }

    /** The node seam for the set_text verify order (RM-A1's NodeHandle, shaped for ACTION_SET_TEXT). */
    interface SetTextHandle {
        fun performSetText(newText: String): Boolean
        fun refresh(): Boolean
        fun readText(): String
    }

    /** ACTION_PASTE on the node (clipboard strategy of the set_text chain; the clipboard itself is set by the caller). */
    fun paste(info: AccessibilityNodeInfo): Boolean =
        perform(info, AccessibilityNodeInfo.ACTION_PASTE)

    fun scrollable(info: AccessibilityNodeInfo): Boolean = UiTreeBuilder.safeBool { info.isScrollable }

    private fun safeParent(info: AccessibilityNodeInfo): AccessibilityNodeInfo? = try {
        info.parent
    } catch (e: RuntimeException) {
        null
    }

    private fun perform(
        info: AccessibilityNodeInfo,
        action: Int,
        args: Bundle? = null,
    ): Boolean = try {
        info.performAction(action, args ?: Bundle.EMPTY)
    } catch (e: RuntimeException) {
        false
    }
}

/** Gesture dispatch (tap/swipe/long-press) through the service's stroke API. */
object Gestures {

    fun tap(service: AccessibilityService, x: Float, y: Float, durationMs: Long = 60): Boolean =
        swipe(service, x, y, x, y, durationMs)

    fun longPress(service: AccessibilityService, x: Float, y: Float, durationMs: Long = 600): Boolean =
        swipe(service, x, y, x, y, durationMs)

    fun swipe(service: AccessibilityService, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = android.graphics.Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        return try {
            service.dispatchGesture(gesture, null, null)
        } catch (e: RuntimeException) {
            false
        }
    }
}

/** Back/home/recents/notifications through the service's global actions; `press` maps here (HANDOFF §3). */
object GlobalActions {
    const val BACK = "back"
    const val HOME = "home"
    const val RECENTS = "recents"
    const val NOTIFICATIONS = "notifications"
    const val QUICK_SETTINGS = "quick_settings"

    fun perform(service: AccessibilityService, key: String): Boolean {
        val action = when (key) {
            BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        return try {
            service.performGlobalAction(action)
        } catch (e: RuntimeException) {
            false
        }
    }

    fun known(): Set<String> = setOf(BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS)
}

/** Distance helper for drag-vs-tap decisions (kept pure for tests). */
fun gestureDistance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
    abs(x2 - x1) + abs(y2 - y1) // Manhattan distance is enough for wave 1
