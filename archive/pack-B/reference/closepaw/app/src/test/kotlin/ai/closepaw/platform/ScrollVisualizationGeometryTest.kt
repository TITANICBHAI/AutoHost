package ai.closepaw.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrollVisualizationGeometryTest {

    @Test
    fun `down scroll draws upward finger trail`() {
        val trail = ScrollVisualizationGeometry.compute(
            x = 500,
            y = 1000,
            direction = "down",
            display = DisplayInfo(widthPixels = 1000, heightPixels = 2000, density = 3f)
        )

        assertThat(trail)
            .isEqualTo(
                ScrollVisualizationTrail(
                    startX = 500f,
                    startY = 1200f,
                    endX = 500f,
                    endY = 800f,
                    durationMs = 300L
                )
            )
    }

    @Test
    fun `left scroll draws rightward finger trail`() {
        val trail = ScrollVisualizationGeometry.compute(
            x = 500,
            y = 1000,
            direction = "left",
            display = DisplayInfo(widthPixels = 1000, heightPixels = 2000, density = 3f)
        )

        assertThat(trail)
            .isEqualTo(
                ScrollVisualizationTrail(
                    startX = 300f,
                    startY = 1000f,
                    endX = 700f,
                    endY = 1000f,
                    durationMs = 300L
                )
            )
    }

    @Test
    fun `trail clamps to screen edges`() {
        val trail = ScrollVisualizationGeometry.compute(
            x = 5,
            y = 5,
            direction = "up",
            display = DisplayInfo(widthPixels = 1000, heightPixels = 2000, density = 3f)
        )

        assertThat(trail)
            .isEqualTo(
                ScrollVisualizationTrail(
                    startX = 10f,
                    startY = 10f,
                    endX = 10f,
                    endY = 205f,
                    durationMs = 300L
                )
            )
    }

    @Test
    fun `unknown direction has no visualization trail`() {
        val trail = ScrollVisualizationGeometry.compute(
            x = 500,
            y = 1000,
            direction = "diagonal",
            display = DisplayInfo(widthPixels = 1000, heightPixels = 2000, density = 3f)
        )

        assertThat(trail).isNull()
    }
}
