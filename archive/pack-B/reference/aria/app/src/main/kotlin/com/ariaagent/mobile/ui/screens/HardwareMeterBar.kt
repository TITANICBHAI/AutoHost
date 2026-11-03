package com.ariaagent.mobile.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ariaagent.mobile.core.system.HardwareMonitor.HardwareStats
import com.ariaagent.mobile.ui.theme.ARIAColors

// ── Color helpers ────────────────────────────────────────────────────────────

/** Returns green → amber → red as the percentage climbs through 50% and 75%. */
private fun meterColor(pct: Int): Color = when {
    pct < 0   -> ARIAColors.TextMuted          // unavailable
    pct < 50  -> ARIAColors.Success
    pct < 75  -> ARIAColors.Warning
    else      -> ARIAColors.Error
}

// ── Single labelled gauge bar ────────────────────────────────────────────────

/**
 * One labelled progress bar with an animated fill.
 *
 * @param label   "CPU" / "GPU" / "RAM"
 * @param pct     0-100, or -1 to display "n/a"
 * @param height  bar thickness
 */
@Composable
fun SingleMeterBar(
    label: String,
    pct: Int,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
) {
    val clampedPct    = pct.coerceIn(0, 100)
    val displayUnavail = pct < 0
    val color         = meterColor(pct)
    val animatedFrac  by animateFloatAsState(
        targetValue    = if (displayUnavail) 0f else clampedPct / 100f,
        animationSpec  = tween(durationMillis = 600),
        label          = "meter_$label"
    )

    Column(modifier = modifier) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                text  = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    color      = ARIAColors.TextSecondary,
                    fontSize   = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.8.sp
                )
            )
            Text(
                text  = if (displayUnavail) "n/a" else "$clampedPct%",
                style = MaterialTheme.typography.labelSmall.copy(
                    color      = if (displayUnavail) ARIAColors.TextMuted else color,
                    fontSize   = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(ARIAColors.Surface3)
        ) {
            if (!displayUnavail) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = animatedFrac)
                        .clip(RoundedCornerShape(height / 2))
                        .background(color)
                )
            }
        }
    }
}

// ── Three-gauge row (full UI) ────────────────────────────────────────────────

/**
 * Full-width row of three hardware gauges: CPU / GPU / RAM.
 *
 * Placed on Dashboard, Chat and Control screens.
 * Automatically collects [HardwareStats] from the ViewModel StateFlow;
 * callers only need to pass the current [stats] snapshot.
 */
@Composable
fun HardwareMeterRow(
    stats: HardwareStats,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier            = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                "HARDWARE",
                style = MaterialTheme.typography.labelSmall.copy(
                    color         = ARIAColors.Primary,
                    fontSize      = 9.sp,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            )
            // RAM MB label
            if (stats.ramTotalMb > 0) {
                Text(
                    "${stats.ramUsedMb} / ${stats.ramTotalMb} MB",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color      = ARIAColors.TextMuted,
                        fontSize   = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SingleMeterBar(label = "CPU", pct = stats.cpuPercent, modifier = Modifier.weight(1f))
            SingleMeterBar(label = "GPU", pct = stats.gpuPercent, modifier = Modifier.weight(1f))
            SingleMeterBar(label = "RAM", pct = stats.ramPercent, modifier = Modifier.weight(1f))
        }
    }
}

// ── Ultra-compact overlay version ────────────────────────────────────────────

/**
 * Single-line mini stats strip for the floating bubble header.
 * Uses coloured text chips instead of bars to save vertical space.
 */
@Composable
fun HardwareMiniStrip(
    stats: HardwareStats,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier              = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        MiniChip(label = "CPU", pct = stats.cpuPercent)
        MiniChip(label = "GPU", pct = stats.gpuPercent)
        MiniChip(label = "RAM", pct = stats.ramPercent)
    }
}

@Composable
private fun MiniChip(label: String, pct: Int) {
    val color  = meterColor(pct)
    val text   = if (pct < 0) "$label n/a" else "$label ${pct}%"
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text  = text,
            style = MaterialTheme.typography.labelSmall.copy(
                color      = if (pct < 0) ARIAColors.TextMuted else color,
                fontSize   = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        )
    }
}
