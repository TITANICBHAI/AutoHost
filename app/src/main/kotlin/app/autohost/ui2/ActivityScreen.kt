package app.autohost.ui2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.autohost.AutoHostApp
import app.autohost.MainActivity
import app.autohost.Routes
import app.autohost.core.designsystem.components.EmptyState
import app.autohost.core.designsystem.components.SectionHeader
import app.autohost.core.designsystem.components.StatusChip
import app.autohost.ui.TaskCatalog
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The Activity tab (the old Today and When, owner feedback 2026-11-14): what needs the person, what is running and what
 * finished, plus the way into schedules and triggers and the full history. The grouping is [TodayModel] (tested).
 */
@Composable
fun ActivityScreen(activity: MainActivity, onNavigate: (Routes, String?) -> Unit) {
    val graph = (activity.application as AutoHostApp).graph
    val entries by remember(graph) {
        TaskCatalog.observe(graph.data.tasks.observeActive(), graph.devTasks.tasks)
    }.collectAsState(initial = emptyList())
    val runs by remember(graph) { graph.data.runs.observeRecent(200) }.collectAsState(initial = emptyList())
    val model = remember(runs, entries) { TodayModel.build(runs, entries.associate { it.taskId to it.name }, recentLimit = 30) }
    val timeFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Activity", style = MaterialTheme.typography.headlineSmall)
            Text(
                "What needs you, what is running, and what finished.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onNavigate(Routes.SCHEDULES, null) }) { Text("Schedules and triggers") }
                OutlinedButton(onClick = { onNavigate(Routes.RUNS_HISTORY, null) }) { Text("Full history") }
            }
        }
        if (model.isEmpty) {
            item { EmptyState("Nothing has run yet", "When a task runs, it shows up here, with what it did and how it ended.") }
        }
        if (model.needsYou.isNotEmpty()) {
            item { SectionHeader("Needs you") }
            items(model.needsYou, key = { "need:" + it.runId }) { run ->
                RunCard(run, timeFormat.format(run.startedAt), onOpen = { onNavigate(Routes.RUN, run.runId) }) {
                    Button(onClick = { onNavigate(Routes.RUN, run.runId) }) { Text("Answer") }
                }
            }
        }
        if (model.active.isNotEmpty()) {
            item { SectionHeader("Running") }
            items(model.active, key = { "run:" + it.runId }) { run ->
                RunCard(run, timeFormat.format(run.startedAt), onOpen = { onNavigate(Routes.RUN, run.runId) }) {
                    Button(onClick = { graph.orchestrator.cancel(run.runId) }) { Text("Stop") }
                }
            }
        }
        if (model.recent.isNotEmpty()) {
            item { SectionHeader("Finished") }
            items(model.recent, key = { "done:" + it.runId }) { run ->
                RunCard(run, timeFormat.format(run.endedAt ?: run.startedAt), onOpen = { onNavigate(Routes.RUN, run.runId) }, action = null)
            }
        }
    }
}

@Composable
private fun RunCard(
    run: TodayRun,
    whenText: String,
    onOpen: () -> Unit,
    action: (@Composable () -> Unit)?,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(run.taskName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusChip(run.status)
            }
            val detail = listOfNotNull(
                run.stage,
                run.progress?.let { "${(it * 100).toInt()}%" },
                whenText,
            ).joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) action()
        }
    }
}
