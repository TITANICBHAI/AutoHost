package app.autohost.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.autohost.AutoHostApp
import app.autohost.MainActivity
import app.autohost.R
import app.autohost.core.designsystem.components.LogLine
import app.autohost.core.designsystem.components.StatusChip
import app.autohost.core.model.BrokerEvent
import app.autohost.core.model.RunStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Run screen (P5-10, docs/UI_SPEC.md S08): live log + stage timeline + incidents + audit viewer +
 * pause/resume/resume-waiting-user/stop/export. Incidents (§12.5) list first while open; the audit
 * trail shows the last 20 broker calls with outcome and duration.
 */
@Composable
fun RunScreen(activity: MainActivity, runId: String?, onBack: () -> Unit) {
    val graph = (activity.application as AutoHostApp).graph
    val runs by graph.runStore.runs.collectAsState()
    val run = runs.firstOrNull { it.runId == runId }
    var events by remember(runId) { mutableStateOf<List<BrokerEvent>>(emptyList()) }
    LaunchedEffect(runId) {
        val activeRunId = runId ?: return@LaunchedEffect
        events = graph.runStore.snapshot(activeRunId, limit = 500)
        graph.runStore.events(activeRunId).collect { event ->
            events = (events + event).takeLast(500)
        }
    }
    val incidents = runId?.let { graph.incidents.openOf(it) }.orEmpty()
    val audit = runId?.let { graph.auditMem.forRun(it).takeLast(20) }.orEmpty()
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(run?.taskName ?: runId ?: "", style = MaterialTheme.typography.titleLarge)
                Text(
                    "attempt ${run?.attempt ?: 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (run != null) StatusChip(run.status)
        }
        run?.stage?.let { stage ->
            Text("stage: $stage", style = MaterialTheme.typography.labelMedium)
        }
        run?.reason?.let { reason ->
            Text(
                reason,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // ---- actions: pause/resume (cooperative), resume-after-WAITING_USER/INTERRUPTED, stop, export
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (run?.status == RunStatus.RUNNING) {
                OutlinedButton(onClick = { graph.orchestrator.requestPause(runId ?: return@OutlinedButton) }) {
                    Text("Pause")
                }
            }
            if (run?.status == RunStatus.PAUSED) {
                Button(onClick = { graph.orchestrator.requestResume(runId ?: return@Button) }) {
                    Text("Resume")
                }
            }
            if (run?.status == RunStatus.WAITING_USER || run?.status == RunStatus.INTERRUPTED) {
                Button(onClick = {
                    if (runId == null) return@Button
                    val checkpoint = graph.checkpoints.load(runId)
                    graph.appScope.launch { graph.orchestrator.resume(runId, checkpoint) }
                }) {
                    Text("Resume run")
                }
            }
            Button(
                onClick = { if (runId != null) graph.orchestrator.cancel(runId) },
                enabled = run != null && run.status.active,
            ) {
                Text(stringResource(R.string.action_stop))
            }
            OutlinedButton(onClick = {
                val exportRunId = runId ?: return@OutlinedButton
                graph.appScope.launch(Dispatchers.IO) {
                    val bytes = graph.runExporter.export(exportRunId) ?: return@launch
                    File(graph.runsRoot(), "$exportRunId.aarun").writeBytes(bytes)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            graph.appContext,
                            "saved $exportRunId.aarun",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }) {
                Text("Export")
            }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }

        // ---- incidents (§12.5): unresolved first
        if (incidents.isNotEmpty()) {
            Text("INCIDENTS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            for (incident in incidents) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            "${incident.capability}.${incident.method} → ${incident.errorCode ?: "FAILED"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "stage: ${incident.stage ?: "-"} · opened ${timeFormat.format(Date(incident.openedAtMs))}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        // ---- stage timeline (§13.1): begin/end pairs from the event stream
        val stageEvents = events.filter { it.type == "stage_begin" || it.type == "stage_end" }
        if (stageEvents.isNotEmpty()) {
            Text("TIMELINE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            for (event in stageEvents.takeLast(12)) {
                val ok = event.data?.get("ok")?.toString()
                Text(
                    "${timeFormat.format(Date(event.ts))}  ${if (event.type == "stage_begin") "▶" else "■"} ${event.stage ?: ""}" +
                        if (event.type == "stage_end" && ok != null) " ($ok)" else "",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // ---- audit trail (§13.1): last 20 broker calls
        if (audit.isNotEmpty()) {
            Text("AUDIT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            for (entry in audit.takeLast(8)) {
                Text(
                    "${timeFormat.format(Date(entry.tsMs))}  ${entry.capability}.${entry.method} " +
                        "${if (entry.ok) "ok" else "FAIL ${entry.errorCode ?: ""}"} ${entry.durationMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (entry.ok) Color.Unspecified else MaterialTheme.colorScheme.error,
                )
            }
        }

        Text("LOG", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(events, key = { "${it.ts}-${it.type}-${it.message ?: ""}" }) { event ->
                LogLine(
                    time = timeFormat.format(Date(event.ts)),
                    level = (event.level ?: "INFO").uppercase(),
                    message = event.message ?: event.type,
                )
            }
        }
    }
}
