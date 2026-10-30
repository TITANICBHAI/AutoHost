package app.autohost.surfaces

import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import app.autohost.capabilities.a11y.A11yBridge
import app.autohost.capabilities.screen.UiOverlayProvider

/**
 * P8-10: the run-drawn overlay sink — highlight rectangles and progress panels over other apps,
 * attached as `TYPE_ACCESSIBILITY_OVERLAY` through the a11y service window (same mechanism as
 * the bubble/recorder strip; no SYSTEM_ALERT_WINDOW, honest no-op when the service is off).
 *
 * Visible and stoppable (§4 rule 7): every view is tagged with its run id; [clearRun] removes
 * one run's overlays on the run's terminal transition and [clearAll] rides the global kill
 * switch. One panel per run and one highlight per run (the newest replaces the older).
 */
class TaskOverlaySink : UiOverlayProvider.Sink {

    private val highlights = LinkedHashMap<String, View>() // runId → view
    private val panels = LinkedHashMap<String, View>() // runId → view

    override fun connected(): Boolean = A11yBridge.service.value != null

    override fun highlight(runId: String, x: Int, y: Int, w: Int, h: Int, label: String?, durationMs: Long): Boolean =
        MainThread.call(false) { highlightOnMain(runId, x, y, w, h, label, durationMs) }

    private fun highlightOnMain(
        runId: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        label: String?,
        durationMs: Long,
    ): Boolean {
        val service = A11yBridge.service.value ?: return false
        val wm = service.getSystemService(WindowManager::class.java) ?: return false
        detach(wm, highlights.remove(runId))
        val frame = android.widget.FrameLayout(service).apply {
            setBackgroundColor(0x33448AFF)
            background = android.graphics.drawable.GradientDrawable().apply {
                setStroke(6, Color.argb(255, 68, 138, 255))
                setColor(Color.argb(48, 68, 138, 255))
            }
            tag = runId
        }
        if (label != null) {
            frame.addView(TextView(service).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(8, 4, 8, 4)
                setBackgroundColor(0xCC141414.toInt())
            })
        }
        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        return try {
            wm.addView(frame, params)
            highlights[runId] = frame
            if (durationMs > 0) {
                android.os.Handler(service.mainLooper).postDelayed({
                    detach(wm, highlights.remove(runId))
                }, durationMs)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun panel(runId: String, text: String, corner: String): Boolean =
        MainThread.call(false) { panelOnMain(runId, text, corner) }

    private fun panelOnMain(runId: String, text: String, corner: String): Boolean {
        val service = A11yBridge.service.value ?: return false
        val wm = service.getSystemService(WindowManager::class.java) ?: return false
        detach(wm, panels.remove(runId))
        val strip = TextView(service).apply {
            this.text = text
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 13f
            setPadding(20, 10, 20, 10)
            setBackgroundColor(0xE6141414.toInt())
            tag = runId
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (corner == "bottom") Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        return try {
            wm.addView(strip, params)
            panels[runId] = strip
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun clear(runId: String): Int = MainThread.call(0) { clearOnMain(runId) }

    private fun clearOnMain(runId: String): Int {
        val service = A11yBridge.service.value ?: return 0
        val wm = service.getSystemService(WindowManager::class.java) ?: return 0
        var removed = 0
        listOf(highlights, panels).forEach { map ->
            map.remove(runId)?.let { detach(wm, it); removed += 1 }
        }
        return removed
    }

    /** The global kill switch / app shutdown: everything comes down. */
    fun clearAll() {
        MainThread.post { clearAllOnMain() }
    }

    private fun clearAllOnMain() {
        val service = A11yBridge.service.value ?: return
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        (highlights.values + panels.values).forEach { detach(wm, it) }
        highlights.clear()
        panels.clear()
    }

    private fun detach(wm: WindowManager, view: View?) {
        view ?: return
        runCatching { wm.removeView(view) }
    }
}
