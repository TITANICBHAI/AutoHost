package app.autohost.fixture

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View

/**
 * CanvasScreen (docs/FIXTURE_APP.md): custom-drawn buttons with **no semantic nodes** — the canonical
 * `EMPTY_TREE`/coordinate/vision target. Clicks are pure geometry; taps land in the state readout.
 */
class CanvasScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val canvas = CanvasView(this)
        canvas.id = R.id.fx_canvas
        setContentView(canvas)
        Screens.bind(this, "canvas")
    }

    private class CanvasView(context: Context) : View(context) {

        private val paint = Paint().apply { isAntiAlias = true }
        private val leftRect = android.graphics.RectF(60f, 300f, 460f, 500f)
        private val rightRect = android.graphics.RectF(620f, 300f, 1020f, 500f)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.rgb(235, 235, 235))
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(66, 129, 244)
            canvas.drawRoundRect(leftRect, 24f, 24f, paint)
            canvas.drawRoundRect(rightRect, 24f, 24f, paint)
            paint.color = Color.WHITE
            paint.textSize = 56f
            canvas.drawText("Canvas Left", 140f, 420f, paint)
            canvas.drawText("Canvas Right", 680f, 420f, paint)
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.action != android.view.MotionEvent.ACTION_UP) return true
            val point = android.graphics.RectF(event.x, event.y, event.x + 1, event.y + 1)
            when {
                point.intersects(leftRect.left, leftRect.top, leftRect.right, leftRect.bottom) -> {
                    FixtureState.recordClick("fx_canvas_left")
                    Screens.refresh(context as Activity)
                }
                point.intersects(rightRect.left, rightRect.top, rightRect.right, rightRect.bottom) -> {
                    FixtureState.recordClick("fx_canvas_right")
                    Screens.refresh(context as Activity)
                }
            }
            return true
        }

        private fun android.graphics.RectF.intersects(l: Float, t: Float, r: Float, b: Float): Boolean =
            left < r && right > l && top < b && bottom > t
    }
}
