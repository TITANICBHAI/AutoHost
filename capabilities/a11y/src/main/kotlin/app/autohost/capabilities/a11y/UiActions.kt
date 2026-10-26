package app.autohost.capabilities.a11y

import android.accessibilityservice.AccessibilityService

/**
 * Indirection over the framework touchpoints so [A11yUiProvider]'s routing and argument handling stay
 * unit-testable on the JVM (fakes record the calls; the real impl talks to the service). Handles may be
 * null when a window vanished between snapshot and action — implementations report failure honestly.
 */
interface UiActions {
    fun click(info: android.view.accessibility.AccessibilityNodeInfo?): NodeActions.ClickOutcome
    fun longClick(info: android.view.accessibility.AccessibilityNodeInfo?): NodeActions.ClickOutcome
    fun setText(info: android.view.accessibility.AccessibilityNodeInfo?, text: String): Boolean
    fun paste(info: android.view.accessibility.AccessibilityNodeInfo?): Boolean
    fun tap(x: Float, y: Float): Boolean
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean
    fun global(key: String): Boolean
}

/** Real implementation: everything goes through the connected service. */
class ServiceUiActions(private val service: () -> AccessibilityService?) : UiActions {

    override fun click(info: android.view.accessibility.AccessibilityNodeInfo?): NodeActions.ClickOutcome {
        val active = service() ?: return NodeActions.ClickOutcome(null, false)
        info ?: return NodeActions.ClickOutcome(null, false)
        return NodeActions.click(active, info)
    }

    override fun longClick(info: android.view.accessibility.AccessibilityNodeInfo?): NodeActions.ClickOutcome {
        val active = service() ?: return NodeActions.ClickOutcome(null, false)
        info ?: return NodeActions.ClickOutcome(null, false)
        return NodeActions.longClick(active, info)
    }

    override fun setText(info: android.view.accessibility.AccessibilityNodeInfo?, text: String): Boolean {
        info ?: return false
        return NodeActions.setText(info, text)
    }

    override fun paste(info: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
        info ?: return false
        return NodeActions.paste(info)
    }

    override fun tap(x: Float, y: Float): Boolean {
        val active = service() ?: return false
        return Gestures.tap(active, x, y)
    }

    override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val active = service() ?: return false
        return Gestures.swipe(active, x1, y1, x2, y2, durationMs)
    }

    override fun global(key: String): Boolean {
        val active = service() ?: return false
        return GlobalActions.perform(active, key)
    }
}

/** Where "did the UI stop changing" comes from (A11yBridge tracks window events). */
fun interface WindowChangeSource {
    fun lastChangeMs(): Long
}
