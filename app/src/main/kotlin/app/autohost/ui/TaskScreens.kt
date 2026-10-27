package app.autohost.ui

import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.collectAsState
import app.autohost.AppGraph
import app.autohost.core.data.db.TaskEntity
import app.autohost.core.engine.tasks.GrantService
import app.autohost.core.engine.tasks.ImportReport
import app.autohost.core.designsystem.components.SectionHeader
import app.autohost.core.designsystem.components.StatusChip
import app.autohost.core.model.TaskManifest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

/**
 * P2-18: the full Library — search, filter chips, per-task cards with runtime badge, last-run and
 * enable state. Selection opens the Task detail screen.
 */
@Composable
fun LibraryScreen(graph: AppGraph, onOpenTask: (String) -> Unit, onImport: () -> Unit) {
    var tasks by remember { mutableStateOf<List<TaskEntity>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var showTemplates by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        tasks = graph.data.tasks.observeActive().first()
    }
    if (showTemplates) {
        NewFromTemplateScreen(graph = graph, onOpenTask = onOpenTask, onBack = { showTemplates = false })
        return
    }
    val filtered = tasks.filter { it.name.contains(query, ignoreCase = true) || it.taskId.contains(query, ignoreCase = true) }
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Tasks", style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showTemplates = true }) { Text("+ Template") }
                    OutlinedButton(onClick = onImport) { Text("+ Import") }
                }
            }
        }
        item {
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        if (filtered.isEmpty()) {
            item { Text("No tasks yet — Import a bundle or copy one into the dev folder.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(filtered, key = { it.taskId }) { task ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onOpenTask(task.taskId) }) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(task.name, style = MaterialTheme.typography.titleMedium)
                        StatusChip("v" + task.activeVersion, neutral = true)
                    }
                    Text(task.taskId, style = MaterialTheme.typography.bodySmall)
                    Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(if (task.enabled) "READY" else "DISABLED", success = task.enabled)
                        StatusChip(task.source, neutral = true)
                    }
                }
            }
        }
    }
}

/**
 * P2-18: task detail with Overview / Config / Profiles / History tabs (schedule and flow tabs arrive
 * with their phases). Run starts through the orchestrator; Remove is two-step with consequences shown.
 */
@Composable
fun TaskDetailScreen(graph: AppGraph, taskId: String, onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var manifest by remember { mutableStateOf<TaskManifest?>(null) }
    var config by remember { mutableStateOf<JsonObject>(JsonObject(emptyMap())) }
    var configError by remember { mutableStateOf<String?>(null) }
    var removed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(taskId) {
        val latest = graph.data.tasks.latestVersion(taskId)
        manifest = latest?.manifestJson?.let {
            kotlinx.serialization.json.Json.decodeFromString(TaskManifest.serializer(), it)
        }
        config = graph.data.tasks.configOf(taskId)
    }

    if (removed) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Task removed.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onBack) { Text("Back") }
        }
        return
    }
    val m = manifest ?: return
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(m.name, style = MaterialTheme.typography.headlineSmall)
                    Text("${m.id} · v${m.version} · ${m.runtime.type}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onBack) { Text("‹ Back") }
            }
        }
        item {
            val base = if (m.runtime.type == "flow") {
                listOf("Overview", "Config", "Profiles", "History", "Flow")
            } else {
                listOf("Overview", "Config", "Profiles", "History")
            }
            // P7-01/02: declarative/html tasks get a UI tab (S16); Tier A tasks keep using Config.
            val hasTaskUi = m.ui?.mode == "declarative" || m.ui?.mode == "html"
            val tabs = base + if (hasTaskUi) listOf("UI") else emptyList()
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                }
            }
        }
        when (tab) {
            0 -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(m.description.ifEmpty { "No description." }, style = MaterialTheme.typography.bodyMedium)
                            SectionHeader("Requirements")
                            m.needs.capabilities.forEach { need ->
                                val availability = graph.availabilityOfCapability(need.id)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(need.id, style = MaterialTheme.typography.bodySmall)
                                    when (availability) {
                                        is app.autohost.core.model.Availability.Available -> StatusChip("AVAILABLE", success = true)
                                        is app.autohost.core.model.Availability.NeedsSetup -> StatusChip("NEEDS_SETUP", warning = true)
                                        else -> StatusChip("UNSUPPORTED", neutral = true)
                                    }
                                }
                            }
                            m.needs.apps.forEach { app ->
                                val installed = graph.probe.appInfo(app.packageName)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                    StatusChip(if (installed == null) "MISSING" else "INSTALLED", success = installed != null)
                                }
                            }
                            Button(
                                onClick = {
                                    scope.launch { graph.startRun(taskId, null, triggerType = "manual") }
                                },
                                modifier = Modifier.padding(top = 8.dp),
                            ) { Text("▶ Run") }
                            // P7-08: one-tap launch from the launcher (plan §10.3).
                            PinShortcutRow(graph = graph, taskId = taskId, taskName = m.name)
                        }
                    }
                }
                // P8-04: the Triggers section (S22) lives on the Overview tab.
                item { TriggersSection(graph, taskId) }
            }
            1 -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            ConfigForm(
                                schema = m.configSchema,
                                values = config,
                                onValueChange = { key, value ->
                                    config = JsonObject(config + (key to value))
                                },
                            )
                            configError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    val problems = ConfigFormModel.validate(ConfigFormModel.fields(m.configSchema), config)
                                    if (problems.isEmpty()) {
                                        scope.launch { graph.data.tasks.saveConfig(taskId, config) }
                                        configError = null
                                    } else {
                                        configError = problems.joinToString("; ")
                                    }
                                }) { Text("Save") }
                                // Scoped values re-resolve grants on next run; the review flow checks fingerprints.
                            }
                        }
                    }
                }
            }
            2 -> {
                item { ProfilesSection(graph, taskId, m) }
            }
            3 -> {
                item { HistorySection(graph, taskId) }
            }
            4 -> {
                // P6-10: flow view / edit-as-text (only rendered for runtime.type == "flow").
                item { FlowSection(graph, taskId, m) }
            }
        }
        if ((m.ui?.mode == "declarative" || m.ui?.mode == "html") && tab == tabsLastIndex(m)) {
            item {
                UiSection(graph, taskId, m) { route ->
                    tab = when (route) {
                        "overview" -> 0
                        "config" -> 1
                        "profiles" -> 2
                        "history" -> 3
                        else -> tab
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = {
                scope.launch {
                    graph.data.tasks.remove(taskId)
                    graph.taskRemover.remove(taskId, keepHistory = true)
                    removed = true
                }
            }) { Text("Remove task… (keeps run history)") }
        }
    }
}

/** P2-17: profiles list + create/edit inline (secrets via masked fields, archive). */
@Composable
private fun ProfilesSection(graph: AppGraph, taskId: String, manifest: TaskManifest) {
    var profiles by remember { mutableStateOf<List<app.autohost.core.data.db.ProfileEntity>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(taskId) {
        graph.data.profiles.observeActive(taskId).collect { profiles = it }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Profiles")
        Text(
            "Profiles keep separate settings, secrets and history per account or instance.",
            style = MaterialTheme.typography.bodySmall,
        )
        profiles.forEach { profile ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(profile.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "secrets: ${if (profile.secretKeys.isEmpty()) "none" else profile.secretKeys.joinToString()}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Row {
                        TextButton(onClick = {
                            scope.launch {
                                graph.data.profiles.create(taskId, profile.name + " (copy)", profile.valuesJson, profile.secretKeys, profile.targetJson)
                            }
                        }) { Text("Clone") }
                        TextButton(onClick = {
                            scope.launch { graph.data.profiles.archive(profile.profileId) }
                        }) { Text("Archive") }
                    }
                }
            }
        }
        if (!creating) {
            OutlinedButton(onClick = { creating = true }) { Text("+ New profile") }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Profile name") },
                        singleLine = true,
                    )
                    Text("Secrets and profile values can be filled in after creation.", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                val keys = ConfigFormModel.fields(manifest.profileSchema).filter { it.secret }.map { it.key }
                                graph.data.profiles.create(taskId, newName.ifBlank { "Profile" }, "{}", keys)
                                creating = false
                                newName = ""
                            }
                        }) { Text("Create") }
                        TextButton(onClick = { creating = false }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySection(graph: AppGraph, taskId: String) {
    var runs by remember { mutableStateOf<List<app.autohost.core.data.db.RunEntity>>(emptyList()) }
    LaunchedEffect(taskId) {
        runs = kotlinx.coroutines.runBlocking { graph.data.runs.recentOfTask(taskId, limit = 20) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader("Recent runs")
        if (runs.isEmpty()) Text("No runs yet.", style = MaterialTheme.typography.bodySmall)
        runs.forEach { run ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(run.runId.takeLast(8) + " · " + run.trigger, style = MaterialTheme.typography.bodySmall)
                StatusChip(run.status, success = run.status == "SUCCEEDED", error = run.status == "FAILED")
            }
        }
    }
}

/**
 * P2-11: the Permission Review — one row per capability with plain-language meaning, resolved scope,
 * required/optional, live availability; optional rows carry a switch; footer Allow / Deny.
 */
@Composable
fun PermissionReviewScreen(
    graph: AppGraph,
    manifest: TaskManifest,
    config: JsonObject,
    onApproved: (List<GrantService.GrantRecord>) -> Unit,
    onDenied: () -> Unit,
) {
    var records by remember { mutableStateOf<List<GrantService.GrantRecord>>(emptyList()) }
    var optionalDenied by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(manifest.id) {
        records = graph.grantService.resolve(
            manifest,
            config,
            JsonObject(emptyMap()),
        )
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("${manifest.name} wants to…", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Capabilities resolve to concrete scopes now; changing a scoped value re-asks later.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(records, key = { it.permission }) { record ->
            val optional = !record.required
            val denied = record.permission in optionalDenied
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.padding(end = 8.dp).weight(1f)) {
                        Text(record.capability, style = MaterialTheme.typography.titleSmall)
                        Text(scopeSummary(record), style = MaterialTheme.typography.bodySmall)
                        if (optional) Text("Optional", style = MaterialTheme.typography.labelSmall)
                    }
                    if (optional) {
                        Switch(checked = !denied, onCheckedChange = { allowed ->
                            optionalDenied = if (allowed) optionalDenied - record.permission else optionalDenied + record.permission
                        })
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val approved = records.filter { it.required || it.permission !in optionalDenied }
                        graph.data.grants.replaceAll(manifest.id, approved, granted = true)
                        onApproved(approved)
                    }
                }) { Text("Allow") }
                OutlinedButton(onClick = onDenied) { Text("Deny") }
            }
        }
    }
}

private fun scopeSummary(record: GrantService.GrantRecord): String {
    val packages = (record.scope["packages"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    return when {
        packages == null -> "scope: unrestricted"
        packages.isEmpty() -> "scope: none resolved yet"
        else -> "only in " + packages.joinToString(", ")
    }
}

/** P2-04/P2-03: the ImportReport screen — errors, warnings, compat, sha; Install only when reviewable. */
@Composable
fun ImportReportScreen(
    report: ImportReport,
    canInstall: Boolean,
    onReviewPermissions: (() -> Unit)?,
    onInstall: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Import report", style = MaterialTheme.typography.headlineSmall)
            Text(report.bundleName, style = MaterialTheme.typography.bodyMedium)
            if (report.sha256.isNotEmpty()) {
                Text("sha256: ${report.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (report.errors.isNotEmpty()) {
            item { SectionHeader("Errors (blocking)") }
            items(report.errors) { issue ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        StatusChip(issue.rule, error = true)
                        Text(issue.describe(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        if (report.warnings.isNotEmpty()) {
            item { SectionHeader("Warnings") }
            items(report.warnings) { issue ->
                Text("⚠ " + issue.describe(), style = MaterialTheme.typography.bodySmall)
            }
        }
        report.scan?.let { scan ->
            item { SectionHeader("Import scan") }
            if (scan.desktopOnly.isNotEmpty()) {
                items(scan.desktopOnly) { hit ->
                    Text("✗ ${hit.module} (${hit.file}): ${hit.suggestion}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (scan.unavailable.isNotEmpty()) {
                items(scan.unavailable) { module ->
                    Text("• $module not bundled", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (report.compat.isNotEmpty()) {
            item { SectionHeader("Compatibility") }
            items(report.compat, key = { it.kind + it.id }) { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${row.kind}: ${row.id}", style = MaterialTheme.typography.bodySmall)
                    StatusChip(
                        row.status,
                        success = row.status == "AVAILABLE",
                        warning = row.status == "NEEDS_SETUP",
                        error = row.status == "DENIED",
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canInstall && onReviewPermissions != null) Button(onClick = onReviewPermissions) { Text("Review permissions") }
                if (canInstall && onReviewPermissions == null && onInstall != null) Button(onClick = onInstall) { Text("Install") }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

/**
 * P6-10: flow view / edit-as-text on the task detail (plan §18 P6-10, UI_SPEC S18). Shows the
 * flow document as text (the canonical diffable artifact — ADR-052), edits it as text, and
 * validates through the same [FlowSources] parser the runtime uses before any write — a bad
 * edit can never reach a run. YAML entries stay read-only until the ADR-052 plug lands (the
 * parser cannot validate what it cannot parse, so editing is refused, honestly).
 */
@Composable
private fun FlowSection(graph: AppGraph, taskId: String, manifest: TaskManifest) {
    var flowText by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var edited by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var savedAt by remember { mutableStateOf<String?>(null) }
    var dir by remember { mutableStateOf<java.io.File?>(null) }

    LaunchedEffect(taskId) {
        val version = graph.data.tasks.latestVersion(taskId) ?: return@LaunchedEffect
        val taskDir = java.io.File(version.bundlePath)
        dir = taskDir
        val file = app.autohost.runtime.flow.FlowSources.resolveFlowFile(taskDir, manifest.runtime.entry)
        fileName = file?.name
        flowText = file?.readText()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Flow document")
        when {
            flowText == null -> Text(
                "No flow document found beside task.json (expected ${manifest.runtime.entry}, " +
                    "flow.json or flow.yaml).",
                style = MaterialTheme.typography.bodyMedium,
            )
            // YAML is read-only until the yamlkt plug is installed (ADR-052): the editor refuses
            // what the parser cannot validate — fail closed, same rule as the runtime.
            fileName?.lowercase()?.let { it.endsWith(".yaml") || it.endsWith(".yml") } == true -> {
                Text("entry: $fileName (read-only until the YAML plug is installed — ADR-052)", style = MaterialTheme.typography.labelSmall)
                Text(
                    flowText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
            else -> {
                Text("entry: $fileName", style = MaterialTheme.typography.labelSmall)
                androidx.compose.material3.OutlinedTextField(
                    value = flowText.orEmpty(),
                    onValueChange = {
                        flowText = it
                        edited = true
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    minLines = 8,
                    maxLines = 24,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                savedAt?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                Button(
                    enabled = edited && dir != null,
                    onClick = {
                        val taskDir = dir ?: return@Button
                        val name = fileName ?: return@Button
                        try {
                            // Parse with the runtime's own parser first: a bad edit stays on screen.
                            app.autohost.runtime.flow.FlowSources.parseFlowText(flowText.orEmpty(), name)
                            java.io.File(taskDir, name).writeText(flowText.orEmpty())
                            edited = false
                            error = null
                            savedAt = "Saved and validated (${java.time.Instant.now()})."
                        } catch (e: Exception) {
                            error = e.message ?: "flow failed to parse"
                        }
                    },
                ) { Text("Validate & Save") }
            }
        }
    }
}

/** Last tab index for a task detail screen that also owns a UI tab (mirrors the tab row above). */
private fun tabsLastIndex(m: TaskManifest): Int {
    val base = if (m.runtime.type == "flow") 5 else 4
    val hasTaskUi = m.ui?.mode == "declarative" || m.ui?.mode == "html"
    return if (hasTaskUi) base else -1
}

/**
 * P7-01/P7-02 (UI_SPEC S16): the task-UI tab. Tier B renders the declarative document through the
 * Compose renderer; Tier C hosts the sandboxed WebView. A document that fails to parse is shown as
 * an honest error, never a blank screen. The panel is height-bounded: it is a LazyColumn hosted in
 * this screen's own LazyColumn (Compose forbids unbounded nested scrolling of the same orientation).
 */
@Composable
private fun UiSection(graph: AppGraph, taskId: String, manifest: TaskManifest, onNavigate: (String) -> Unit) {
    var load by remember(taskId) { mutableStateOf<app.autohost.taskui.TaskUiController.LoadResult?>(null) }
    var taskPath by remember(taskId) { mutableStateOf<String?>(null) }
    LaunchedEffect(taskId) {
        taskPath = graph.data.tasks.latestVersion(taskId)?.bundlePath
        load = graph.taskUi.load(taskId)
    }
    val bindingScope by graph.taskUi.bindingScope(taskId).collectAsState()
    val consoleLines by graph.taskUi.console(taskId).collectAsState()
    val scope = rememberCoroutineScope()
    val result = load
    when {
        result == null -> Text("Loading task UI…", style = MaterialTheme.typography.bodySmall)
        result.error != null -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("The task UI could not be shown", style = MaterialTheme.typography.titleSmall)
                Text(result.error ?: "", style = MaterialTheme.typography.bodySmall)
            }
        }
        manifest.ui?.mode == "html" -> {
            val path = taskPath
            if (path != null) {
                app.autohost.ui.taskui.HtmlUiHost(
                    taskId = taskId,
                    taskPath = path,
                    file = manifest.ui?.file,
                    handlers = graph.uiHandlers,
                    taskUi = graph.taskUi,
                    modifier = Modifier.heightIn(max = 560.dp),
                )
            }
        }
        else -> {
            val doc = result.doc
            if (doc != null) {
                app.autohost.ui.taskui.DeclarativeRenderer(
                    doc = doc,
                    scope = bindingScope,
                    consoleLines = consoleLines,
                    modifier = Modifier.heightIn(max = 560.dp),
                    onAction = { action, inputs ->
                        scope.launch {
                            when (val outcome = graph.taskUi.onAction(taskId, action, inputs)) {
                                is app.autohost.taskui.TaskUiController.ActionOutcome.Navigate -> onNavigate(outcome.route)
                                // Call failures are logged by the controller; the run/console views
                                // carry the detail (v1: no inline banner — noted in ADR-055).
                                else -> Unit
                            }
                        }
                    },
                )
            }
        }
    }
}
