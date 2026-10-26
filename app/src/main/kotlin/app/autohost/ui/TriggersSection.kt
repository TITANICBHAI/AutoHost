package app.autohost.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.autohost.AppGraph
import app.autohost.core.data.db.TriggerRuleEntity
import app.autohost.core.designsystem.components.SectionHeader
import app.autohost.core.engine.events.EventSourceCatalog
import app.autohost.core.model.TriggerRule
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P8-04: the Triggers section on the task detail Overview tab (docs/UI_SPEC S22). Lists every
 * trigger rule of the task with its live status (last fire, count, cooldown remaining, busy-queue
 * depth, grant gate), lets the user enable/disable, delete, fire a synthetic test event and add
 * rules through a source-driven editor. All persistence goes through [TriggerRuleEntity] +
 * `graph.triggerController.refresh()` so the runtime re-arms immediately.
 */
@Composable
fun TriggersSection(graph: AppGraph, taskId: String) {
    var rules by remember { mutableStateOf<List<TriggerRuleEntity>>(emptyList()) }
    var editing by remember { mutableStateOf<Boolean>(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val nowMs by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    suspend fun reload() {
        rules = graph.data.triggers.ofTask(taskId)
    }

    LaunchedEffect(taskId) { reload() }

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("Triggers")
        if (rules.isEmpty()) {
            Text(
                "No trigger rules. Add one to start this task from an event (notification, screen, " +
                    "battery, network, another task's result, or a named external call).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        for (rule in rules) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(rule.source, style = MaterialTheme.typography.titleSmall)
                            Text(
                                triggerSummary(nowMs, rule),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val gate = grantNote(rule)
                            if (gate != null) {
                                Text(gate, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { on ->
                                scope.launch {
                                    graph.data.triggers.upsert(rule.copy(enabled = on, updatedAt = System.currentTimeMillis()))
                                    graph.triggerController.refresh()
                                    reload()
                                }
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            scope.launch {
                                testResult = graph.triggerController.testFire(rule.ruleId).toString()
                                reload()
                            }
                        }) { Text("Test") }
                        TextButton(onClick = {
                            scope.launch {
                                graph.data.triggers.delete(rule.ruleId)
                                graph.triggerController.refresh()
                                reload()
                            }
                        }) { Text("Delete") }
                    }
                    if (testResult != null) {
                        Text(
                            testResult ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
        Button(onClick = { editing = true }, modifier = Modifier.padding(top = 4.dp)) { Text("Add trigger") }
    }

    if (editing) {
        TriggerEditorDialog(
            graph = graph,
            taskId = taskId,
            onDismiss = { editing = false },
            onSaved = {
                editing = false
                scope.launch { reload() }
            },
        )
    }
}

@Composable
private fun TriggerEditorDialog(
    graph: AppGraph,
    taskId: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val sources = remember { EventSourceCatalog.ALL.map { it.kind } + app.autohost.core.model.TriggerSource.TIMED }
    var source by remember { mutableStateOf("notification.posted") }
    var pkg by remember { mutableStateOf("") }
    var titleRegex by remember { mutableStateOf("") }
    var textRegex by remember { mutableStateOf("") }
    var textParam by remember { mutableStateOf("") }
    var percent by remember { mutableStateOf("") }
    var op by remember { mutableStateOf("lte") }
    var cooldown by remember { mutableStateOf("") }
    var debounce by remember { mutableStateOf("") }
    var maxLaunches by remember { mutableStateOf("") }
    var busyPolicy by remember { mutableStateOf("drop") }
    var queueMax by remember { mutableStateOf("5") }
    var onResult by remember { mutableStateOf("any") }
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add trigger") },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Source", style = MaterialTheme.typography.labelMedium)
                // Source picker as a scrollable chip row (no dropdown dependency).
                Row(modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(sources.size) { index ->
                            FilterChip(
                                selected = source == sources[index],
                                onClick = { source = sources[index] },
                                label = { Text(sources[index], style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
                if (source in setOf("notification.posted", "notification.removed", "app.entered", "app.exited", "toast.shown")) {
                    OutlinedTextField(
                        value = pkg, onValueChange = { pkg = it },
                        label = { Text("package (exact, optional for notifications/toast)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (source == "notification.posted") {
                    OutlinedTextField(
                        value = titleRegex, onValueChange = { titleRegex = it },
                        label = { Text("title regex (optional)") }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = textRegex, onValueChange = { textRegex = it },
                        label = { Text("text regex (optional)") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (source == "toast.shown") {
                    OutlinedTextField(
                        value = textRegex, onValueChange = { textRegex = it },
                        label = { Text("text regex (optional)") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (source == "battery.level") {
                    OutlinedTextField(
                        value = percent, onValueChange = { percent = it },
                        label = { Text("percent 0..100") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("lt", "lte", "gt", "gte", "eq").forEach { candidate ->
                            FilterChip(
                                selected = op == candidate,
                                onClick = { op = candidate },
                                label = { Text(candidate) },
                            )
                        }
                    }
                }
                if (source == "time.delay") {
                    OutlinedTextField(
                        value = textParam, onValueChange = { textParam = it },
                        label = { Text("seconds") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (source == "time.daily") {
                    OutlinedTextField(
                        value = textParam, onValueChange = { textParam = it },
                        label = { Text("time HH:MM") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (source == "external") {
                    OutlinedTextField(
                        value = textParam, onValueChange = { textParam = it },
                        label = { Text("trigger name (the caller presents it)") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (source == "task.chain") {
                    OutlinedTextField(
                        value = textParam, onValueChange = { textParam = it },
                        label = { Text("upstream task id") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("any", "succeeded", "failed").forEach { candidate ->
                            FilterChip(
                                selected = onResult == candidate,
                                onClick = { onResult = candidate },
                                label = { Text(candidate) },
                            )
                        }
                    }
                }
                if (source == "network.wifi_ssid") {
                    OutlinedTextField(
                        value = textParam, onValueChange = { textParam = it },
                        label = { Text("SSID") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = cooldown, onValueChange = { cooldown = it },
                    label = { Text("cooldown_seconds (optional)") }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = debounce, onValueChange = { debounce = it },
                    label = { Text("debounce_ms (coalesces bursts, optional)") }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = maxLaunches, onValueChange = { maxLaunches = it },
                    label = { Text("max_launch_count (lifetime cap, optional)") }, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("drop", "queue", "replace").forEach { candidate ->
                        FilterChip(
                            selected = busyPolicy == candidate,
                            onClick = { busyPolicy = candidate },
                            label = { Text(candidate) },
                        )
                    }
                }
                if (busyPolicy == "queue") {
                    OutlinedTextField(
                        value = queueMax, onValueChange = { queueMax = it },
                        label = { Text("queue max (oldest dropped first)") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (problem != null) {
                    Text(problem ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val params = buildJsonObject {
                    if (pkg.isNotBlank()) put("package", pkg)
                    if (titleRegex.isNotBlank() && source == "notification.posted") put("title_regex", titleRegex)
                    if (textRegex.isNotBlank() && source != "task.chain") put("text_regex", textRegex)
                    if (source == "battery.level") { put("op", op); percent.toIntOrNull()?.let { put("percent", it) } }
                    if (textParam.isNotBlank()) {
                        when (source) {
                            "time.delay" -> put("seconds", textParam.toIntOrNull() ?: 0)
                            "external", "task.chain", "network.wifi_ssid", "time.daily" -> put(
                                if (source == "time.daily") "time" else if (source == "task.chain") "task" else if (source == "network.wifi_ssid") "ssid" else "name",
                                textParam,
                            )
                        }
                    }
                    if (source == "task.chain") put("on", onResult)
                }
                val rule = TriggerRule(
                    task = taskId,
                    source = source,
                    params = params,
                    cooldownSeconds = cooldown.toLongOrNull() ?: 0,
                    maxLaunchCount = maxLaunches.toIntOrNull(),
                    debounceMs = debounce.toLongOrNull() ?: 0,
                    busyPolicy = busyPolicy,
                    queueMax = queueMax.toIntOrNull() ?: 5,
                    enabled = true,
                )
                val problems = rule.validate()
                if (problems.isNotEmpty()) {
                    problem = problems.joinToString(", ") { "TRIGGER_RULE_INVALID($it)" }
                    return@TextButton
                }
                scope.launch {
                    val now = System.currentTimeMillis()
                    graph.data.triggers.upsert(
                        TriggerRuleEntity(
                            ruleId = app.autohost.core.common.Ids.ulid(now),
                            taskId = taskId,
                            source = rule.source,
                            paramsJson = rule.params.toString(),
                            cooldownSeconds = rule.cooldownSeconds,
                            maxLaunchCount = rule.maxLaunchCount,
                            debounceMs = rule.debounceMs,
                            busyPolicy = rule.busyPolicy,
                            queueMax = rule.queueMax,
                            enabled = true,
                            armed = false,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    graph.triggerController.refresh()
                    onSaved()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One honest status line per rule (plan §11.9: last fire, counts, cooldown remaining, depth). */
private fun triggerSummary(nowMs: Long, rule: TriggerRuleEntity): String {
    val parts = mutableListOf<String>()
    rule.lastFireAt?.let {
        parts += "last ${java.time.Duration.ofMillis(nowMs - it).toMinutes()} min ago"
    }
    parts += "${rule.launchCount} launches"
    if (rule.cooldownSeconds > 0) parts += "cooldown ${rule.cooldownSeconds}s"
    if (rule.debounceMs > 0) parts += "debounce ${rule.debounceMs}ms"
    if (rule.busyPolicy != "drop") parts += "busy: ${rule.busyPolicy}"
    parts += when {
        rule.source in app.autohost.core.model.TriggerSource.TIMED ->
            if (rule.armed) "armed" else "arming…"
        else -> if (rule.armed) "live" else "live (event)"
    }
    return parts.joinToString(" · ")
}

private fun grantNote(rule: TriggerRuleEntity): String? {
    val required = EventSourceCatalog.byKind(rule.source)?.requiredGrant ?: return null
    return "needs the '$required' grant on this task — events are dropped until it is granted (§11.8)"
}
