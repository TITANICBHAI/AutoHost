package app.autohost.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.autohost.MainActivity
import app.autohost.Routes
import app.autohost.R
import app.autohost.core.designsystem.components.EmptyState
import app.autohost.core.designsystem.components.SectionHeader
import kotlinx.coroutines.launch

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
        Routes.SETUP -> {
            // "What this phone allows": reached from Settings and from a task that needs something. Not a tab, not onboarding.
            androidx.compose.material3.Surface {
                Column(Modifier.fillMaxSize()) {
                    TextButton(onClick = { onNavigate(Routes.LIBRARY, null) }) { Text("\u2190 Back") }
                    app.autohost.ui2.PhoneScreen(activity, onNavigate, onAddTask = { onNavigate(Routes.TEMPLATES, null) })
                }
            }
        }
        Routes.SCHEDULES -> {
            val graph = (activity.application as app.autohost.AutoHostApp).graph
            androidx.compose.material3.Surface {
                Column(Modifier.fillMaxSize()) {
                    TextButton(onClick = { onNavigate(Routes.LIBRARY, null) }) { Text("\u2190 Tasks") }
                    ScheduleScreen(graph)
                }
            }
        }
        Routes.MACRO -> {
            val graph = (activity.application as app.autohost.AutoHostApp).graph
            androidx.compose.material3.Surface {
                app.autohost.ui2.macro.MacroEditorScreen(
                    graph = graph,
                    devRoot = activity.getExternalFilesDir("dev"),
                    onDone = { taskId ->
                        if (taskId != null) onNavigate(Routes.TASK_DETAIL, taskId) else onNavigate(Routes.LIBRARY, null)
                    },
                )
            }
        }
        Routes.TEMPLATES -> {
            val graph = (activity.application as app.autohost.AutoHostApp).graph
            androidx.compose.material3.Surface {
                NewFromTemplateScreen(
                    graph = graph,
                    onOpenTask = { taskId -> onNavigate(Routes.TASK_DETAIL, taskId) },
                    onBack = { onNavigate(Routes.LIBRARY, null) },
                )
            }
        }
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
                SettingsScreen(
                    graph,
                    onOpenHealth = { onNavigate(Routes.HEALTH, null) },
                    onOpenSurfaces = { onNavigate(Routes.SURFACES, null) },
                    onOpenAiSettings = { onNavigate(Routes.AI_SETTINGS, null) },
                )
            }
        }
        Routes.AI_SETTINGS -> {
            val graph = (activity.application as app.autohost.AutoHostApp).graph
            androidx.compose.material3.Surface {
                AiSettingsHost(graph, onBack = { onNavigate(Routes.SETTINGS, null) })
            }
        }
        Routes.ASK -> {
            val graph = (activity.application as app.autohost.AutoHostApp).graph
            androidx.compose.material3.Surface {
                AskSheetHost(
                    graph,
                    onBack = { onNavigate(Routes.LIBRARY, null) },
                    onOpenRun = { runId -> onNavigate(Routes.RUN, runId) },
                )
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

private data class TabSpec(val labelRes: Int, val iconRes: Int)

private val MAIN_TABS = listOf(
    TabSpec(R.string.tab_tasks, R.drawable.ic_nav_tasks),
    TabSpec(R.string.tab_agent, R.drawable.ic_nav_agent),
    TabSpec(R.string.tab_build, R.drawable.ic_nav_build),
    TabSpec(R.string.tab_activity, R.drawable.ic_nav_activity),
    TabSpec(R.string.tab_settings, R.drawable.ic_nav_settings),
)

/**
 * The shell (UI v3): first run shows the full-screen onboarding, never a tab; after that a bottom bar with five icons:
 * Tasks, Agent, Build, Activity, Settings. What each tab shows comes from the ui2 models.
 */
@Composable
private fun MainTabs(activity: MainActivity, onNavigate: (Routes, String?) -> Unit, initialTab: Int) {
    val graph = (activity.application as app.autohost.AutoHostApp).graph
    var tab by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(initialTab) }
    var addOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var loaded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val onboarded by graph.onboarded.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Read the saved flag before drawing anything, so a returning person never sees the welcome flash by.
    LaunchedEffect(Unit) {
        graph.loadOnboarded()
        loaded = true
    }
    if (addOpen) {
        app.autohost.ui2.AddTaskDialog(
            graph = graph,
            onTemplates = { addOpen = false; onNavigate(Routes.TEMPLATES, null) },
            onMacro = { addOpen = false; onNavigate(Routes.MACRO, null) },
            onInstalled = { taskId -> addOpen = false; onNavigate(Routes.TASK_DETAIL, taskId) },
            onDismiss = { addOpen = false },
        )
    }
    if (!loaded) {
        Box(Modifier.fillMaxSize())
    } else if (!onboarded) {
        app.autohost.ui2.OnboardingFlow(
            activity = activity,
            onNavigate = onNavigate,
            onAddTask = { addOpen = true },
            onFinished = { scope.launch { graph.setOnboarded(true) } },
        )
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    MAIN_TABS.forEachIndexed { i, spec ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(painterResource(spec.iconRes), contentDescription = null) },
                            label = { Text(stringResource(spec.labelRes)) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    0 -> LibraryScreen(
                        activity, onNavigate,
                        onAddTask = { addOpen = true },
                        onOpenSetup = { onNavigate(Routes.SETUP, null) },
                    )
                    1 -> AskSheetHost(
                        graph,
                        onBack = { tab = 0 },
                        onOpenRun = { runId -> onNavigate(Routes.RUN, runId) },
                        simple = true,
                        onOpenAiSettings = { onNavigate(Routes.AI_SETTINGS, null) },
                    )
                    2 -> app.autohost.ui2.BuildScreen(activity, onNavigate, onAddTask = { addOpen = true })
                    3 -> app.autohost.ui2.ActivityScreen(activity, onNavigate)
                    else -> app.autohost.ui2.SettingsHub(activity, onNavigate, onAddTask = { addOpen = true })
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(
    activity: MainActivity,
    onNavigate: (Routes, String?) -> Unit,
    onAddTask: () -> Unit = {},
    onOpenSetup: () -> Unit = {},
) {
    val graph = (activity.application as app.autohost.AutoHostApp).graph
    // U-01: installed tasks (templates, examples, imports) and dev-folder tasks in ONE list.
    val entries by remember(graph) {
        TaskCatalog.observe(graph.data.tasks.observeActive(), graph.devTasks.tasks)
    }.collectAsState(initial = emptyList())
    val runs by remember(graph) { graph.data.runs.observeRecent(200) }.collectAsState(initial = emptyList())
    val labs by graph.labsEnabled.collectAsState()
    val rows = remember(entries, runs) { TaskListModel.rows(entries, runs) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val visible = remember(rows, query) { TaskListModel.search(rows, query) }
    val readiness = app.autohost.ui2.rememberTaskReadiness(activity, graph, entries)

    LaunchedEffect(Unit) {
        graph.loadLabsEnabled()
        graph.refreshDevTasks()
        // P7-08: keep dynamic shortcuts aligned with the installed dev-folder tasks (long-press menu).
        graph.devTasks.tasks.value.take(4).let { tasks ->
            app.autohost.surfaces.Shortcuts.syncDynamicShortcuts(activity, tasks.map { it.taskId to it.name })
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                app.autohost.ui2.BrandMark(32.dp)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 10.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    searching = !searching
                    if (!searching) query = ""
                }) { Text(stringResource(R.string.library_search)) }
                TextButton(onClick = onAddTask) { Text("+ " + stringResource(R.string.library_add)) }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Text("\u22EE") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Quick macro") },
                            onClick = {
                                menuOpen = false
                                onNavigate(Routes.MACRO, null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_refresh)) },
                            onClick = {
                                menuOpen = false
                                graph.refreshDevTasks()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_history)) },
                            onClick = {
                                menuOpen = false
                                onNavigate(Routes.RUNS_HISTORY, null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_settings)) },
                            onClick = {
                                menuOpen = false
                                onNavigate(Routes.SETTINGS, null)
                            },
                        )
                        if (labs) {
                            // Labs (Settings): unfinished tools stay out of the way until the person asks for them.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_ask)) },
                                onClick = {
                                    menuOpen = false
                                    onNavigate(Routes.ASK, null)
                                },
                            )
                            // P6-09: record a flow — arms the a11y tap; the REC overlay stays visible over other apps and
                            // its Stop parks the session. Android 10+ blocks background activity launches, so returning
                            // happens through this entry, which flips to "Review" once a session is waiting.
                            val recorderState by graph.recorder.state.collectAsState()
                            when {
                                recorderState.recording -> DropdownMenuItem(
                                    text = { Text("\u25CF REC (${recorderState.stepCount})") },
                                    onClick = {},
                                    enabled = false,
                                )
                                recorderState.result != null -> DropdownMenuItem(
                                    text = { Text("\u2713 Review") },
                                    onClick = {
                                        menuOpen = false
                                        onNavigate(Routes.RECORDER_REVIEW, null)
                                    },
                                )
                                else -> DropdownMenuItem(
                                    text = { Text("\u25CF " + stringResource(R.string.library_record)) },
                                    onClick = {
                                        menuOpen = false
                                        val started = graph.recorder.start(graph.recorderOverlay())
                                        if (started) onNavigate(Routes.RECORDER_REVIEW, null)
                                    },
                                )
                            }
                            // P11-05 (ADR-083): the in-app Task Studio.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_studio)) },
                                onClick = {
                                    menuOpen = false
                                    onNavigate(Routes.STUDIO, null)
                                },
                            )
                        }
                    }
                }
            }
        }
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.library_search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        app.autohost.ui2.TasksAttention(activity, graph, entries, runs, onNavigate, onOpenSetup)
        SectionHeader(stringResource(R.string.library_section_title))
        when {
            entries.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.library_empty_title),
                    body = stringResource(R.string.library_empty_body),
                )
                Button(onClick = onAddTask, modifier = Modifier.padding(horizontal = 24.dp)) {
                    Text(stringResource(R.string.library_empty_action))
                }
            }
            visible.isEmpty() -> Text(
                stringResource(R.string.library_no_match, query.trim()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            else -> {
                val nowMs = remember(rows) { graph.clock.nowMs() }
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.entry.taskId }) { row ->
                        TaskCard(graph, row, nowMs, onNavigate, readiness[row.entry.taskId], onOpenSetup)
                    }
                }
            }
        }
    }
}

/** One Library card (S04): name, status chip, last result, and the one button that makes sense for the task. */
@Composable
private fun TaskCard(
    graph: app.autohost.AppGraph,
    row: TaskRow,
    nowMs: Long,
    onNavigate: (Routes, String?) -> Unit,
    readiness: app.autohost.ui2.TaskReadiness.Result? = null,
    onOpenSetup: () -> Unit = {},
) {
    val entry = row.entry
    val devTask = entry.dev
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                val running = row.status == TaskStatus.RUNNING
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        stringResource(if (running) R.string.library_status_running else R.string.library_status_ready),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            val ago = row.lastRunAt?.let { agoText(TaskListModel.age(nowMs, it)) } ?: ""
            Text(
                when (row.lastResult) {
                    LastResult.NONE -> stringResource(R.string.library_last_none)
                    LastResult.SUCCEEDED -> stringResource(R.string.library_last_succeeded, ago)
                    LastResult.FAILED -> stringResource(R.string.library_last_failed, ago)
                    LastResult.STOPPED -> stringResource(R.string.library_last_stopped, ago)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (readiness != null && !readiness.ready) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        readiness.line(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenSetup) { Text("Set up") }
                }
            }
            if (devTask != null) {
                Button(
                    onClick = {
                        // X-19: dev-folder starts go through the facade — the inline StartRequest bypassed the packs
                        // injection (ADR-089 prediction, fixed by the DevTaskStarter extraction)
                        val record = graph.devTaskStarter.start(
                            devTask.taskId, devTask.name, devTask.manifest, devTask.config, devTask.dir.absolutePath,
                        ).getOrNull()
                        if (record != null) onNavigate(Routes.RUN, record.runId)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.action_run))
                }
            } else {
                // Installed tasks run from their detail page (permissions, setup, schedule live there).
                Button(
                    onClick = { onNavigate(Routes.TASK_DETAIL, entry.taskId) },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.action_open))
                }
            }
        }
    }
}

@Composable
private fun agoText(age: Pair<TaskListModel.AgeUnit, Long>): String = when (age.first) {
    TaskListModel.AgeUnit.NOW -> stringResource(R.string.age_now)
    TaskListModel.AgeUnit.MINUTES -> stringResource(R.string.age_minutes, age.second.toInt())
    TaskListModel.AgeUnit.HOURS -> stringResource(R.string.age_hours, age.second.toInt())
    TaskListModel.AgeUnit.DAYS -> stringResource(R.string.age_days, age.second.toInt())
}
