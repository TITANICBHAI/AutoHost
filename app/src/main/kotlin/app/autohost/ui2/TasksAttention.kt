package app.autohost.ui2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.autohost.AppGraph
import app.autohost.MainActivity
import app.autohost.Routes
import app.autohost.core.data.db.RunEntity
import app.autohost.core.designsystem.components.StatusChip
import app.autohost.ui.TaskEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What sits above the task list on the Tasks tab (the old Today and When tabs, folded in per owner feedback 2026-11-14):
 * unfinished setup first, then runs that need the person or are running (with Stop), then the way into Schedules and triggers.
 */
@Composable
fun TasksAttention(
    activity: MainActivity,
    graph: AppGraph,
    entries: List<TaskEntry>,
    runs: List<RunEntity>,
    onNavigate: (Routes, String?) -> Unit,
    onOpenSetup: () -> Unit,
) {
    var live by remember { mutableStateOf(LiveAccess()) }
    var facts by remember { mutableStateOf(SetupFacts(onboarded = true)) } // quiet until the first read says otherwise
    LaunchedEffect(Unit) {
        graph.loadOnboarded()
        withContext(Dispatchers.IO) {
            live = LiveAccessReader.read(activity, graph)
            facts = SetupFactsReader.read(graph)
        }
    }
    val steps = remember(live, facts) { GetStarted.steps(live, facts) }
    val model = remember(runs, entries) { TodayModel.build(runs, entries.associate { it.taskId to it.name }) }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (GetStarted.needsAttention(steps, facts)) {
            val required = GetStarted.requiredLeft(steps)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (required > 0) "Finish setup to run tasks" else "Finish setup",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (required > 0) "AutoHost cannot tap or read other apps until you turn on its accessibility service."
                        else "A few optional steps are left (${GetStarted.counts(steps).let { it.second - it.first }}).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onOpenSetup) { Text("Open setup") }
                }
            }
        }
        (model.needsYou + model.active).take(2).forEach { run ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(run.taskName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        StatusChip(run.status)
                    }
                    val detail = listOfNotNull(run.stage, run.progress?.let { "${(it * 100).toInt()}%" }).joinToString(" · ")
                    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onNavigate(Routes.RUN, run.runId) }) {
                            Text(if (run.status == app.autohost.core.model.RunStatus.WAITING_USER) "Answer" else "Open")
                        }
                        if (run.status != app.autohost.core.model.RunStatus.WAITING_USER) {
                            Button(onClick = { graph.orchestrator.cancel(run.runId) }) { Text("Stop") }
                        }
                    }
                }
            }
        }
        TextButton(onClick = { onNavigate(Routes.SCHEDULES, null) }) { Text("Schedules and triggers ›") }
    }
}
