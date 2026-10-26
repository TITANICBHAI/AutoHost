package app.autohost.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Semantic palette from docs/UI_SPEC.md §2. Status colours never change between themes.
object StatusColors {
    val success = Color(0xFF2E7D32)
    val running = Color(0xFF1565C0)
    val waiting = Color(0xFF6A1B9A)
    val warning = Color(0xFFB26A00)
    val error = Color(0xFFC62828)
    val interrupted = Color(0xFFE65100)
    val neutral = Color(0xFF5F6B6D)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7C86),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB4EAEE),
    surface = Color(0xFFFAFCFC),
    onSurface = Color(0xFF171C1D),
    surfaceVariant = Color(0xFFE3EBEC),
    onSurfaceVariant = Color(0xFF3F4849),
    outline = Color(0xFF6F7B7D),
    error = StatusColors.error,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FD1D9),
    onPrimary = Color(0xFF00363A),
    primaryContainer = Color(0xFF004F55),
    surface = Color(0xFF101414),
    onSurface = Color(0xFFDEE3E4),
    surfaceVariant = Color(0xFF2B3335),
    onSurfaceVariant = Color(0xFFBAC8C9),
    outline = Color(0xFF8A9698),
    error = Color(0xFFEF9A9A),
)

/** AutoHost theme: calm utility tool, status-first (docs/UI_SPEC.md). */
@Composable
fun AutoHostTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AutoHostTypography,
        shapes = AutoHostShapes,
        content = content,
    )
}
