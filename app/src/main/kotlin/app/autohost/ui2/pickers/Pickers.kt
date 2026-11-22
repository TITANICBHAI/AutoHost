package app.autohost.ui2.pickers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.autohost.AppGraph
import app.autohost.ui.TaskCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The most rows one list draws. A phone with more apps than this asks the person to type a few letters instead. */
private const val MAX_ROWS = 200

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
 * A button that shows the picked app by name; tapping it opens a searchable list of the apps on the phone right under
 * the button (not in a pop-up window: a pop-up with a text box inside hung the JVM test runner, and an inline list also
 * scrolls with the page on a small screen). [noneLabel] adds a first row that clears the choice (for "any app"); [hide]
 * is an app that must not be offered (this one).
 */
@Composable
fun AppPickerButton(
    label: String,
    pkg: String,
    apps: List<AppChoice>,
    hide: String?,
    noneLabel: String? = null,
    blankText: String = "choose an app",
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val shown = PickerModel.appLabel(apps, pkg) ?: noneLabel?.takeIf { pkg.isBlank() } ?: blankText
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // a blank [label] makes the button read as just [shown]: "+ Add an app", not ": + Add an app"
        OutlinedButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
            Text(if (label.isBlank()) shown else "$label: $shown")
        }
        if (open) {
            var query by remember { mutableStateOf("") }
            val rows = remember(apps, query) { PickerModel.apps(apps, query, hide) }
            PickerPanel(
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
                onClose = { open = false },
            )
        }
    }
}

/**
 * The same for tasks: shows the picked task by name; the list shows each task's name and what kind it is. A blank [label]
 * makes the button read as just [blankText] or the picked name ("+ Add a task"), like [AppPickerButton].
 */
@Composable
fun TaskPickerButton(
    label: String,
    taskId: String,
    tasks: List<TaskChoice>,
    blankText: String = "choose a task",
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val shown = PickerModel.taskName(tasks, taskId) ?: blankText
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
            Text(if (label.isBlank()) shown else "$label: $shown")
        }
        if (open) {
            var query by remember { mutableStateOf("") }
            val rows = remember(tasks, query) { PickerModel.tasks(tasks, query) }
            PickerPanel(
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
                onClose = { open = false },
            )
        }
    }
}

/** A search box over a list that scrolls on its own once it is taller than 240 dp. Plain Column rows: no lazy list, no window. */
@Composable
private fun <T> PickerPanel(
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
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, label = { Text("Search") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            if (noneLabel != null) {
                PickerRow(noneLabel, null, onNone)
            }
            if (rows.isEmpty()) {
                Text(emptyText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
            }
            rows.take(MAX_ROWS).forEach { row ->
                key(rowKey(row)) { PickerRow(primary(row), secondary(row)) { onPick(row) } }
            }
            if (rows.size > MAX_ROWS) {
                Text(
                    "Showing the first $MAX_ROWS. Type a few letters to narrow the list.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
        // "Close", not "Cancel": the editor around it has its own Cancel, and two buttons with one name are easy to mix up.
        TextButton(onClick = onClose) { Text("Close") }
    }
}

@Composable
private fun PickerRow(primary: String, secondary: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(primary, style = MaterialTheme.typography.bodyLarge)
        if (secondary != null) {
            Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
