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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.autohost.AutoHostApp
import app.autohost.MainActivity
import app.autohost.Routes
import app.autohost.core.designsystem.components.EmptyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P5-18: run history (docs/UI_SPEC.md S09 reduced). Persisted runs from Room with status filter
 * chips; tapping a row opens the live Run screen (or the record view). Unresolved incidents and
 * INTERRUPTED rows surface first per plan §12.5.
 */
@Composable
fun RunsHistoryScreen(activity: MainActivity, onOpenRun: (String) -> Unit, onBack: () -> Unit) {
    val graph = (activity.application as AutoHostApp).graph
    val runs by graph.data.runs.observeRecent(100).collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf("ALL") }
    val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    val filters = listOf("ALL", "ACTIVE", "SUCCEEDED", "FAILED", "INTERRUPTED", "CANCELLED")
    val visible = runs.filter { run ->
        when (filter) {
            "ALL" -> true
            "ACTIVE" -> run.status in listOf("QUEUED", "STARTING", "RUNNING", "PAUSED", "WAITING_USER")
            else -> run.status == filter
        }
    }.sortedWith(
        compareByDescending<app.autohost.core.data.db.RunEntity> { it.status == "INTERRUPTED" }
            .thenByDescending { it.startedAt },
    )

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Runs", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in filters) {
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(option) },
                )
            }
        }
        if (visible.isEmpty()) {
            EmptyState(title = "No runs yet", body = "Run a task from the library; history appears here.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.rowId }) { run ->
                    Card(
                        Modifier.fillMaxWidth(),
                        onClick = { onOpenRun(run.runId) },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(run.taskId, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    run.status,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (run.status) {
                                        "SUCCEEDED" -> MaterialTheme.colorScheme.primary
                                        "FAILED", "INTERRUPTED" -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            Text(
                                "attempt ${run.attempt} · ${timeFormat.format(Date(run.startedAt))}" +
                                    (run.reasonCode?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
