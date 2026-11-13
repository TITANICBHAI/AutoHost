package app.autohost.ui2

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.autohost.AutoHostApp
import app.autohost.MainActivity
import app.autohost.Routes
import app.autohost.core.designsystem.components.SectionHeader
import app.autohost.core.designsystem.components.StatusChip
import app.autohost.ui.TaskCatalog
import app.autohost.ui.TaskListModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UI v2 "Today" tab (docs/UI_V2.md §2): what needs the person now, what is running (with Stop), the unfinished setup,
 * and the last few results. Drawn from [TodayModel] and [PhonePlan]; no rules live in this file.
 */
@Composable
fun TodayScreen(activity: MainActivity, onNavigate: (Routes, String?) -> Unit, onOpenPhone: () -> Unit) {
    val graph = (activity.application as AutoHostApp).graph
    val entries by remember(graph) {
        TaskCatalog.observe(graph.data.tasks.observeActive(), graph.devTasks.tasks)
    }.collectAsState(initial = emptyList())
    val runs by remember(graph) { graph.data.runs.observeRecent(100) }.collectAsState(initial = emptyList())
    val labs by graph.labsEnabled.collectAsState()
    var live by remember { mutableStateOf(LiveAccess()) }
    LaunchedEffect(Unit) {
        graph.loadLabsEnabled()
        live = withContext(Dispatchers.IO) { LiveAccessReader.read(activity, graph) }
    }
    val model = remember(runs, entries) { TodayModel.build(runs, entries.associate { it.taskId to it.name }) }
    val steps = remember(live) { PhonePlan.build(live, android.os.Build.VERSION.SDK_INT).steps }
    val now = System.currentTimeMillis()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Today", style = MaterialTheme.typography.headlineSmall) }

        if (model.needsYou.isNotEmpty()) {
            item { SectionHeader("Needs you") }
            items(model.needsYou, key = { "n" + it.runId }) { run ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(run.taskName, style = MaterialTheme.typography.titleMedium)
                        Text("Waiting for you", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { onNavigate(Routes.RUN, run.runId) }) { Text("Open") }
                    }
                }
            }
        }

        if (model.active.isNotEmpty()) {
            item { SectionHeader("Running now") }
            items(model.active, key = { "a" + it.runId }) { run ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(run.taskName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            StatusChip(run.status)
                        }
                        val detail = listOfNotNull(run.stage, run.progress?.let { "${(it * 100).toInt()}%" }).joinToString(" · ")
                        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onNavigate(Routes.RUN, run.runId) }) { Text("Open") }
                            Button(onClick = { graph.orchestrator.cancel(run.runId) }) { Text("Stop") }
                        }
                    }
                }
            }
        }

        if (steps.isNotEmpty()) {
            item { SectionHeader("Finish setup") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        steps.take(3).forEach { Text("• " + it.title, style = MaterialTheme.typography.bodyMedium) }
                        Button(onClick = onOpenPhone) { Text("Open Phone tab") }
                    }
                }
            }
        }

        item {
            SectionHeader("Recent")
        }
        if (model.recent.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        if (model.isEmpty) "Nothing has run yet. Pick a task in the Tasks tab and press Run."
                        else "Nothing has finished yet.",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            items(model.recent, key = { "r" + it.runId }) { run ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(run.taskName, style = MaterialTheme.typography.bodyMedium)
                        val (unit, n) = TaskListModel.age(now, run.endedAt ?: run.startedAt)
                        Text(
                            when (unit) {
                                TaskListModel.AgeUnit.NOW -> "just now"
                                TaskListModel.AgeUnit.MINUTES -> "$n min ago"
                                TaskListModel.AgeUnit.HOURS -> "$n h ago"
                                TaskListModel.AgeUnit.DAYS -> "$n d ago"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusChip(run.status)
                }
            }
            item { TextButton(onClick = { onNavigate(Routes.RUNS_HISTORY, null) }) { Text("See all runs") } }
        }

        if (labs) {
            item { OutlinedButton(onClick = { onNavigate(Routes.ASK, null) }) { Text("Ask AI") } }
        }
    }
}
