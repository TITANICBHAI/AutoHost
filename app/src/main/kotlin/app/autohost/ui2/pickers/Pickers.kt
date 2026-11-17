package app.autohost.ui2.pickers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import app.autohost.AppGraph
import app.autohost.ui.TaskCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The apps with a launcher icon, read once off the main thread. Empty until the read finishes. */
@Composable
fun rememberInstalledApps(graph: AppGraph): List<AppChoice> {
    var apps by remember { mutableStateOf(emptyList<AppChoice>()) }
    LaunchedEffect(graph) {
        apps = withContext(Dispatchers.IO) { InstalledApps.launchable(graph.appContext) }
    }
    return apps
}

/** The person's tasks (installed and dev-folder), live. The dev folder is re-read once when the editor opens. */
@Composable
fun rememberTaskChoices(graph: AppGraph): List<TaskChoice> {
    LaunchedEffect(graph) { withContext(Dispatchers.IO) { graph.refreshDevTasks() } }
    val entries by remember(graph) {
        TaskCatalog.observe(graph.data.tasks.observeActive(), graph.devTasks.tasks)
    }.collectAsState(initial = emptyList())
    return remember(entries) {
        entries.map { TaskChoice(it.taskId, it.name, PickerModel.taskKind(it.taskId, it.runtime)) }
    }
}

/**
 * A button that shows the picked app by name; tapping it opens a searchable list of the apps on the phone. [noneLabel]
 * adds a first row that clears the choice (for "any app"); [hide] is an app that must not be offered (this one).
 */
@Composable
fun AppPickerButton(
    label: String,
    pkg: String,
    apps: List<AppChoice>,
    hide: String?,
    noneLabel: String? = null,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val shown = PickerModel.appLabel(apps, pkg) ?: noneLabel?.takeIf { pkg.isBlank() } ?: "choose an app"
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: $shown") }
    if (open) {
        var query by remember { mutableStateOf("") }
        val rows = remember(apps, query) { PickerModel.apps(apps, query, hide) }
        PickerDialog(
            title = label,
            query = query,
            onQuery = { query = it },
            rows = rows,
            rowKey = { it.pkg },
            primary = { it.label },
            secondary = { null },
            emptyText = if (apps.isEmpty()) "The list of apps is still loading." else "No app matches.",
            noneLabel = noneLabel,
            onNone = { onPick(""); open = false },
            onPick = { onPick(it.pkg); open = false },
            onDismiss = { open = false },
        )
    }
}

/** The same for tasks: shows the picked task by name; the list shows each task's name and what kind it is. */
@Composable
fun TaskPickerButton(
    label: String,
    taskId: String,
    tasks: List<TaskChoice>,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val shown = PickerModel.taskName(tasks, taskId) ?: "choose a task"
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: $shown") }
    if (open) {
        var query by remember { mutableStateOf("") }
        val rows = remember(tasks, query) { PickerModel.tasks(tasks, query) }
        PickerDialog(
            title = label,
            query = query,
            onQuery = { query = it },
            rows = rows,
            rowKey = { it.taskId },
            primary = { it.name },
            secondary = { it.kind },
            emptyText = if (tasks.isEmpty()) "You have no other tasks yet. Add one, then come back." else "No task matches.",
            noneLabel = null,
            onNone = { },
            onPick = { onPick(it.taskId); open = false },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun <T> PickerDialog(
    title: String,
    query: String,
    onQuery: (String) -> Unit,
    rows: List<T>,
    rowKey: (T) -> String,
    primary: (T) -> String,
    secondary: (T) -> String?,
    emptyText: String,
    noneLabel: String?,
    onNone: () -> Unit,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = onQuery, label = { Text("Search") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    if (noneLabel != null) {
                        item(key = "none") { PickerRow(noneLabel, null, onNone) }
                    }
                    if (rows.isEmpty()) {
                        item(key = "empty") { Text(emptyText, style = MaterialTheme.typography.bodySmall) }
                    }
                    items(rows, key = { "row:" + rowKey(it) }) { row ->
                        PickerRow(primary(row), secondary(row)) { onPick(row) }
                    }
                }
            }
        },
        // "Close", not "Cancel": the editor underneath has its own Cancel, and two buttons with one name are easy to mix up.
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PickerRow(primary: String, secondary: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(primary, style = MaterialTheme.typography.bodyLarge)
        if (secondary != null) {
            Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
