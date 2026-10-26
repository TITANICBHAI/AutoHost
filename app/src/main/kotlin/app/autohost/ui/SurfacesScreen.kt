package app.autohost.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.autohost.AppGraph
import app.autohost.R
import app.autohost.core.designsystem.components.SectionHeader
import app.autohost.core.engine.surfaces.BubblePolicy
import app.autohost.core.engine.surfaces.TileBindings

/**
 * P7-06..12: one screen for every system surface (plan §10.3) — bubble mode, the five tile
 * bindings (+ API 33 "add tile" nudge), the widget task picker, the external-trigger token,
 * the inspector, and the volume-key stop. Settings here write straight through to
 * [app.autohost.surfaces.SurfacesPrefs]; the a11y key filter reads its spec per key event, so
 * toggles take effect immediately without a service restart.
 */
@Composable
fun SurfacesScreen(graph: AppGraph, onBack: () -> Unit) {
    val prefs = graph.surfacesPrefs
    val clipboard = LocalClipboardManager.current
    val tasks by graph.devTasks.tasks.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Surfaces", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("‹ Back") }
        }
        Text(
            "System entry points: bubble, Quick Settings tiles, shortcuts, home widget, external " +
                "intent, inspector and the global stop gesture.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionHeader("Floating bubble")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                var mode by remember { mutableStateOf(prefs.bubbleMode()) }
                Text("Show a status + Stop bubble over other apps", style = MaterialTheme.typography.titleSmall)
                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BubblePolicy.MODES.forEach { option ->
                        FilterChip(
                            selected = mode == option,
                            onClick = {
                                mode = option
                                prefs.setBubbleMode(option)
                            },
                            label = { Text(option) },
                        )
                    }
                }
                Text(
                    when (mode) {
                        BubblePolicy.MODE_AUTO -> "auto: visible while a run that acts on the UI is active (default)."
                        BubblePolicy.MODE_ALWAYS -> "always: visible while any run is active."
                        else -> "off: never; use the notification or the volume-key stop."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        SectionHeader("Quick Settings tiles")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bind \"AutoHost 1–5\" to a task, then add the tile from the edit panel.", style = MaterialTheme.typography.bodySmall)
                for (slot in 1..TileBindings.TILE_COUNT) {
                    var binding by remember { mutableStateOf(prefs.tileBinding(slot)) }
                    var expanded by remember { mutableStateOf(false) }
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Tile $slot", style = MaterialTheme.typography.titleSmall)
                            Text(
                                binding?.taskId ?: "tap to bind",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (expanded) {
                            Column(modifier = Modifier.padding(top = 4.dp)) {
                                Text("Tap a task to bind (tap the current one to unbind):", style = MaterialTheme.typography.bodySmall)
                                tasks.forEach { task ->
                                    TextButton(onClick = {
                                        binding = if (binding?.taskId == task.taskId) null else TileBindings.Binding(taskId = task.taskId)
                                        prefs.setTileBinding(slot, binding)
                                    }) {
                                        Text((if (binding?.taskId == task.taskId) "● " else "") + task.name)
                                    }
                                }
                                if (tasks.isEmpty()) Text("No dev-folder tasks yet.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    TextButton(onClick = { addTilesToQs(graph.appContext) }) {
                        Text("Suggest tile 1 to the system panel (Android 13+)")
                    }
                }
            }
        }

        SectionHeader("Home widget")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("The widget lists tasks with last status + Run. Pick which tasks it shows:", style = MaterialTheme.typography.bodySmall)
                var selected by remember { mutableStateOf(prefs.widgetTaskIds().toSet()) }
                tasks.forEach { task ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            // Empty selection means "show all"; the first tap narrows to explicit choices.
                            if (selected.isEmpty()) selected = tasks.map { it.taskId }.toSet()
                            selected = if (task.taskId in selected) selected - task.taskId else selected + task.taskId
                            prefs.setWidgetTaskIds(selected.toList())
                            app.autohost.surfaces.SurfaceRuns.refreshWidgets(graph)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (task.taskId in selected) "☑" else "☐")
                        Text(
                            "  ${task.name}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                Text(
                    "Empty selection = show all tasks (first 5).",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        SectionHeader("External triggers (RUN_TASK)")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Other apps (e.g. Tasker) start a task with an intent guarded by this per-install token.", style = MaterialTheme.typography.bodySmall)
                var token by remember { mutableStateOf(prefs.externalToken()) }
                Text("Token: ${token.take(10)}…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(token)) }) { Text("Copy token") }
                    TextButton(onClick = {
                        token = prefs.regenerateToken()
                    }) { Text("Regenerate") }
                }
                Text(
                    "adb shell am broadcast -a app.autohost.RUN_TASK -n " +
                        "${graph.appContext.packageName}/.surfaces.RunTaskReceiver " +
                        "--es token $token --es task TASK_ID",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
        }

        SectionHeader("Inspector")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Draws node bounds over any app; tap a node to copy its selector. Start here, then switch to the app you want to inspect. Needs accessibility on.", style = MaterialTheme.typography.bodySmall)
                var inspecting by remember { mutableStateOf(graph.inspector.inspecting) }
                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            inspecting = graph.inspector.start()
                        },
                        enabled = !graph.inspector.inspecting,
                    ) { Text("Start") }
                    OutlinedButton(
                        onClick = {
                            graph.inspector.stop()
                            inspecting = false
                        },
                        enabled = graph.inspector.inspecting,
                    ) { Text("Exit") }
                }
                if (graph.inspector.inspecting) {
                    Text("Scope: ${graph.inspector.inspectedPackage.ifEmpty { "next foreground app" }}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        SectionHeader("Volume-key stop")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                var enabled by remember { mutableStateOf(prefs.keyFilterEnabled()) }
                var keyCode by remember { mutableStateOf(prefs.keyFilterKeyCode()) }
                var hold by remember { mutableStateOf(prefs.keyFilterHoldMs().toFloat()) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hold to stop all runs", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Off by default. While armed, holding the key will not change the volume.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = {
                        enabled = it
                        prefs.setKeyFilterEnabled(it)
                    })
                }
                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                        onClick = {
                            keyCode = android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                            prefs.setKeyFilterKeyCode(keyCode)
                        },
                        label = { Text("Volume −") },
                    )
                    FilterChip(
                        selected = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP,
                        onClick = {
                            keyCode = android.view.KeyEvent.KEYCODE_VOLUME_UP
                            prefs.setKeyFilterKeyCode(keyCode)
                        },
                        label = { Text("Volume +") },
                    )
                }
                Text("Hold duration: ${hold.toInt() / 1000.0}s", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = hold,
                    onValueChange = {
                        hold = it
                        prefs.setKeyFilterHoldMs(it.toLong())
                    },
                    valueRange = 600f..3000f,
                )
            }
        }

        SectionHeader("Pinned shortcuts")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("One-tap launch from the home screen (pin from here or from a task page):", style = MaterialTheme.typography.bodySmall)
                tasks.forEach { task ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(task.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            app.autohost.surfaces.Shortcuts.requestPin(
                                graph.appContext,
                                task.taskId,
                                task.name,
                            )
                        }) { Text("Pin") }
                    }
                }
                if (tasks.isEmpty()) Text("No dev-folder tasks yet.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** API 33+: ask the system panel to offer the tile directly (no edit-panel hunt). */
private fun addTilesToQs(context: Context) {
    if (Build.VERSION.SDK_INT < 33) return
    runCatching {
        val statusBar = context.getSystemService(android.app.StatusBarManager::class.java) ?: return
        val componentName = android.content.ComponentName(context, "app.autohost.surfaces.Tile1Service")
        val icon = android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_autohost)
        val executor = ContextCompatExecutor(context)
        statusBar.requestAddTileService(
            componentName,
            "AutoHost 1",
            icon,
            executor,
        ) { }
    }
}

/** P7-08: the task-detail row offering the pinned shortcut (Surfaces screen lists the same action). */
@Composable
fun PinShortcutRow(graph: AppGraph, taskId: String, taskName: String) {
    TextButton(onClick = {
        app.autohost.surfaces.Shortcuts.requestPin(graph.appContext, taskId, taskName)
    }) { Text("＋ Add to home screen") }
}

/** A plain main-thread executor (requestAddTileService wants one; the callback is fire-and-forget). */
private class ContextCompatExecutor(context: Context) : java.util.concurrent.Executor {
    private val handler = android.os.Handler(context.mainLooper)
    override fun execute(command: Runnable) {
        handler.post(command)
    }
}
