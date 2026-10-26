package app.autohost.prompt

import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import app.autohost.capabilities.a11y.A11yBridge

/**
 * P7-04 (`ui.overlay`, plan §10.2): a short-lived text strip over the current app, attached as
 * `TYPE_ACCESSIBILITY_OVERLAY` through the accessibility service's window manager — the same
 * mechanism as the recorder overlay (P6-09), no SYSTEM_ALERT_WINDOW grant needed. Honest verdict:
 * when the accessibility service is disconnected (or the add fails) it reports `shown=false`
 * instead of pretending, and the script can fall back to `ui.toast`.
 */
class UiTextOverlay(private val clock: app.autohost.core.common.Clock) {

    private val handler = Handler(Looper.getMainLooper())
    private var attached: TextView? = null

    /** Must be called from a background thread safe context; attaches on the main thread synchronously. */
    fun show(text: String, durationMs: Int): Boolean {
        detach()
        var shown = false
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try {
                val service = A11yBridge.service.value
                val wm = service?.getSystemService(WindowManager::class.java)
                if (service != null && wm != null) {
                    val view = TextView(service).apply {
                        this.text = text
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        setBackgroundColor(0xE6101010.toInt())
                        setPadding(24, 16, 24, 16)
                    }
                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT,
                    ).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        y = 120
                    }
                    wm.addView(view, params)
                    attached = view
                    shown = true
                }
            } catch (e: RuntimeException) {
                shown = false // overlay failures are honest no-ops (recorder-overlay rule)
            } finally {
                latch.countDown()
            }
        }
        latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (shown) {
            handler.postDelayed({ detach() }, durationMs.toLong())
        }
        return shown
    }

    private fun detach() {
        val view = attached ?: return
        attached = null
        val service = A11yBridge.service.value ?: return
        runCatching {
            service.getSystemService(WindowManager::class.java)?.removeView(view)
        }
    }
}
