package app.autohost.capabilities.a11y

import android.view.accessibility.AccessibilityEvent

/**
 * P6-09: the recorder's event tap (plan §7.9). While a recording is armed, the accessibility
 * service forwards user-interaction events (clicks, long clicks, text commits) with the semantic
 * node info the `:runtime:flow` [app.autohost.runtime.flow.Recorder] needs. Armed-only forwarding
 * keeps the zero-cost rule: with the recorder off, onAccessibilityEvent does no extra work.
 *
 * The full screen snapshot is NOT taken here — the recorder controller (:app) reads it through
 * [A11yReader] right after the event arrives, so the tap stays cheap and never blocks the
 * accessibility callback.
 */
data class RecorderTapEvent(
    val kind: Kind,
    val pkg: String?,
    val className: String?,
    val viewId: String?,
    val text: String?,
    val desc: String?,
    val beforeText: String?,
    /** Acted node bounds in screen coordinates [l,t,r,b]; null when the source was gone. */
    val bounds: List<Int>?,
    val editable: Boolean,
    val password: Boolean,
    val tsMs: Long,
) {
    enum class Kind { CLICK, LONG_CLICK, TEXT }

    companion object {
        fun fromEvent(event: AccessibilityEvent, tsMs: Long): RecorderTapEvent? {
            val kind = when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> Kind.CLICK
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> Kind.LONG_CLICK
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> Kind.TEXT
                else -> return null
            }
            val source = try {
                event.source
            } catch (e: RuntimeException) {
                null // stale/gone node; fields below degrade to the event payload
            }
            val rect = android.graphics.Rect()
            val bounds: List<Int>? = source?.let {
                try {
                    it.getBoundsInScreen(rect)
                    listOf(rect.left, rect.top, rect.right, rect.bottom)
                } catch (e: RuntimeException) {
                    null
                }
            }
            fun safeText(seq: CharSequence?): String? = try {
                seq?.toString()
            } catch (e: RuntimeException) {
                null
            }
            return RecorderTapEvent(
                kind = kind,
                pkg = event.packageName?.toString(),
                className = event.className?.toString(),
                viewId = source?.viewIdResourceName, // view ids need flagReportViewIds (service config sets it)
                text = when (kind) {
                    Kind.TEXT -> safeText(event.afterText)
                    else -> source?.let { safeText(it.text) } ?: safeText(event.text?.firstOrNull())
                },
                desc = source?.let { safeText(it.contentDescription) },
                beforeText = if (kind == Kind.TEXT) safeText(event.beforeText) else null,
                bounds = bounds,
                editable = try {
                    source?.isEditable ?: false
                } catch (e: RuntimeException) {
                    false
                },
                password = try {
                    source?.isPassword ?: false
                } catch (e: RuntimeException) {
                    false
                },
                tsMs = tsMs,
            )
        }
    }
}
