package app.autohost.ui.taskui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.autohost.core.designsystem.components.SectionHeader
import app.autohost.core.model.taskui.DeclarativeUi
import app.autohost.core.model.taskui.UiAction
import app.autohost.core.model.taskui.UiBindingScope
import app.autohost.core.model.taskui.UiBindings
import app.autohost.core.model.taskui.UiWidget
import kotlinx.serialization.json.JsonPrimitive

/**
 * P7-01 (plan §10.1, UI_SPEC S16): the Tier B declarative renderer. Renders exactly the frozen v1
 * widget set parsed by `:core:model` (`taskui/DeclarativeUi.kt`) — no HTML, no script; every string
 * goes through the binding evaluator before display. Buttons dispatch through [onAction], which
 * also receives the current input-widget values ({{input.NAME}} scope); `confirm` actions get a
 * two-step dialog with the declared consequences (UI_SPEC principle 4). The "provided by the task"
 * footer is the trust marker UI_SPEC S16 asks for.
 */
@Composable
fun DeclarativeRenderer(
    doc: DeclarativeUi,
    scope: UiBindingScope,
    onAction: (UiAction, kotlinx.serialization.json.JsonObject) -> Unit,
    consoleLines: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var inputs by remember(doc) { mutableStateOf(mapOf<String, String>()) }
    var pendingConfirm by remember(doc) { mutableStateOf<UiAction.Confirm?>(null) }
    fun dispatch(action: UiAction) {
        if (action is UiAction.Confirm) pendingConfirm = action
        else onAction(action, inputsJsonObject(inputs))
    }
    pendingConfirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            title = { Text(confirm.title) },
            text = { Text(confirm.message) },
            confirmButton = {
                TextButton(onClick = {
                    pendingConfirm = null
                    onAction(confirm.action, inputsJsonObject(inputs))
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirm = null }) { Text("Cancel") }
            },
        )
    }
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(doc.body, key = { it.hashCode() }) { widget ->
            RenderWidget(
                widget = widget,
                scope = scope,
                inputs = inputs,
                onInput = { name, value -> inputs = inputs + (name to value) },
                onAction = ::dispatch,
                consoleLines = consoleLines,
                depth = 0,
            )
        }
        item {
            Text(
                "This screen was provided by the task.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun boundText(value: kotlinx.serialization.json.JsonElement): String =
    (value as? JsonPrimitive)?.content ?: value.toString()

private fun inputsJsonObject(inputs: Map<String, String>): kotlinx.serialization.json.JsonObject {
    val builder = kotlinx.serialization.json.JsonObjectBuilder()
    for ((key, value) in inputs) builder.put(key, JsonPrimitive(value))
    return builder.build()
}

@Composable
private fun Visible(widget: UiWidget, scope: UiBindingScope, content: @Composable () -> Unit) {
    val expr = widget.whenVisible ?: return content()
    if (UiBindings.isVisible(expr, scope)) content()
}

@Composable
private fun RenderWidget(
    widget: UiWidget,
    scope: UiBindingScope,
    inputs: Map<String, String>,
    onInput: (String, String) -> Unit,
    onAction: (UiAction) -> Unit,
    consoleLines: List<String>,
    depth: Int,
) {
    Visible(widget, scope) {
        when (widget) {
            is UiWidget.Column -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                widget.children.forEach { RenderWidget(it, scope, inputs, onInput, onAction, consoleLines, depth + 1) }
            }
            is UiWidget.Row -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                widget.children.forEach { RenderWidget(it, scope, inputs, onInput, onAction, consoleLines, depth + 1) }
            }
            is UiWidget.Card -> Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    widget.title?.let { SectionHeader(it) }
                    widget.children.forEach { RenderWidget(it, scope, inputs, onInput, onAction, consoleLines, depth + 1) }
                }
            }
            is UiWidget.Tabs -> DeclarativeTabs(widget, scope, inputs, onInput, onAction, consoleLines, depth)
            is UiWidget.Text -> Text(
                text = boundText(UiBindings.bind(widget.value, scope).value),
                style = when (widget.style) {
                    "headline" -> MaterialTheme.typography.headlineSmall
                    "label" -> MaterialTheme.typography.labelMedium
                    else -> MaterialTheme.typography.bodyMedium
                },
            )
            is UiWidget.Markdown -> Column {
                // v1 renders markdown as plain lines (headings/bold not styled) — honest subset, ADR-055.
                widget.value.lines().forEach { line ->
                    Text(text = boundText(UiBindings.bind(line, scope).value), style = MaterialTheme.typography.bodySmall)
                }
            }
            is UiWidget.Input -> OutlinedTextField(
                value = inputs[widget.name] ?: widget.default.orEmpty(),
                onValueChange = { onInput(widget.name, it) },
                label = { Text(widget.label ?: widget.name) },
                visualTransformation = if (widget.secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            is UiWidget.Toggle -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(widget.label ?: widget.name, style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = (inputs[widget.name] ?: widget.default.toString()) == "true",
                    onCheckedChange = { onInput(widget.name, it.toString()) },
                )
            }
            is UiWidget.Select -> {
                var selected by remember(widget.name) { mutableStateOf(widget.defaultIndex) }
                Column {
                    widget.label?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        widget.options.forEachIndexed { index, option ->
                            val label = boundText(UiBindings.bind(option.label, scope).value)
                            if (index == selected) {
                                Button(onClick = { selected = index; onInput(widget.name, option.value) }) { Text(label) }
                            } else {
                                OutlinedButton(onClick = { selected = index; onInput(widget.name, option.value) }) { Text(label) }
                            }
                        }
                    }
                }
            }
            is UiWidget.Button -> Button(
                onClick = { onAction(widget.action) },
                colors = when (widget.style) {
                    "outlined" -> ButtonDefaults.outlinedButtonColors()
                    "danger" -> ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    else -> ButtonDefaults.buttonColors()
                },
            ) {
                Text(boundText(UiBindings.bind(widget.label, scope).value))
            }
            is UiWidget.Progress -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            is UiWidget.Table -> {
                val dynamicRows: List<List<String>> = widget.rowsBinding?.let { binding ->
                    val bound = UiBindings.bind("{{$binding}}", scope).value
                    (bound as? kotlinx.serialization.json.JsonArray)?.map { row ->
                        when (row) {
                            is kotlinx.serialization.json.JsonArray -> row.map { cell -> (cell as? JsonPrimitive)?.content ?: cell.toString() }
                            is JsonPrimitive -> listOf(row.content)
                            else -> listOf(row.toString())
                        }
                    } ?: emptyList()
                } ?: widget.rows
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            widget.columns.forEach { column ->
                                Text(column, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            }
                        }
                        dynamicRows.forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                row.forEach { cell ->
                                    Text(
                                        boundText(UiBindings.bind(cell, scope).value),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            is UiWidget.Image -> Text(
                "[image: ${widget.path}]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            is UiWidget.LogView -> Column {
                SectionHeader(if (widget.source == "run") "Run log" else "Console")
                if (consoleLines.isEmpty()) {
                    Text("No console lines yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    consoleLines.takeLast(100).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
            is UiWidget.Stat -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(widget.label, style = MaterialTheme.typography.labelMedium)
                Text(boundText(UiBindings.bind(widget.value, scope).value), style = MaterialTheme.typography.bodyMedium)
            }
            is UiWidget.ProfilePicker -> Text(
                "Profile selection lives in the Profiles tab (v1 picker).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DeclarativeTabs(
    widget: UiWidget.Tabs,
    scope: UiBindingScope,
    inputs: Map<String, String>,
    onInput: (String, String) -> Unit,
    onAction: (UiAction) -> Unit,
    consoleLines: List<String>,
    depth: Int,
) {
    var selected by remember(widget) { mutableStateOf(0) }
    Column {
        TabRow(selectedTabIndex = selected) {
            widget.items.forEachIndexed { index, item ->
                Tab(selected = selected == index, onClick = { selected = index }, text = { Text(item.title) })
            }
        }
        widget.items.getOrNull(selected)?.children?.forEach {
            RenderWidget(it, scope, inputs, onInput, onAction, consoleLines, depth + 1)
        }
    }
}
