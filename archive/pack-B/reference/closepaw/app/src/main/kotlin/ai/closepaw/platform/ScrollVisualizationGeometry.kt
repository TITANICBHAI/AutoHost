package ai.closepaw.platform

import kotlin.math.min

internal data class ScrollVisualizationTrail(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val durationMs: Long,
)

internal object ScrollVisualizationGeometry {
    private const val DURATION_MS = 300L
    private const val DISTANCE_RATIO = 0.4f
    private const val MIN_DISTANCE_PX = 160f
    private const val MAX_DISTANCE_PX = 600f
    private const val EDGE_PADDING_PX = 10f

    fun compute(
        x: Int,
        y: Int,
        direction: String,
        display: DisplayInfo,
    ): ScrollVisualizationTrail? {
        if (display.widthPixels <= 0 || display.heightPixels <= 0) return null

        val halfDistance = scrollDistance(display) / 2f
        val centerX = x.toFloat()
        val centerY = y.toFloat()
        val (startX, startY, endX, endY) =
            when (direction) {
                "down" -> listOf(centerX, centerY + halfDistance, centerX, centerY - halfDistance)
                "up" -> listOf(centerX, centerY - halfDistance, centerX, centerY + halfDistance)
                "left" -> listOf(centerX - halfDistance, centerY, centerX + halfDistance, centerY)
                "right" -> listOf(centerX + halfDistance, centerY, centerX - halfDistance, centerY)
                else -> return null
            }

        return ScrollVisualizationTrail(
            startX = clamp(startX, display.widthPixels),
            startY = clamp(startY, display.heightPixels),
            endX = clamp(endX, display.widthPixels),
            endY = clamp(endY, display.heightPixels),
            durationMs = DURATION_MS,
        )
    }

    private fun scrollDistance(display: DisplayInfo): Float {
        val rawDistance = min(display.widthPixels, display.heightPixels) * DISTANCE_RATIO
        return rawDistance.coerceIn(MIN_DISTANCE_PX, MAX_DISTANCE_PX)
    }

    private fun clamp(value: Float, extent: Int): Float {
        val upper = (extent.toFloat() - EDGE_PADDING_PX).coerceAtLeast(EDGE_PADDING_PX)
        return value.coerceIn(EDGE_PADDING_PX, upper)
    }
}
