package app.autohost.fixture

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/**
 * GestureTargetScreen (docs/FIXTURE_APP.md v4, A3): a target that classifies tap / long-press /
 * swipe from the raw events and records each kind with a counter — optionally DELAYING the
 * recording by `delay_ms` (default 0), so the awaited-injection contract (the action completes
 * only when the OS callback fires) has a slow target to prove itself against.
 */
class GestureTargetScreenActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gesture_target)
        Screens.bind(this, "gesture")

        val delayMs = Screens.delayMs(this, default = 0)
        val status = findViewById<TextView>(R.id.fx_gesture_status)
        val target = findViewById<View>(R.id.fx_gesture)
        var downTime = 0L
        var downX = 0f
        var downY = 0f
        var moved = false

        target.setOnTouchListener { _, event: MotionEvent ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = event.eventTime
                    downX = event.x
                    downY = event.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (gestureDistance(downX, downY, event.x, event.y) > 60f) moved = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val kind = when {
                        moved -> "swipe"
                        event.eventTime - downTime >= 500L -> "long_press"
                        else -> "tap"
                    }
                    val elapsed = event.eventTime - downTime
                    handler.postDelayed({ record(kind, elapsed, status) }, delayMs)
                    true
                }
                else -> false
            }
        }
        Screens.refresh(this)
    }

    private fun record(kind: String, elapsedMs: Long, status: TextView) {
        FixtureState.recordClick("fx_${kind}_count")
        FixtureState.lastEvent = "gesture:$kind:${elapsedMs}ms"
        status.text = "last: $kind (${elapsedMs}ms)"
        FixtureState.persist(this)
        Screens.refresh(this)
    }

    /** The same Manhattan distance helper the provider uses for drag-vs-tap (kept consistent). */
    private fun gestureDistance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        kotlin.math.abs(x2 - x1) + kotlin.math.abs(y2 - y1)
}
