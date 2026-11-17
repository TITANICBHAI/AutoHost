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
import app.autohost.ui2.pickers.AppChoice
import app.autohost.ui2.pickers.AppPickerButton
import app.autohost.ui2.pickers.TaskChoice
import app.autohost.ui2.pickers.TaskPickerButton
import app.autohost.ui2.pickers.rememberInstalledApps
import app.autohost.ui2.pickers.rememberTaskChoices
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
    val inputs = remember { mutableStateListOf<NamedValue>() }
    var problems by remember { mutableStateOf(emptyList<String>()) }
    var saving by remember { mutableStateOf(false) }
    var whenMenu by remember { mutableStateOf(false) }
    var stepMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // People pick an app or a task by name from a list; the package name or id is only what gets stored.
    val apps = rememberInstalledApps(graph)
    val myTasks = rememberTaskChoices(graph)
    val ownPackage = graph.application.packageName
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
            if (p.kind == ParamKind.PACKAGE) {
                AppPickerButton(
                    label = p.label, pkg = whenValues[p.key].orEmpty(), apps = apps, hide = ownPackage,
                    noneLabel = if (p.required) null else "Any app",
                ) { whenValues[p.key] = it }
            } else {
                OutlinedTextField(
                    value = whenValues[p.key].orEmpty(), onValueChange = { whenValues[p.key] = it },
                    label = { Text(p.label) }, placeholder = { if (p.hint.isNotEmpty()) Text(p.hint) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionHeader("Values it can be given")
        Text(
            "Optional. A script or another task can hand your macro values. Each one has a default; use it in any step as {name}.",
            style = MaterialTheme.typography.bodySmall,
        )
        PairsEditor(inputs, "+ Add a value", valueLabel = "Default value") { inputs.clear(); inputs.addAll(it) }

        SectionHeader("Then")
        steps.forEachIndexed { i, step ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${i + 1}. ${step.label}", style = MaterialTheme.typography.titleSmall)
                    StepFields(step, apps, myTasks, ownPackage) { steps[i] = it }
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
                MacroSteps.menu().forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.label) },
                        onClick = {
                            // A new step starts with the app the last step used, so the person types it once.
                            val pkg = MacroSteps.lastPackage(steps)
                            steps.add(if (pkg.isNotBlank()) MacroSteps.withPackage(s, pkg) else s)
                            stepMenu = false
                        },
                    )
                }
            }
        }

        problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !saving, onClick = {
                val macro = Macro(name, whenId, whenValues.toMap(), steps.toList(), inputs.toList())
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
                                    .onFailure { problems = listOf("The macro could not be saved. Check that the phone has free space, then try again."); saving = false }
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
private fun StepFields(
    step: MacroStep,
    apps: List<AppChoice>,
    tasks: List<TaskChoice>,
    ownPackage: String,
    onChange: (MacroStep) -> Unit,
) {
    @Composable
    fun field(label: String, value: String, set: (String) -> Unit) = OutlinedTextField(
        value = value, onValueChange = set, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    @Composable
    fun app(pkg: String, set: (String) -> Unit) = AppPickerButton("App", pkg, apps, ownPackage, onPick = set)
    when (step) {
        is MacroStep.Notify -> {
            field("Title", step.title) { onChange(step.copy(title = it)) }
            field("Text", step.text) { onChange(step.copy(text = it)) }
        }
        is MacroStep.OpenApp -> app(step.pkg) { onChange(step.copy(pkg = it)) }
        is MacroStep.Tap -> {
            app(step.pkg) { onChange(step.copy(pkg = it)) }
            field("Button text", step.text) { onChange(step.copy(text = it)) }
        }
        is MacroStep.WaitFor -> {
            app(step.pkg) { onChange(step.copy(pkg = it)) }
            field("Text to wait for", step.text) { onChange(step.copy(text = it)) }
            field("Seconds (1-120)", step.seconds.toString()) { onChange(step.copy(seconds = it.toIntOrNull() ?: 0)) }
        }
        is MacroStep.Ask -> {
            field("Title", step.title) { onChange(step.copy(title = it)) }
            field("Message", step.message) { onChange(step.copy(message = it)) }
        }
        is MacroStep.Log -> field("Message", step.message) { onChange(step.copy(message = it)) }
        is MacroStep.TypeText -> {
            app(step.pkg) { onChange(step.copy(pkg = it)) }
            field("Field label (blank: the field you just tapped)", step.field) { onChange(step.copy(field = it)) }
            field("Text to type", step.text) { onChange(step.copy(text = it)) }
        }
        is MacroStep.Scroll -> {
            app(step.pkg) { onChange(step.copy(pkg = it)) }
            Choice("Direction", step.direction, MacroSteps.DIRECTIONS.map { it to titleCase(it) }) { onChange(step.copy(direction = it)) }
        }
        is MacroStep.PressKey -> {
            app(step.pkg) { onChange(step.copy(pkg = it)) }
            Choice("Key", step.key, MacroSteps.KEYS.map { it to titleCase(it) }) { onChange(step.copy(key = it)) }
        }
        is MacroStep.RunTask -> {
            TaskPickerButton("Task to run", step.task, tasks) { onChange(step.copy(task = it)) }
            Text("Values to hand over", style = MaterialTheme.typography.labelLarge)
            PairsEditor(step.inputs, "+ Add a value", valueLabel = "Value") { onChange(step.copy(inputs = it)) }
            field("Wait up to (seconds, 1-600)", step.waitSeconds.toString()) { onChange(step.copy(waitSeconds = it.toIntOrNull() ?: 0)) }
            field("Keep its answer as (optional name)", step.into) { onChange(step.copy(into = it)) }
            if (step.into.isNotBlank()) {
                val n = step.into.trim()
                Text(
                    "Later steps can use {" + n + ".code} or {" + n + ".data.name}.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        is MacroStep.Finish -> {
            field("Result word, for example DONE", step.code) { onChange(step.copy(code = it)) }
            Text("Values to return", style = MaterialTheme.typography.labelLarge)
            PairsEditor(step.values, "+ Add a value", valueLabel = "Value") { onChange(step.copy(values = it)) }
        }
    }
}

private fun titleCase(word: String): String = word.replaceFirstChar { it.uppercase() }

/** A short list of name + value pairs: one name field, one value field and Remove per pair, stacked so it fits a narrow screen. */
@Composable
private fun PairsEditor(items: List<NamedValue>, addLabel: String, valueLabel: String, onChange: (List<NamedValue>) -> Unit) {
    items.forEachIndexed { i, item ->
        OutlinedTextField(
            value = item.name,
            onValueChange = { v -> onChange(items.toMutableList().also { list -> list[i] = item.copy(name = v) }) },
            label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = item.value,
            onValueChange = { v -> onChange(items.toMutableList().also { list -> list[i] = item.copy(value = v) }) },
            label = { Text(valueLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { onChange(items.filterIndexed { j, _ -> j != i }) }) { Text("Remove") }
    }
    OutlinedButton(onClick = { onChange(items + NamedValue("")) }) { Text(addLabel) }
}

/** A drop-down of fixed choices; [options] are (value, label) pairs and [onPick] gets the value. */
@Composable
private fun Choice(label: String, current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label + ": " + (options.firstOrNull { it.first == current }?.second ?: "choose"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option.second) }, onClick = { onPick(option.first); open = false })
            }
        }
    }
}
