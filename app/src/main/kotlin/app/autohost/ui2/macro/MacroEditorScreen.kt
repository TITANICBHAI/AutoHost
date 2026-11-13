package app.autohost.ui2.macro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.autohost.AppGraph
import app.autohost.core.data.db.TriggerRuleEntity
import app.autohost.core.designsystem.components.SectionHeader
import java.io.File
import kotlinx.coroutines.launch

/**
 * Quick macro editor (docs/UI_V2.md §6): name, one "when", a short list of steps, Save. Saving writes a dev-folder task
 * (task.json + flow.json) and one trigger rule; the rules and the file format live in [MacroCompiler] (unit-tested).
 * Concept credit: OpenTasker (MIT, SysAdminDoc) — no code taken.
 */
@Composable
fun MacroEditorScreen(graph: AppGraph, devRoot: File?, onDone: (taskId: String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var whenId by remember { mutableStateOf(WhenKinds.all.first().id) }
    val whenValues = remember { mutableStateMapOf<String, String>() }
    val steps = remember { mutableStateListOf<MacroStep>() }
    var problems by remember { mutableStateOf(emptyList<String>()) }
    var saving by remember { mutableStateOf(false) }
    var whenMenu by remember { mutableStateOf(false) }
    var stepMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val kind = WhenKinds.byId(whenId)!!

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Quick macro", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = name, onValueChange = { name = it }, label = { Text("Name") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader("When")
        Box {
            OutlinedButton(onClick = { whenMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(kind.label) }
            DropdownMenu(expanded = whenMenu, onDismissRequest = { whenMenu = false }) {
                WhenKinds.all.forEach { k ->
                    DropdownMenuItem(text = { Text(k.label) }, onClick = { whenId = k.id; whenValues.clear(); whenMenu = false })
                }
            }
        }
        kind.params.forEach { p ->
            OutlinedTextField(
                value = whenValues[p.key].orEmpty(), onValueChange = { whenValues[p.key] = it },
                label = { Text(p.label) }, placeholder = { if (p.hint.isNotEmpty()) Text(p.hint) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionHeader("Then")
        steps.forEachIndexed { i, step ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${i + 1}. ${step.label}", style = MaterialTheme.typography.titleSmall)
                    StepFields(step) { steps[i] = it }
                    Row {
                        if (i > 0) TextButton(onClick = { val s = steps.removeAt(i); steps.add(i - 1, s) }) { Text("Up") }
                        TextButton(onClick = { steps.removeAt(i) }) { Text("Remove") }
                    }
                }
            }
        }
        Box {
            OutlinedButton(onClick = { stepMenu = true }) { Text("+ Add step") }
            DropdownMenu(expanded = stepMenu, onDismissRequest = { stepMenu = false }) {
                listOf(
                    MacroStep.OpenApp(""), MacroStep.WaitFor("", "", 10), MacroStep.Tap("", ""),
                    MacroStep.Notify("", ""), MacroStep.Ask("", ""), MacroStep.Log(""),
                ).forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { steps.add(s); stepMenu = false }) }
            }
        }

        problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !saving, onClick = {
                val macro = Macro(name, whenId, whenValues.toMap(), steps.toList())
                val now = System.currentTimeMillis()
                when (val result = MacroCompiler.compile(macro, now)) {
                    is MacroResult.Invalid -> problems = result.problems
                    is MacroResult.Ok -> {
                        val c = result.compiled
                        if (devRoot == null) {
                            problems = listOf("This phone has no app storage available for tasks right now.")
                        } else {
                            saving = true
                            scope.launch {
                                runCatching {
                                    val dir = File(devRoot, c.folderName).apply { mkdirs() }
                                    File(dir, "task.json").writeText(c.taskJson)
                                    File(dir, "flow.json").writeText(c.flowJson)
                                    graph.data.triggers.upsert(
                                        TriggerRuleEntity(
                                            ruleId = app.autohost.core.common.Ids.ulid(now),
                                            taskId = c.taskId,
                                            source = c.rule.source,
                                            paramsJson = c.rule.params.toString(),
                                            cooldownSeconds = c.rule.cooldownSeconds,
                                            debounceMs = c.rule.debounceMs,
                                            busyPolicy = c.rule.busyPolicy,
                                            queueMax = 5,
                                            enabled = true,
                                            armed = false,
                                            createdAt = now,
                                            updatedAt = now,
                                        ),
                                    )
                                    graph.refreshDevTasks()
                                }.onSuccess { onDone(c.taskId) }
                                    .onFailure { problems = listOf("Could not save: ${it.message}"); saving = false }
                            }
                        }
                    }
                }
            }) { Text("Save") }
            TextButton(onClick = { onDone(null) }) { Text("Cancel") }
        }
    }
}

@Composable
private fun StepFields(step: MacroStep, onChange: (MacroStep) -> Unit) {
    @Composable
    fun field(label: String, value: String, set: (String) -> Unit) = OutlinedTextField(
        value = value, onValueChange = set, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    when (step) {
        is MacroStep.Notify -> {
            field("Title", step.title) { onChange(step.copy(title = it)) }
            field("Text", step.text) { onChange(step.copy(text = it)) }
        }
        is MacroStep.OpenApp -> field("App package", step.pkg) { onChange(step.copy(pkg = it)) }
        is MacroStep.Tap -> {
            field("App package", step.pkg) { onChange(step.copy(pkg = it)) }
            field("Button text", step.text) { onChange(step.copy(text = it)) }
        }
        is MacroStep.WaitFor -> {
            field("App package", step.pkg) { onChange(step.copy(pkg = it)) }
            field("Text to wait for", step.text) { onChange(step.copy(text = it)) }
            field("Seconds (1-120)", step.seconds.toString()) { onChange(step.copy(seconds = it.toIntOrNull() ?: 0)) }
        }
        is MacroStep.Ask -> {
            field("Title", step.title) { onChange(step.copy(title = it)) }
            field("Message", step.message) { onChange(step.copy(message = it)) }
        }
        is MacroStep.Log -> field("Message", step.message) { onChange(step.copy(message = it)) }
    }
}
