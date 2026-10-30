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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.autohost.MainActivity
import app.autohost.Routes
import app.autohost.R
import app.autohost.core.designsystem.components.EmptyState
import app.autohost.core.designsystem.components.SectionHeader

/** Wave-1 shell: Tasks + Setup tabs, hand-rolled navigation into the Run screen. */
@Composable
fun AppNavHost(
    activity: MainActivity,
    route: Routes,
    runId: String?,
    onNavigate: (Routes, String?) -> Unit,
) {
    when (route) {
        Routes.LIBRARY -> MainTabs(activity, onNavigate, initialTab = 0)
        Routes.SETUP -> MainTabs(activity, onNavigate, initialTab = 1)
        Routes.SCHEDULES -> MainTabs(activity, onNavigate, initialTab = 2)
        Routes.RUN -> RunScreen(activity, runId = runId, onBack = { onNavigate(Routes.LIBRARY, null) })
        Routes.RUNS_HISTORY -> RunsHistoryScreen(
            activity,
            onOpenRun = { runIdArg -> onNavigate(Routes.RUN, runIdArg) },
            onBack = { onNavigate(Routes.LIBRARY, null) },
        )
        Routes.TASK_DETAIL -> {
            val graph = (activity.application as app.autohost.AutoHostApp).graph
            val taskId = runId ?: return
            androidx.compose.material3.Surface {
                TaskDetailScreen(graph, taskId = taskId, onBack = { onNavigate(Routes.LIBRARY, null) })
            }
        }
        Routes.HEALTH -> {
            val graph = (activity.application as app.autohost.AutoHostApp).graph
            androidx.compose.material3.Surface {
                // P8-14: Fix buttons launch their flow through the FixHub (system screens,
                // runtime-permission request activity); in-app routes navigate from here.
                HealthScreen(graph, onOpenFix = { action ->
                    if (action in app.autohost.fix.FixHub.IN_APP_ROUTES) {
                        when (action) {
                            "OPEN_INSECURE_SWITCH", "OPEN_DEV_API_SETTINGS" -> onNavigate(Routes.SETTINGS, null)
                            "OPEN_PERMISSION_REVIEW" -> onNavigate(Routes.SETTINGS, null)
                            "OPEN_SURFACES" -> onNavigate(Routes.SURFACES, null)
                        }
                    } else {
                        app.autohost.fix.FixHub.launch(activity, action)
                    }
                })
            }
        }
        Routes.SETTINGS -> {
            val graph = (activity.application as app.autohost.AutoHostApp).graph
            androidx.compose.material3.Surface {
                SettingsScreen(graph, onOpenHealth = { onNavigate(Routes.HEALTH, null) }, onOpenSurfaces = { onNavigate(Routes.SURFACES, null) })
            }
        }
        Routes.SURFACES -> {
            val graph = (activity.application as app.autohost.AutoHostApp).graph
            androidx.compose.material3.Surface {
                SurfacesScreen(graph, onBack = { onNavigate(Routes.SETTINGS, null) })
            }
        }
        Routes.RECORDER_REVIEW -> {
            val graph = (activity.application as app.autohost.AutoHostApp).graph
            androidx.compose.material3.Surface {
                RecorderReviewScreen(
                    controller = graph.recorder,
                    onBack = {
                        onNavigate(Routes.LIBRARY, null)
                        graph.refreshDevTasks() // a saved flow task appears after the refresh
                    },
                )
            }
        }
        Routes.STUDIO -> {
            // P11-05 (ADR-083): the in-app Task Studio (dev-folder drafts + the repo tab).
            val graph = (activity.application as app.autohost.AutoHostApp).graph
            androidx.compose.material3.Surface {
                app.autohost.ui.studio.TaskStudioScreen(graph, onBack = { onNavigate(Routes.LIBRARY, null) })
            }
        }
    }
}

@Composable
private fun MainTabs(activity: MainActivity, onNavigate: (Routes, String?) -> Unit, initialTab: Int) {
    var tab by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(initialTab) }
    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_tasks)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_setup)) })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Schedules") })
            }
            when (tab) {
                0 -> LibraryScreen(activity, onNavigate)
                1 -> SetupScreen(activity)
                else -> {
                    val graph = (activity.application as app.autohost.AutoHostApp).graph
                    app.autohost.ui.ScheduleScreen(graph)
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(activity: MainActivity, onNavigate: (Routes, String?) -> Unit) {
    val graph = (activity.application as app.autohost.AutoHostApp).graph
    val tasks by graph.devTasks.tasks.collectAsState()
    var showTemplates by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        graph.refreshDevTasks()
        // P7-08: keep dynamic shortcuts aligned with the installed dev-folder tasks (long-press menu).
        graph.devTasks.tasks.value.take(4).let { tasks ->
            app.autohost.surfaces.Shortcuts.syncDynamicShortcuts(activity, tasks.map { it.taskId to it.name })
        }
    }

    if (showTemplates) {
        NewFromTemplateScreen(
            graph = graph,
            onOpenTask = { taskId ->
                showTemplates = false
                onNavigate(Routes.TASK_DETAIL, taskId)
            },
            onBack = { showTemplates = false },
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onNavigate(Routes.RUNS_HISTORY, null) }) { Text("History") }
                OutlinedButton(onClick = { showTemplates = true }) { Text("+ Template") }
                // P6-09: record a flow — arms the a11y tap; the ● REC overlay (a11y service
                // window) stays visible over other apps and its Stop parks the session. Android
                // 10+ blocks background activity launches, so returning happens through this
                // button, which flips to "✓ Review" once a session is waiting.
                val recorderState by graph.recorder.state.collectAsState()
                when {
                    recorderState.recording -> OutlinedButton(onClick = {}, enabled = false) {
                        Text("● REC (${recorderState.stepCount})")
                    }
                    recorderState.result != null -> OutlinedButton(onClick = {
                        onNavigate(Routes.RECORDER_REVIEW, null)
                    }) { Text("✓ Review") }
                    else -> OutlinedButton(onClick = {
                        val started = graph.recorder.start(graph.recorderOverlay())
                        if (started) onNavigate(Routes.RECORDER_REVIEW, null)
                    }) { Text("● Record") }
                }
                OutlinedButton(onClick = { graph.refreshDevTasks() }) {
                    Text(stringResource(R.string.action_refresh))
                }
                // P11-05 (ADR-083): the in-app Task Studio.
                OutlinedButton(onClick = { onNavigate(Routes.STUDIO, null) }) { Text("Studio") }
            }
        }
        SectionHeader("DEV-FOLDER TASKS")
        if (tasks.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.library_empty_title),
                body = stringResource(R.string.library_empty_body),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tasks, key = { it.dir.absolutePath }) { task ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(task.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                task.taskId,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            )
                            Text(
                                "${task.manifest.runtime.type} · ${task.manifest.version}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    val record = graph.orchestrator.start(
                                        app.autohost.core.engine.orchestrator.StartRequest(
                                            taskId = task.taskId,
                                            taskName = task.name,
                                            manifest = task.manifest,
                                            config = task.config,
                                            profile = kotlinx.serialization.json.JsonObject(emptyMap()),
                                            profileId = null,
                                            taskPath = task.dir.absolutePath,
                                            runDir = java.io.File(
                                                graph.runsRoot(),
                                                app.autohost.core.common.Ids.ulid(graph.clock.nowMs()),
                                            ).absolutePath,
                                        ),
                                    ).getOrNull()
                                    if (record != null) onNavigate(Routes.RUN, record.runId)
                                },
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                Text(stringResource(R.string.action_run))
                            }
                        }
                    }
                }
            }
        }
    }
}
