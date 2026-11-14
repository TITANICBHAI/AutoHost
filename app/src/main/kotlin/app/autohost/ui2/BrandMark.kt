package app.autohost.ui2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/**
 * The AutoHost logo drawn in code, so it shows inside the app without depending on a drawable: a phone with a circuit
 * trace that ends in one amber action dot, on deep indigo. Same geometry as `ic_launcher_foreground.xml` (108 x 108 grid).
 */
@Composable
fun BrandMark(side: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(side)) {
        val s = this.size.minDimension / 108f
        val ink = Color(0xFFF4F1FF)
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF6A55FF), Color(0xFF2B1F9C)),
                start = Offset.Zero,
                end = Offset(108f * s, 108f * s),
            ),
            cornerRadius = CornerRadius(24f * s, 24f * s),
        )
        drawRoundRect(
            color = ink,
            topLeft = Offset(37f * s, 26f * s),
            size = Size(34f * s, 56f * s),
            cornerRadius = CornerRadius(8f * s, 8f * s),
            style = Stroke(width = 3.4f * s),
        )
        drawLine(ink, Offset(48.5f * s, 32f * s), Offset(59.5f * s, 32f * s), strokeWidth = 2.4f * s, cap = StrokeCap.Round)
        val trace = Path().apply {
            moveTo(46f * s, 70f * s)
            lineTo(46f * s, 58f * s)
            lineTo(62f * s, 58f * s)
            lineTo(62f * s, 45f * s)
        }
        drawPath(trace, ink, style = Stroke(width = 3f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(ink, radius = 4f * s, center = Offset(46f * s, 70f * s))
        drawCircle(Color(0xFFFFB020), radius = 5.4f * s, center = Offset(62f * s, 43f * s))
    }
}
