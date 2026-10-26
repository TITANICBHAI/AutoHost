package app.autohost.recorder

import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.autohost.capabilities.a11y.A11yBridge

/**
 * P6-09: the recorder overlay (plan §7.9, UI_SPEC S18) — a small always-on-top strip showing
 * ● REC, the live step count and a Stop button. Added as `TYPE_ACCESSIBILITY_OVERLAY` through
 * the *accessibility service's* window manager: that type may only be added by the service, and
 * it needs no SYSTEM_ALERT_WINDOW grant. Detached on stop/disconnect; all failures are honest
 * no-ops (a missing overlay must never break a recording session).
 */
class RecorderOverlay(
    private val stepCount: () -> Int,
    private val onStop: () -> Unit,
) {

    private var container: LinearLayout? = null

    /** Attaches the overlay via the connected accessibility service. No-op when disconnected. */
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
            setBackgroundColor(0xE6101010.toInt())
            setPadding(24, 12, 24, 12)
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = TextView(context).apply {
            text = "● REC"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val counter = TextView(context).apply {
            id = android.view.View.generateViewId()
            setTextColor(0xFFBDBDBD.toInt())
            textSize = 14f
            setPadding(24, 0, 24, 0)
        }
        val stop = Button(context).apply {
            text = "Stop"
            textSize = 12f
            setOnClickListener { onStop() }
        }
        strip.addView(badge)
        strip.addView(counter)
        strip.addView(stop)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 64
        }
        container = strip
        try {
            wm.addView(strip, params)
        } catch (e: RuntimeException) {
            container = null // overlay denied (some OEMs) — recording continues without it
        }
        counterRef = counter
    }

    private var counterRef: TextView? = null

    /** Refreshes the visible step counter (called from the controller's tap path). */
    fun updateCount() {
        counterRef?.text = stepCount().toString()
    }

    fun hide() {
        val strip = container ?: return
        container = null
        counterRef = null
        val service = A11yBridge.service.value ?: return
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        try {
            wm.removeView(strip)
        } catch (e: RuntimeException) {
            // already gone
        }
    }

    /** True while the overlay strip is attached (settings/tests surface this). */
    val showing: Boolean get() = container != null
}
