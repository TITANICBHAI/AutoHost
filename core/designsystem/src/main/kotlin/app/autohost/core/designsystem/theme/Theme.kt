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

// Brand: indigo = the phone and its surfaces, amber = the one thing that acts. Status colours stay as above.
private val LightColors = lightColorScheme(
    primary = Color(0xFF4B3FD8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E0FF),
    onPrimaryContainer = Color(0xFF160A7A),
    secondary = Color(0xFF5E5C71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E0F9),
    onSecondaryContainer = Color(0xFF1B192C),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA8),
    onTertiaryContainer = Color(0xFF2B1A00),
    background = Color(0xFFFBF9FF),
    onBackground = Color(0xFF1B1B23),
    surface = Color(0xFFFBF9FF),
    onSurface = Color(0xFF1B1B23),
    surfaceVariant = Color(0xFFE7E3F3),
    onSurfaceVariant = Color(0xFF47464F),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC8C5D1),
    error = StatusColors.error,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC6BFFF),
    onPrimary = Color(0xFF2A1CA0),
    primaryContainer = Color(0xFF3A2DB8),
    onPrimaryContainer = Color(0xFFE4E0FF),
    secondary = Color(0xFFC8C4DC),
    onSecondary = Color(0xFF302E42),
    secondaryContainer = Color(0xFF474459),
    onSecondaryContainer = Color(0xFFE4E0F9),
    tertiary = Color(0xFFFFC25C),
    onTertiary = Color(0xFF442C00),
    tertiaryContainer = Color(0xFF624000),
    onTertiaryContainer = Color(0xFFFFDEA8),
    background = Color(0xFF12111A),
    onBackground = Color(0xFFE5E1EC),
    surface = Color(0xFF12111A),
    onSurface = Color(0xFFE5E1EC),
    surfaceVariant = Color(0xFF2B2A36),
    onSurfaceVariant = Color(0xFFC9C5D3),
    outline = Color(0xFF928F9B),
    outlineVariant = Color(0xFF47464F),
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
