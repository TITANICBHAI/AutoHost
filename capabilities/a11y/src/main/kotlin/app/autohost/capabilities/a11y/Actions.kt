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

    enum class Route { NODE, PARENT, COORDINATE }

    data class ClickOutcome(val route: Route?, val verified: Boolean)

    fun click(service: AccessibilityService, info: AccessibilityNodeInfo): ClickOutcome {
        if (perform(info, AccessibilityNodeInfo.ACTION_CLICK)) return ClickOutcome(Route.NODE, true)
        var ancestor = safeParent(info)
        var hops = 0
        while (ancestor != null && hops < 8) {
            if (ancestor.isClickable && perform(ancestor, AccessibilityNodeInfo.ACTION_CLICK)) {
                return ClickOutcome(Route.PARENT, true)
            }
            ancestor = safeParent(ancestor)
            hops++
        }
        val rect = UiTreeBuilder.safeBounds(info)
        val dispatched = Gestures.tap(service, rect.exactCenterX(), rect.exactCenterY())
        return ClickOutcome(if (dispatched) Route.COORDINATE else null, dispatched)
    }

    fun longClick(service: AccessibilityService, info: AccessibilityNodeInfo): ClickOutcome {
        if (perform(info, AccessibilityNodeInfo.ACTION_LONG_CLICK)) return ClickOutcome(Route.NODE, true)
        val rect = UiTreeBuilder.safeBounds(info)
        val dispatched = Gestures.longPress(service, rect.exactCenterX(), rect.exactCenterY())
        return ClickOutcome(if (dispatched) Route.COORDINATE else null, dispatched)
    }

    /**
     * ACTION_SET_TEXT then verify by re-reading the field (plan §7.4). Returns verified=true only when the
     * field now holds exactly [text]; TEXT_INPUT_UNVERIFIED is reported by the provider otherwise.
     */
    fun setText(info: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val applied = perform(info, AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!applied) return false
        val now = UiTreeBuilder.safeText(info)
        return now == text
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
