package app.autohost.ui2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.autohost.AppGraph
import app.autohost.MainActivity
import app.autohost.core.model.TaskManifest
import app.autohost.ui.TaskEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Android side of [TaskReadiness]: reads the phone's state once and each task's required capabilities
 * (dev-folder tasks carry their manifest; installed tasks keep it in the latest stored version), and returns one
 * result per task id. A task whose manifest cannot be read is simply left out: no chip is better than a wrong one.
 */
@Composable
fun rememberTaskReadiness(activity: MainActivity, graph: AppGraph, entries: List<TaskEntry>): Map<String, TaskReadiness.Result> {
    var result by remember { mutableStateOf<Map<String, TaskReadiness.Result>>(emptyMap()) }
    LaunchedEffect(entries) {
        result = withContext(Dispatchers.IO) {
            val live = LiveAccessReader.read(activity, graph)
            val out = LinkedHashMap<String, TaskReadiness.Result>()
            for (entry in entries) {
                val manifest = manifestOf(graph, entry) ?: continue
                out[entry.taskId] = TaskReadiness.of(manifest.needs.capabilities.map { it.id }, live)
            }
            out
        }
    }
    return result
}

private suspend fun manifestOf(graph: AppGraph, entry: TaskEntry): TaskManifest? {
    entry.dev?.let { return it.manifest }
    val json = graph.data.tasks.latestVersion(entry.taskId)?.manifestJson ?: return null
    return try {
        kotlinx.serialization.json.Json.decodeFromString(TaskManifest.serializer(), json)
    } catch (e: kotlinx.serialization.SerializationException) {
        null
    }
}
