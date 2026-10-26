package app.autohost.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** 12 dp cards, 20 dp chips (docs/UI_SPEC.md §2). */
val AutoHostShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
)

/** M3 scale; monospace for logs, selectors and the compact dump. */
val AutoHostTypography = Typography().copy(
    labelSmall = Typography().labelSmall.copy(fontFamily = FontFamily.Default),
)
