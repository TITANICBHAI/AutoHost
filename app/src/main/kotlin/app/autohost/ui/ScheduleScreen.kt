package app.autohost.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.autohost.AppGraph
import app.autohost.core.designsystem.components.EmptyState
import app.autohost.core.designsystem.components.SectionHeader
import app.autohost.core.model.ScheduleRule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * P4-12: the Schedules surface (docs/UI_SPEC S10): every armed rule with its next fire, last
 * result and the "timing may be late" badge (G4-e), plus an editor with a live **next 5 fires**
 * preview driven by the same [app.autohost.core.engine.schedule.ScheduleEngine] that arms them.
 * Honest states: invalid rules show their validation problems; event-source rules (Phase 8)
 * are listed as waiting for event support.
 */
private val FIRE_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatFire(epochMs: Long): String =
    FIRE_FORMAT.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))

@Composable
fun ScheduleScreen(graph: AppGraph) {
    val schedules by graph.data.schedules.observeAll().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<ScheduleRule?>(null) }

    if (editing != null) {
        ScheduleEditor(graph, editing!!, onDone = { editing = null })
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Schedules", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val lateTiming = schedules.any { it.enabled && it.lateTiming }
            if (lateTiming) {
                AssistChip(
                    onClick = {},
                    label = { Text("Timing may be late") },
                )
                Text(
                    "  exact alarms unavailable — WorkManager fallback",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        SectionHeader("ARMED RULES")
        if (schedules.isEmpty()) {
            EmptyState(
                title = "No schedules yet",
                body = "Open a task and add a schedule, or edit the rule JSON below.",
            )
            TextButton(onClick = { editing = ScheduleRule(id = "sch_new", task = "", type = "interval", everyMinutes = 30) }) {
                Text("+ New schedule (JSON)")
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(schedules, key = { it.scheduleId }) { state ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    val rule = ScheduleRule.fromJson(state.ruleJson)
                                    Text(
                                        rule?.summary() ?: state.scheduleId,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        state.taskId,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                Switch(
                                    checked = state.enabled,
                                    onCheckedChange = { on ->
                                        if (on) {
                                            rule?.let { graph.scheduleController.upsertAndArm(it.copy(enabled = true)) }
                                        } else {
                                            graph.scheduleController.disable(state.scheduleId)
                                        }
                                    },
                                )
                            }
                            Text(
                                when {
                                    !state.enabled -> "Disabled"
                                    state.nextFireAt != null ->
                                        "Next fire: ${formatFire(state.nextFireAt!!)}" +
                                            (if (state.proposedNextFireAt != null) " · script-proposed override armed" else "")
                                    else -> "No upcoming fire (limits or end reached)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            state.lastResult?.let { Text("Last: $it", style = MaterialTheme.typography.labelSmall) }
                            if (state.lateTiming) {
                                Text(
                                    "⚠ Timing may be late (exact alarms denied)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Row {
                                TextButton(onClick = { rule?.let { editing = it } }) { Text("Edit") }
                                TextButton(onClick = { graph.scheduleController.reconcile("manual") }) {
                                    Text("Reconcile now")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One-line human summary of a rule ("every 30 min · 2 profiles · sequential"). */
fun ScheduleRule.summary(): String = when (type) {
    "interval" -> "every ${everyMinutes ?: "?"} min"
    "daily" -> "daily at ${times.joinToString(", ")}"
    "weekly" -> "weekly ${days.joinToString(",")} at ${times.joinToString(", ")}"
    "cron" -> "cron $cron"
    "once" -> "once at $at"
    "window" -> "window $start–$end every ${everyMinutes ?: "?"} min"
    else -> type
} + (if (profileIds.isEmpty()) "" else " · ${profileIds.size} profiles · ${fanout.mode}")

@Composable
private fun ScheduleEditor(graph: AppGraph, initial: ScheduleRule, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var json by remember { mutableStateOf(app.autohost.schedule.stringifyRule(initial)) }
    val parsed = remember(json) { ScheduleRule.fromJson(json) }
    val problems = remember(json) { parsed?.validate() ?: listOf("not valid JSON") }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Edit schedule", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = json,
            onValueChange = { json = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            minLines = 10,
        )
        if (problems.isNotEmpty()) {
            problems.forEach { Text("• $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        } else {
            SectionHeader("NEXT 5 FIRES (preview)")
            val engine = graph.scheduleEngine
            val upcoming = parsed?.let { engine.nextFires(it, graph.clock.nowMs(), n = 5) } ?: emptyList()
            if (upcoming.isEmpty()) {
                Text("No upcoming fires — limits or window exhaust the rule.", style = MaterialTheme.typography.bodySmall)
            } else {
                upcoming.forEach { fire ->
                    Text(formatFire(fire), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Row(Modifier.padding(top = 12.dp)) {
            Button(
                enabled = problems.isEmpty() && parsed != null,
                onClick = {
                    parsed?.let { rule -> scope.launch { graph.scheduleController.upsertAndArm(rule) } }
                    onDone()
                },
            ) { Text("Save & arm") }
            OutlinedButton(onClick = onDone, modifier = Modifier.padding(start = 8.dp)) { Text("Cancel") }
        }
        Text(
            "The JSON shape is the same one the Python reference (autohost_ref/schedule.py) defines; " +
                "the next-fires preview runs the real engine.",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
