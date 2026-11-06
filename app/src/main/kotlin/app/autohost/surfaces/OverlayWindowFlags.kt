package app.autohost.surfaces

import android.view.WindowManager

/**
 * P12-07 (ACTION_HARDENING §5): the OVERLAY WINDOW-FLAG AUDIT as code. One table owns every
 * `WindowManager.LayoutParams` flag set the app creates, so the pass-through property of each
 * surface is testable on the JVM (the layout params themselves stay on the Android side).
 *
 * The audit (one `TYPE_ACCESSIBILITY_OVERLAY` window per surface):
 *
 * | Surface (file)                 | Flags                                   | Touch behaviour              |
 * |--------------------------------|-----------------------------------------|------------------------------|
 * | BubbleOverlay (bubble)         | NOT_FOCUSABLE + **NOT_TOUCH_MODAL**     | touchable INSIDE its bounds only |
 * | InspectorOverlay (inspector)   | NOT_FOCUSABLE + LAYOUT_IN_SCREEN        | full-screen BY DESIGN (it has its own exit control); shown only while the user inspects |
 * | UiTextOverlay (prompt text)    | NOT_FOCUSABLE + NOT_TOUCH_MODAL         | touchable inside, pass-through outside |
 * | RecorderOverlay (recorder)     | NOT_FOCUSABLE + NOT_TOUCH_MODAL         | touchable inside, pass-through outside |
 * | TaskOverlaySink (highlight/panel) | NOT_TOUCHABLE + NOT_FOCUSABLE        | fully pass-through (visual-only) |
 *
 * The run capsule and the action visualizer (`ui/capsule/`) are in-app Compose views — no
 * window, no flags, nothing to audit.
 *
 * The finding the audit produced: the bubble previously carried ONLY `FLAG_NOT_FOCUSABLE`.
 * Without `FLAG_NOT_TOUCH_MODAL` a window receives every touch ANYWHERE on screen — the bubble
 * blocked the whole display while it was up, real touches and injected strokes alike. That is
 * the one overlay swallowing case the audit found; the flag (not a runtime touch gate) is the
 * fix, so no gate was added (the row's "only if a test shows swallowing" condition).
 */
object OverlayWindowFlags {

    /** The draggable bubble: interactive inside its bounds, pass-through everywhere else. */
    const val BUBBLE: Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    /** The full-screen inspector: interactive by design (it owns the screen while shown). */
    const val INSPECTOR: Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    /** Transient text overlays (prompt/toast-like): tap-through outside the strip. */
    const val PROMPT_TEXT: Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    /** The recorder strip: tap-through outside its Stop/badge area. */
    const val RECORDER: Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    /** Visual-only run highlights and panels: never touchable at all. */
    const val TASK_VISUAL: Int =
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    /** The overlay type every surface shares: no app window may sit above system UI. */
    val TYPE: Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
}
