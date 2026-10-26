package app.autohost.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.autohost.core.designsystem.theme.StatusColors
import app.autohost.core.model.Availability
import app.autohost.core.model.RunStatus

private fun statusColor(status: RunStatus): Color = when (status) {
    RunStatus.QUEUED -> StatusColors.neutral
    RunStatus.STARTING -> StatusColors.running
    RunStatus.RUNNING -> StatusColors.running
    RunStatus.PAUSED -> StatusColors.warning
    RunStatus.WAITING_USER -> StatusColors.waiting
    RunStatus.SUCCEEDED -> StatusColors.success
    RunStatus.FAILED -> StatusColors.error
    RunStatus.CANCELLED -> StatusColors.neutral
    RunStatus.INTERRUPTED -> StatusColors.interrupted
    RunStatus.SKIPPED -> StatusColors.neutral
}

/** Status chip: QUEUED neutral · RUNNING (pulsing dot arrives P5) · SUCCEEDED success · FAILED error … */
@Composable
fun StatusChip(status: RunStatus, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Text(
        text = status.name,
        modifier = modifier
            .semantics { contentDescription = "Status: ${status.name}" }
            .background(color.copy(alpha = 0.16f), MaterialTheme.shapes.large)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * Text status chip with explicit tone flags (READY / NEEDS_SETUP / COORDINATES-style statuses).
 * P6-09: promoted to the shared components — TaskScreens and the recorder review screen both
 * used this String form (it previously lived only at call sites; the first-build "Compose drift"
 * note in HANDOFF §6).
 */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    success: Boolean = false,
    warning: Boolean = false,
    neutral: Boolean = false,
    error: Boolean = false,
) {
    val color = when {
        error -> StatusColors.error
        warning -> StatusColors.warning
        neutral -> StatusColors.neutral
        success -> StatusColors.success
        else -> StatusColors.running
    }
    Text(
        text = text,
        modifier = modifier
            .semantics { contentDescription = "Status: $text" }
            .background(color.copy(alpha = 0.16f), MaterialTheme.shapes.large)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Availability chip from docs/UI_SPEC.md §2, including the Fix hint codes. */
@Composable
fun AvailabilityChip(availability: Availability, modifier: Modifier = Modifier) {
    val (label, color) = when (availability) {
        is Availability.Available -> "AVAILABLE" to StatusColors.success
        is Availability.NeedsSetup -> "NEEDS_SETUP" to StatusColors.warning
        is Availability.Unsupported -> "UNSUPPORTED" to StatusColors.neutral
    }
    val detail = when (availability) {
        is Availability.NeedsSetup -> availability.reason
        is Availability.Unsupported -> availability.reason
        is Availability.Available -> ""
    }
    Text(
        text = label,
        modifier = modifier
            .semantics { contentDescription = if (detail.isEmpty()) label else "$label: $detail" }
            .background(color.copy(alpha = 0.16f), MaterialTheme.shapes.large)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

/** One monospace log line; level >= WARN is emphasised (docs/UI_SPEC.md §8). */
@Composable
fun LogLine(time: String, level: String, message: String, modifier: Modifier = Modifier) {
    val emphasis = level == "WARN" || level == "ERROR"
    androidx.compose.foundation.layout.Row(modifier = modifier) {
        Text(
            text = "$time  ",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
            color = if (level == "ERROR") StatusColors.error else if (level == "WARN") StatusColors.warning else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier = modifier.padding(24.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
