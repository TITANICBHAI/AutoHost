package app.autohost.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.autohost.core.designsystem.components.SectionHeader
import app.autohost.core.designsystem.components.StatusChip
import app.autohost.recorder.RecorderController
import app.autohost.runtime.flow.RecordedStep
import app.autohost.runtime.flow.Recorder
import app.autohost.runtime.flow.RecorderResult
import kotlinx.serialization.json.JsonObject

/**
 * P6-09: review-steps screen (plan §7.9, UI_SPEC S18). Shows the recorded steps with the
 * selector rank each one used, lets the user delete or reorder, and enforces the coordinate
 * gate: **Save stays blocked until every bounds-flagged step is explicitly confirmed** (G6-e).
 * Save writes `flow.json` + the sidecar into the dev folder, where the normal import pipeline
 * picks the task up (library refresh shows it with the `flow` runtime badge).
 */
@Composable
fun RecorderReviewScreen(controller: RecorderController, onBack: () -> Unit) {
    val state by controller.state.collectAsState()

    // Local editing state (delete/reorder/confirm) over the recorded steps.
    var steps by remember(state.result) {
        mutableStateOf(state.result?.steps?.map { ReviewRow(it) } ?: emptyList())
    }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    val unconfirmed = steps.count { it.needsConfirmation && !it.confirmed }
    val recorded = state.result

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Review steps", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("‹ Back") }
        }

        if (recorded == null) {
            if (state.recording) {
                // Live recording view: the user performs the task in the target app; the ● REC
                // overlay floats above it. Stop here or on the overlay — same controller call.
                Text("● Recording… ${state.stepCount} step(s) so far.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Switch to the target app and perform the task once. Every tap and text entry " +
                        "becomes a step. Press Stop when done — Android keeps this screen in the " +
                        "recents switch for coming back.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { controller.stop() }) { Text("■ Stop recording") }
            } else {
                Text("Nothing recorded yet — start the recorder from the Library screen (● Record).")
            }
            return
        }

        recorded.warnings.forEach { warning ->
            Text("⚠ $warning", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        SectionHeader("STEPS (${steps.size})")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(steps, key = { _, row -> row.label }) { index, row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}. ${row.command}", style = MaterialTheme.typography.titleSmall)
                            Row {
                                TextButton(onClick = {
                                    if (index > 0) steps = steps.toMutableList().apply { add(index - 1, removeAt(index)) }
                                }) { Text("↑") }
                                TextButton(onClick = {
                                    if (index < steps.size - 1) steps = steps.toMutableList().apply { add(index + 1, removeAt(index)) }
                                }) { Text("↓") }
                                TextButton(onClick = { steps = steps.filterNot { it.label == row.label } }) { Text("✕") }
                            }
                        }
                        Text(row.bodyPreview, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusChip(
                                when (row.rank) {
                                    "id" -> "STABLE ID"
                                    "desc" -> "DESC"
                                    "text" -> "TEXT"
                                    "relational" -> "RELATIONAL"
                                    "bounds" -> "COORDINATES"
                                    else -> "NO SELECTOR"
                                },
                                warning = row.rank == "bounds",
                                success = row.rank != "bounds" && row.rank != "none",
                                neutral = row.rank == "none",
                            )
                            if (row.needsConfirmation) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = row.confirmed, onCheckedChange = { confirmed ->
                                        steps = steps.map { if (it.label == row.label) it.copy(confirmed = confirmed) else it }
                                    })
                                    Text("Keep coordinate step", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (recorded.steps.any { it.secretNames.isNotEmpty() }) {
            SectionHeader("SECRETS")
            Text(
                "Password fields were recorded as \${secret.INPUT_n} placeholders. Bind them in the " +
                    "task's profile after saving — the flow itself never stores the value.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (unconfirmed > 0) {
            Text(
                "Save blocked: $unconfirmed coordinate step(s) need explicit confirmation — they replay " +
                    "screen positions, which break on layout changes.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = unconfirmed == 0 && steps.isNotEmpty(),
                onClick = {
                    val rebuilt = RecorderResult(
                        steps = steps.map { it.source },
                        warnings = recorded.warnings,
                        startedAtMs = recorded.startedAtMs,
                    )
                    val name = folderName(rebuilt.startedAtMs)
                    val flowJson = Recorder.toFlowDocument(rebuilt, name)
                    val saved = controller.save(name, flowJson, rebuilt)
                    saveMessage = if (saved != null) {
                        "Saved ${saved.first.parentFile?.name}/flow.json (+sidecar). Refresh the library."
                    } else {
                        "Save failed: external dev storage unavailable."
                    }
                },
            ) { Text("Save as flow") }
            OutlinedButton(onClick = {
                controller.reset()
                onBack()
            }) { Text("Discard") }
        }
        saveMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** One editable row in the review list. */
private data class ReviewRow(val source: RecordedStep, val confirmed: Boolean = false) {
    val label: String get() = source.label
    val command: String get() = source.command
    val needsConfirmation: Boolean get() = source.needsConfirmation
    val rank: String get() = source.selectorRank
    val bodyPreview: String get() = app.autohost.core.common.AhJson.stringify(source.body).take(180)
}

private fun folderName(startedAtMs: Long): String = "recorded-$startedAtMs"
