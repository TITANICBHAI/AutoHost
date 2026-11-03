@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ariaagent.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ariaagent.mobile.ui.theme.ARIAColors
import com.ariaagent.mobile.ui.viewmodel.AgentViewModel
import com.ariaagent.mobile.ui.viewmodel.OrchestrationComponentUi
import com.ariaagent.mobile.ui.viewmodel.OrchestrationEventUi
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DiagnosticsScreen — surfaces the CentralOrchestrator subsystem (rounds 1–3)
 * that previously had no UI. Shows:
 *
 *   • Floating-chat overlay toggle (also requests SYSTEM_ALERT_WINDOW if missing)
 *   • Orchestrator availability + last refresh timestamp
 *   • Live component registry + per-component health (status, errors, restarts,
 *     circuit-breaker state, last heartbeat)
 *   • Recent orchestration bus events (last 50, color-coded by severity)
 *   • Last LLM problem-solver resolution
 *
 * Polls vm.refreshOrchestrationStatus() every 5 seconds while visible.
 */
@Composable
fun DiagnosticsScreen(
    vm: AgentViewModel = viewModel(),
    onBack: () -> Unit,
) {
    val status        by vm.orchestrationStatus.collectAsStateWithLifecycle()
    val events        by vm.orchestrationEvents.collectAsStateWithLifecycle()
    val overlayActive by vm.floatingChatActive.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.refreshOrchestrationStatus()
        vm.refreshFloatingChatActive()
        while (true) {
            delay(5_000)
            vm.refreshOrchestrationStatus()
            vm.refreshFloatingChatActive()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ARIAColors.Background)
    ) {
        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ARIAColors.Surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = ARIAColors.OnSurface)
            }
            Text(
                "Diagnostics",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = ARIAColors.OnSurface, fontWeight = FontWeight.Bold
                ),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { vm.refreshOrchestrationStatus() }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = ARIAColors.Muted)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── Floating-chat overlay toggle ────────────────────────────────
            ARIACard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Default.PictureInPicture,
                        contentDescription = null,
                        tint = if (overlayActive) ARIAColors.Success else ARIAColors.Muted,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Floating Chat Overlay",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = ARIAColors.OnSurface, fontWeight = FontWeight.SemiBold
                            )
                        )
                        Text(
                            if (overlayActive) "Visible — tap to dismiss"
                            else "Hidden — tap to summon (requires display-over-other-apps)",
                            style = MaterialTheme.typography.bodySmall.copy(color = ARIAColors.Muted)
                        )
                    }
                    Switch(
                        checked = overlayActive,
                        onCheckedChange = { vm.toggleFloatingChat() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor   = ARIAColors.Primary,
                            checkedTrackColor   = ARIAColors.Primary.copy(alpha = 0.4f),
                            uncheckedThumbColor = ARIAColors.Muted,
                            uncheckedTrackColor = ARIAColors.Surface,
                        )
                    )
                }
            }

            // ── Orchestrator header ─────────────────────────────────────────
            ARIACard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (status.available) ARIAColors.Success else ARIAColors.Muted)
                    )
                    Text(
                        if (status.available) "Orchestrator: ONLINE" else "Orchestrator: not started",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = if (status.available) ARIAColors.Success else ARIAColors.Muted,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    if (status.lastRefreshAt > 0L) {
                        Text(
                            "refreshed " + relativeTime(status.lastRefreshAt),
                            style = MaterialTheme.typography.labelSmall.copy(color = ARIAColors.Muted)
                        )
                    }
                }
                if (!status.available) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The CentralOrchestrator starts when the agent foreground service runs. " +
                        "Use Control → Start to bring it up; this screen will populate.",
                        style = MaterialTheme.typography.bodySmall.copy(color = ARIAColors.Muted)
                    )
                }
            }

            // ── Components ──────────────────────────────────────────────────
            if (status.components.isNotEmpty()) {
                Text(
                    "REGISTERED COMPONENTS (${status.components.size})",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = ARIAColors.Primary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                    )
                )
                status.components.forEach { c ->
                    ComponentRow(c)
                }
            }

            // ── Last LLM resolution ─────────────────────────────────────────
            if (status.lastResolution.isNotBlank()) {
                Text(
                    "LAST LLM RESOLUTION",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = ARIAColors.Accent, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                    )
                )
                ARIACard {
                    Column {
                        Text(
                            relativeTime(status.lastResolutionAt),
                            style = MaterialTheme.typography.labelSmall.copy(color = ARIAColors.Muted)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            status.lastResolution,
                            style = MaterialTheme.typography.bodySmall.copy(color = ARIAColors.OnSurface)
                        )
                    }
                }
            }

            // ── Recent events ───────────────────────────────────────────────
            Text(
                "RECENT ORCHESTRATION EVENTS (${events.size})",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = ARIAColors.Primary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
            )
            if (events.isEmpty()) {
                ARIACard {
                    Text(
                        "No events yet. Component lifecycle, health-degrade, error and " +
                        "state-diff signals will appear here as they happen.",
                        style = MaterialTheme.typography.bodySmall.copy(color = ARIAColors.Muted)
                    )
                }
            } else {
                events.forEach { evt -> EventRow(evt) }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

// ─── Sub-composables ──────────────────────────────────────────────────────────

@Composable
private fun ComponentRow(c: OrchestrationComponentUi) {
    val statusColor = when (c.status) {
        "ACTIVE"                 -> ARIAColors.Success
        "DEGRADED"               -> ARIAColors.Warning
        "ERROR", "ISOLATED"      -> ARIAColors.Error
        "INITIALIZING"           -> ARIAColors.Accent
        else                     -> ARIAColors.Muted
    }
    ARIACard(
        modifier = Modifier.border(1.dp, statusColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Text(
                    c.componentName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = ARIAColors.OnSurface, fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    c.status,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = statusColor, fontWeight = FontWeight.Bold
                    )
                )
            }
            Text(
                "id=${c.componentId}",
                style = MaterialTheme.typography.labelSmall.copy(color = ARIAColors.Muted)
            )
            if (c.capabilities.isNotEmpty()) {
                Text(
                    "caps: " + c.capabilities.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall.copy(color = ARIAColors.Muted)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricChip("ok",   c.successCount.toString(), ARIAColors.Success)
                MetricChip("err",  c.errorCount.toString(),
                    if (c.errorCount > 0) ARIAColors.Error else ARIAColors.Muted)
                MetricChip("seq-fail", c.consecutiveFailures.toString(),
                    if (c.consecutiveFailures > 0) ARIAColors.Warning else ARIAColors.Muted)
                MetricChip("restart", c.restartCount.toString(),
                    if (c.restartCount > 0) ARIAColors.Warning else ARIAColors.Muted)
                if (c.circuitBreakerOpen) {
                    MetricChip("BREAKER", "OPEN", ARIAColors.Error)
                }
            }
            Text(
                "heartbeat: " + relativeTime(c.lastHeartbeat),
                style = MaterialTheme.typography.labelSmall.copy(color = ARIAColors.Muted)
            )
        }
    }
}

@Composable
private fun MetricChip(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(color = ARIAColors.Muted)
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(
                color = color, fontWeight = FontWeight.Bold
            )
        )
    }
}

@Composable
private fun EventRow(evt: OrchestrationEventUi) {
    val color = when (evt.severity) {
        "error" -> ARIAColors.Error
        "warn"  -> ARIAColors.Warning
        else    -> ARIAColors.Muted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ARIAColors.Surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                evt.summary,
                style = MaterialTheme.typography.bodySmall.copy(color = ARIAColors.OnSurface)
            )
            Text(
                "${evt.eventType}  ·  ${formatClock(evt.timestamp)}",
                style = MaterialTheme.typography.labelSmall.copy(color = ARIAColors.Muted)
            )
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

private val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
private fun formatClock(ts: Long): String = clockFmt.format(Date(ts))

private fun relativeTime(ts: Long): String {
    if (ts <= 0L) return "—"
    val delta = (System.currentTimeMillis() - ts).coerceAtLeast(0L)
    return when {
        delta < 1_000L         -> "just now"
        delta < 60_000L        -> "${delta / 1_000}s ago"
        delta < 3_600_000L     -> "${delta / 60_000}m ago"
        delta < 86_400_000L    -> "${delta / 3_600_000}h ago"
        else                   -> "${delta / 86_400_000}d ago"
    }
}
