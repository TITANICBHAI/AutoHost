package app.autohost.ui2

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.autohost.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Add a task": the one place that lists every way to get a task onto the phone without a computer — a template, a quick
 * macro, or a task file from the phone's storage. Shown from the Tasks tab, Setup and Settings.
 */
@Composable
fun AddTaskDialog(
    graph: AppGraph,
    onTemplates: () -> Unit,
    onMacro: () -> Unit,
    onInstalled: (taskId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = graph.appContext
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var problems by remember { mutableStateOf(emptyList<String>()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        problems = emptyList()
        scope.launch {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "task.zip"
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            val outcome = if (bytes == null) ImportOutcome.Rejected(listOf("The file could not be read."))
            else TaskImporter.installZip(graph, name, bytes)
            busy = false
            when (outcome) {
                is ImportOutcome.Installed -> onInstalled(outcome.taskId)
                is ImportOutcome.Rejected -> problems = outcome.reasons
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Add a task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("None of these need a computer.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onTemplates, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("From a template") }
                OutlinedButton(onClick = onMacro, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Quick macro (when → steps)") }
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Checking the file…" else "Import a task file (.zip)") }
                Text(
                    "A task file can hold a Python, JavaScript or flow task. AutoHost shows what it needs before it can run.",
                    style = MaterialTheme.typography.bodySmall,
                )
                problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } },
    )
}
