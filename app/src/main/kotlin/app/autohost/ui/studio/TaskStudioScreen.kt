package app.autohost.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import app.autohost.AppGraph
import app.autohost.core.engine.tasks.RepoIndex
import app.autohost.core.engine.tasks.StudioDraft
import java.io.File
import kotlinx.coroutines.launch

/**
 * S27 (P11-05, ADR-083): Task Studio — authoring surface for dev-folder tasks plus the repo tab
 * (P11-03's consumer). The editor edits name/description/version/entry text and validates with
 * the importer's own validator; saves write `dev/<id>/task.json` + the entry file. The repo tab
 * fetches an https `index.json` and shows the pure RepoIndex verdicts; install rides the same
 * review → install path as any import (with the sha check when the entry carries one).
 */
@Composable
fun TaskStudioScreen(graph: AppGraph, onBack: () -> Unit) {
    val controller = remember { StudioController(graph) }
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("list") }
    var currentDir by remember { mutableStateOf<File?>(null) }
    var draft by remember { mutableStateOf<StudioDraft?>(null) }
    var devTasks by remember { mutableStateOf(controller.listDevTasks()) }
    var message by remember { mutableStateOf<String?>(null) }

    // Repo tab state
    var indexUrl by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf<StudioController.RepoView?>(null) }
    var repoBusy by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Task Studio", style = MaterialTheme.typography.titleLarge)
            Row {
                TextButton(onClick = { mode = "list"; draft = null }) { Text("Tasks") }
                TextButton(onClick = { mode = "repo" }) { Text("Repo") }
                TextButton(onClick = onBack) { Text("Close") }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        if (mode == "list") {
            NewTaskRow(onCreate = { id, name, runtime ->
                scope.launch {
                    val created = StudioDraft.newTask(id, name, runtime)
                    controller.create(created)
                        .onSuccess { devTasks = controller.listDevTasks(); draft = created; currentDir = it; mode = "edit" }
                        .onFailure { message = it.message }
                }
            })
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(devTasks) { dir ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dir.name, style = MaterialTheme.typography.titleSmall)
                                Text(dir.absolutePath, style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = {
                                val opened = controller.openDraft(dir)
                                if (opened == null) message = "could not open ${dir.name}" else {
                                    draft = opened; currentDir = dir; mode = "edit"
                                }
                            }) { Text("Edit") }
                        }
                    }
                }
            }
        } else if (mode == "repo") {
            OutlinedTextField(
                value = indexUrl,
                onValueChange = { indexUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Repository index URL (https)") },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            repoBusy = true
                            controller.loadRepo(indexUrl)
                                .onSuccess { repo = it; message = null }
                                .onFailure { message = it.message }
                            repoBusy = false
                        }
                    },
                    enabled = !repoBusy && indexUrl.isNotBlank(),
                ) { Text(if (repoBusy) "Loading…" else "Load") }
            }
            repo?.let { view ->
                view.issues.forEach { Text("index: ${it.message}", style = MaterialTheme.typography.labelSmall) }
                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(view.rows) { row ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(row.entry.title ?: row.entry.id, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${row.entry.id} · ${row.entry.version}" +
                                        (row.entry.runtime?.let { " · $it" } ?: "") +
                                        if (row.message.isEmpty()) "" else " · ${row.message}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                when (row.verdict) {
                                    is RepoIndex.Verdict.Install, is RepoIndex.Verdict.Upgrade -> OutlinedButton(onClick = {
                                        scope.launch {
                                            controller.installFromRepo(row.entry.source, row.entry.sha256)
                                                .onSuccess { message = null; devTasks = controller.listDevTasks() }
                                                .onFailure { message = it.message }
                                        }
                                    }) { Text(if (row.verdict is RepoIndex.Verdict.Upgrade) "Upgrade" else "Install") }
                                    is RepoIndex.Verdict.UpToDate -> Text("Up to date", style = MaterialTheme.typography.labelSmall)
                                    is RepoIndex.Verdict.Reject -> Text(
                                        "Rejected: ${row.verdict.reason}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            } ?: Text("load an index to see the entries", style = MaterialTheme.typography.bodySmall)
        } else {
            // editor
            val d = draft
            if (d == null) {
                Text("nothing open", style = MaterialTheme.typography.bodySmall)
            } else {
                var name by remember(d.taskId) { mutableStateOf(d.name) }
                var description by remember(d.taskId) { mutableStateOf(d.description) }
                var version by remember(d.taskId) { mutableStateOf(d.version) }
                var entryText by remember(d.taskId) { mutableStateOf(d.entryText) }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = version, onValueChange = { version = it }, label = { Text("Version (semver)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    value = entryText,
                    onValueChange = { entryText = it },
                    label = { Text("${d.entry} (${d.runtimeType})") },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val working = d.with(name = name, description = description, version = version, entryText = entryText)
                        val dir = currentDir
                        if (dir == null) {
                            message = "no dev folder for this draft"
                        } else {
                            controller.save(dir, working)
                                .onSuccess { message = null; draft = working }
                                .onFailure { message = it.message }
                        }
                    }) { Text("Save (validates)") }
                    OutlinedButton(onClick = {
                        val working = d.with(name = name, description = description, version = version, entryText = entryText)
                        val result = working.validate()
                        message = if (result.ok) "manifest valid" else result.errors.joinToString("; ") { it.describe() }
                    }) { Text("Validate") }
                    TextButton(onClick = { mode = "list" }) { Text("Close") }
                }
                Text(
                    "Drafts live in the dev folder: push or edit there, or import as a bundle. Studio never edits installed bundles.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun NewTaskRow(onCreate: (String, String, String) -> Unit) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var runtime by remember { mutableStateOf(StudioDraft.RUNTIME_PYTHON) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("New dev task", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("id (com.example.my-task)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (candidate in listOf(
                    StudioDraft.RUNTIME_PYTHON,
                    StudioDraft.RUNTIME_FLOW,
                    StudioDraft.RUNTIME_JAVASCRIPT,
                )) {
                    OutlinedButton(onClick = { runtime = candidate }, enabled = runtime != candidate) { Text(candidate) }
                }
                Button(onClick = { onCreate(id.trim(), name.trim(), runtime) }, enabled = id.isNotBlank() && name.isNotBlank()) { Text("Create") }
            }
        }
    }
}
