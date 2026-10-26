package app.autohost.surfaces

import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.autohost.capabilities.a11y.A11yBridge

/**
 * P7-06: the floating control bubble (plan §10.3) — a small draggable strip over other apps with
 * the active-run status and a Stop button. Attached as `TYPE_ACCESSIBILITY_OVERLAY` through the
 * accessibility service's window manager (same mechanism as the recorder strip, P6-09), so it
 * needs no SYSTEM_ALERT_WINDOW grant and disappears honestly when the service is off.
 *
 * Drag: the strip follows the finger horizontally/vertically while the move stays within slop of
 * a straight drag; the Stop button keeps its own click handling. All failures are honest no-ops.
 */
class BubbleOverlay(
    private val statusText: () -> String,
    private val onStop: () -> Unit,
    private val onOpenApp: () -> Unit,
) {

    private var container: LinearLayout? = null
    private var statusRef: TextView? = null

    fun show() {
        if (container != null) return
        val service = A11yBridge.service.value ?: return
        val wm = try {
            service.getSystemService(WindowManager::class.java) ?: return
        } catch (e: RuntimeException) {
            return
        }
        val context = service
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xE6141414.toInt())
            setPadding(20, 10, 20, 10)
            gravity = Gravity.CENTER_VERTICAL
        }
        val status = TextView(context).apply {
            id = android.view.View.generateViewId()
            text = statusText()
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 13f
            setPadding(0, 0, 20, 0)
            maxLines = 1
        }
        val open = Button(context).apply {
            text = "Open"
            textSize = 11f
            setOnClickListener { onOpenApp() }
        }
        val stop = Button(context).apply {
            text = "Stop"
            textSize = 11f
            setOnClickListener { onStop() }
        }
        strip.addView(status)
        strip.addView(open)
        strip.addView(stop)

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32
            y = 96
        }
        strip.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    false // let the child buttons still work; we only track for drags
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (dragging || dx * dx + dy * dy > TOUCH_SLOP * TOUCH_SLOP) {
                        dragging = true
                        params.x = startX + dx
                        params.y = startY + dy
                        runCatching { wm.updateViewLayout(strip, params) }
                        view.performClick()
                    }
                    dragging
                }
                else -> dragging
            }
        }
        container = strip
        statusRef = status
        try {
            wm.addView(strip, params)
        } catch (e: RuntimeException) {
            container = null // overlay denied (some OEMs) — visibility stays on the notification
        }
    }

    fun updateStatus() {
        statusRef?.text = statusText()
    }

    fun hide() {
        val strip = container ?: return
        container = null
        statusRef = null
        val service = A11yBridge.service.value ?: return
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        try {
            wm.removeView(strip)
        } catch (e: RuntimeException) {
            // already gone
        }
    }

    val showing: Boolean get() = container != null

    private companion object {
        const val TOUCH_SLOP = 24
    }
}
