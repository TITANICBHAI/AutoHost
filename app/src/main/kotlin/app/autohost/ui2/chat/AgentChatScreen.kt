package app.autohost.ui2.chat

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.autohost.AppGraph
import app.autohost.agent.AgentRunStarter
import app.autohost.agent.TaskValueNames
import app.autohost.core.common.AhResult
import app.autohost.core.engine.orchestrator.RunRecord
import app.autohost.core.llm.ProviderProfile
import app.autohost.ui2.ReasonCopy
import app.autohost.ui2.pickers.AppChoice
import app.autohost.ui2.pickers.AppPickerButton
import app.autohost.ui2.pickers.PickerModel
import app.autohost.ui2.pickers.TaskChoice
import app.autohost.ui2.pickers.TaskPickerButton
import app.autohost.ui2.pickers.rememberInstalledApps
import app.autohost.ui2.pickers.rememberTaskChoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private const val START_FAILED = "AutoHost could not start the agent. Check the AI settings and try again."

private val EXAMPLES = listOf(
    "Open Gemini and ask it for a short video idea",
    "Open my notes app and add a note called Shopping",
    "Open Settings and turn on dark mode",
)

/**
 * The Agent tab as a chat (docs/INTEGRATION_V3.md §5, INT-F): the person types what they want, the supervised agent
 * run starts (the same `AgentRunStarter` the Ask form used, with its preflight), and its steps appear as plain lines
 * under the goal. Stop is one tap. The run store is the only source of what happened; the saved conversation only
 * remembers which goal started which run, and the apps and model last used.
 *
 * Not in this slice (docs/INTEGRATION_V3.md): answering the agent's questions inside the chat (they still arrive as a
 * notification), screenshots to the model, a follow-up that carries the earlier answer into the next goal.
 */
@Composable
fun AgentChatHost(
    graph: AppGraph,
    onOpenRun: (String) -> Unit,
    onOpenAiSettings: () -> Unit,
    onFix: (FixTarget) -> Unit,
) {
    val store = remember(graph) {
        ChatTranscriptStore(graph.application.getSharedPreferences(ChatTranscriptStore.PREFS_NAME, Context.MODE_PRIVATE))
    }
    var transcript by remember(store) { mutableStateOf(store.load()) }
    var goal by remember { mutableStateOf("") }
    var packages by remember(store) { mutableStateOf(transcript.packages) }
    var profileId by remember(store) { mutableStateOf(transcript.profileId) }
    var taskIds by remember(store) { mutableStateOf(transcript.tasks) }
    var profiles by remember { mutableStateOf(emptyList<ProviderProfile>()) }
    var aiEnabled by remember { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<Refusal?>(null) }
    var starting by remember { mutableStateOf(false) }
    var setupOpen by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val apps = rememberInstalledApps(graph)
    val taskChoices = rememberTaskChoices(graph)
    val records by graph.runStore.runs.collectAsState()

    LaunchedEffect(graph.aiFacts) {
        aiEnabled = graph.aiFacts.enabled
        profiles = graph.aiProfileStore.load()
    }
    val installed = remember(apps) { apps.map { it.pkg }.toSet() }
    LaunchedEffect(installed) {
        if (installed.isNotEmpty()) packages = AgentChat.stillInstalled(packages, installed)
    }
    val appNames = remember(apps) { apps.associate { it.pkg to it.label } }
    // a saved task that was deleted since it was ticked drops out; while the list is still empty nothing is dropped
    val existingTasks = remember(taskChoices) { taskChoices.map { it.taskId }.toSet() }
    LaunchedEffect(existingTasks) {
        if (existingTasks.isNotEmpty()) taskIds = AgentChat.stillSaved(taskIds, existingTasks)
    }

    // the runs this chat started that are still going; their events nudge the lines to redraw
    val activeIds = transcript.entries.map { it.runId }.filter { id -> records.firstOrNull { it.runId == id }?.active == true }
    LaunchedEffect(activeIds) {
        if (activeIds.isNotEmpty()) activeIds.map { graph.runStore.events(it) }.merge().collect { tick += 1 }
    }
    val rows = remember(transcript, records, tick, appNames) {
        AgentChat.rows(
            entries = transcript.entries,
            recordOf = { id -> records.firstOrNull { it.runId == id } },
            eventsOf = { id -> graph.runStore.snapshot(id) },
            appNames = appNames,
        )
    }

    val gate = AgentChat.gate(goal, packages, aiEnabled, profiles)
    val chosenProfile = AgentChat.profileFor(profileId, profiles)
    val runningId = activeIds.lastOrNull()

    val send: () -> Unit = send@{
        if (gate != AgentChat.Gate.Ready || starting || runningId != null) return@send
        val text = goal.trim()
        val chosenApps = packages
        val chosenTasks = taskIds
        val model = chosenProfile
        // only saved tasks that exist right now are handed over; the agent is never told about a deleted one
        val ticked = AgentChat.allowedTasks(chosenTasks, taskChoices)
        val devTasks = graph.devTasks.tasks.value
        scope.launch {
            starting = true
            refusal = null
            val started: AhResult<RunRecord>? = try {
                // each macro's value names are read from its own file: off the main thread, and not at all when no task is ticked
                val allowed: List<AgentRunStarter.AllowedTask> =
                    if (ticked.isEmpty()) emptyList() else withContext(graph.dispatchers.io) { TaskValueNames.allowed(ticked, devTasks) }
                graph.agentRunStarter.start(text, chosenApps, model, JsonObject(emptyMap()), allowed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            when (started) {
                is AhResult.Ok -> {
                    transcript = transcript
                        .withEntry(ChatEntry(started.value.runId, text, started.value.startedAtMs))
                        .withSetup(chosenApps, model, chosenTasks)
                    store.save(transcript)
                    goal = ""
                }
                is AhResult.Err -> refusal = Refusal(
                    ReasonCopy.text(started.error.reason) ?: START_FAILED,
                    AgentChat.fixFor(started.error.reason),
                )
                null -> refusal = Refusal(START_FAILED, null)
            }
            starting = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Ask the agent", style = MaterialTheme.typography.titleLarge)
            if (transcript.entries.isNotEmpty()) {
                TextButton(
                    onClick = {
                        transcript = transcript.cleared()
                        store.save(transcript)
                    },
                    enabled = runningId == null,
                ) { Text("Clear chat") }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                EmptyChat(onExample = { goal = it })
            } else {
                Conversation(rows, onOpenRun, onFix)
            }
        }
        SetupStrip(
            gate = gate,
            apps = apps,
            packages = packages,
            taskChoices = taskChoices,
            taskIds = taskIds,
            profiles = profiles,
            chosenProfile = chosenProfile,
            open = setupOpen,
            ownPackage = graph.application.packageName,
            onToggle = { setupOpen = !setupOpen },
            onAddApp = { pkg -> if (pkg.isNotBlank() && pkg !in packages) packages = packages + pkg },
            onRemoveApp = { pkg -> packages = packages - pkg },
            onAddTask = { id -> if (id.isNotBlank() && id !in taskIds && taskIds.size < AgentChat.MAX_TASKS) taskIds = taskIds + id },
            onRemoveTask = { id -> taskIds = taskIds - id },
            onPickProfile = { profileId = it },
            onOpenAiSettings = onOpenAiSettings,
        )
        Composer(
            goal = goal,
            onGoal = { goal = it.take(MAX_GOAL_CHARS) },
            canSend = gate == AgentChat.Gate.Ready && !starting,
            starting = starting,
            running = runningId != null,
            refusal = refusal,
            onFix = onFix,
            onSend = send,
            onStop = { runningId?.let { graph.orchestrator.cancel(it) } },
        )
    }
}

/** The longest goal the chat sends. */
private const val MAX_GOAL_CHARS = 2_000

/** Lets a journey test find the "could not start" line whatever its words are. */
const val REFUSAL_TAG = "chat-refusal"

/** Lets a journey test find the button under the "could not start" line. */
const val REFUSAL_FIX_TAG = "chat-refusal-fix"

/** Why the last message could not start a run, in the catalog's words, and the button that would fix it (if any). */
private data class Refusal(val text: String, val fix: ChatFix?)

@Composable
private fun EmptyChat(onExample: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tell the agent what to do on your phone.", style = MaterialTheme.typography.titleMedium)
        Text(
            "It looks at the screen and taps for you, one step at a time. You choose the apps it may use, " +
                "and anything risky asks you first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Try one of these:", style = MaterialTheme.typography.labelLarge)
        for (example in EXAMPLES) {
            OutlinedButton(onClick = { onExample(example) }, modifier = Modifier.fillMaxWidth()) { Text(example) }
        }
    }
}

@Composable
private fun Conversation(rows: List<ChatRow>, onOpenRun: (String) -> Unit, onFix: (FixTarget) -> Unit) {
    val listState = rememberLazyListState()
    // follow the newest line while the person is at the bottom; stop following once they scroll up to read
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) follow = !listState.canScrollForward
    }
    LaunchedEffect(rows.size, rows.lastOrNull()?.key) {
        if (follow && rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(rows, key = { it.key }) { row -> ChatRowView(row, onOpenRun, onFix) }
    }
}

@Composable
private fun ChatRowView(row: ChatRow, onOpenRun: (String) -> Unit, onFix: (FixTarget) -> Unit) {
    when (row) {
        is ChatRow.Goal -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 300.dp)) {
                Text(
                    row.text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        is ChatRow.Line -> when (val line = row.line) {
            is ChatLine.Thought -> Text(
                line.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is ChatLine.Step -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stepMark(line.ok, row.runFinished), style = MaterialTheme.typography.bodyMedium)
                Text(line.phrase, style = MaterialTheme.typography.bodyMedium)
            }
            is ChatLine.Notice -> Text(
                line.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is ChatLine.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(line.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                if (line.waitingForYou) TextButton(onClick = { onOpenRun(row.runId) }) { Text("Open details") }
            }
            is ChatLine.Outcome -> OutcomeCard(line, onOpen = { onOpenRun(row.runId) }, onFix = onFix)
        }
    }
}

/** A check, a cross, a dot for an action that never got a result, or an ellipsis while its result is still due. */
private fun stepMark(ok: Boolean?, runFinished: Boolean): String = when {
    ok == true -> "✓"
    ok == false -> "✗"
    runFinished -> "•"
    else -> "…"
}

@Composable
private fun OutcomeCard(line: ChatLine.Outcome, onOpen: () -> Unit, onFix: (FixTarget) -> Unit) {
    val container = when (line.tone) {
        ChatLine.Tone.Good -> MaterialTheme.colorScheme.primaryContainer
        ChatLine.Tone.Bad -> MaterialTheme.colorScheme.errorContainer
        ChatLine.Tone.Neutral -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(line.headline, style = MaterialTheme.typography.titleSmall)
            line.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            line.fix?.let { fix -> Button(onClick = { onFix(fix.target) }) { Text(fix.label) } }
            TextButton(onClick = onOpen) { Text("Open details") }
        }
    }
}

/**
 * What the agent needs before it can start: a model (and AI switched on), then the apps it may use, and, once the person
 * has saved tasks, which of those it may start (INT-E2). Collapsed to one line once the model and apps are set; the person
 * can change any of them from there.
 */
@Composable
private fun SetupStrip(
    gate: AgentChat.Gate,
    apps: List<AppChoice>,
    packages: List<String>,
    taskChoices: List<TaskChoice>,
    taskIds: List<String>,
    profiles: List<ProviderProfile>,
    chosenProfile: String?,
    open: Boolean,
    ownPackage: String,
    onToggle: () -> Unit,
    onAddApp: (String) -> Unit,
    onRemoveApp: (String) -> Unit,
    onAddTask: (String) -> Unit,
    onRemoveTask: (String) -> Unit,
    onPickProfile: (String) -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    if (gate == AgentChat.Gate.NeedsModel || gate == AgentChat.Gate.AiOff) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentChat.hint(gate)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = onOpenAiSettings) {
                Text(if (gate == AgentChat.Gate.NeedsModel) "Set up an AI model" else "Open AI settings")
            }
        }
        return
    }
    val forced = gate == AgentChat.Gate.NeedsApps
    val showPanel = open || forced
    val modelName = profiles.firstOrNull { it.id == chosenProfile }?.label
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
                AgentChat.summaryLine(
                    appNames = packages.map { PickerModel.appLabel(apps, it) ?: "an app" },
                    taskNames = taskIds.map { PickerModel.taskName(taskChoices, it) ?: "a task" },
                    modelName = modelName,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (!forced) TextButton(onClick = onToggle) { Text(if (open) "Done" else "Change") }
        }
        if (showPanel) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AgentChat.hint(gate)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text("Apps the agent may use", style = MaterialTheme.typography.labelLarge)
                if (packages.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (pkg in packages) {
                            FilterChip(
                                selected = true,
                                onClick = { onRemoveApp(pkg) },
                                label = { Text("${PickerModel.appLabel(apps, pkg) ?: "an app"}  ✕") },
                            )
                        }
                    }
                }
                AppPickerButton(label = "", pkg = "", apps = apps, hide = ownPackage, blankText = "+ Add an app", onPick = onAddApp)
                // only once there is a saved task to offer (or one already ticked): a first-time person sees no empty section
                if (taskChoices.isNotEmpty() || taskIds.isNotEmpty()) {
                    Text("Saved tasks the agent may start", style = MaterialTheme.typography.labelLarge)
                    if (taskIds.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (id in taskIds) {
                                FilterChip(
                                    selected = true,
                                    onClick = { onRemoveTask(id) },
                                    label = { Text("${PickerModel.taskName(taskChoices, id) ?: "a task"}  ✕") },
                                )
                            }
                        }
                    }
                    val notYetAllowed = taskChoices.filter { it.taskId !in taskIds }
                    if (taskIds.size >= AgentChat.MAX_TASKS) {
                        Text("That is the most saved tasks one message can allow.", style = MaterialTheme.typography.bodySmall)
                    } else if (notYetAllowed.isNotEmpty()) {
                        TaskPickerButton(label = "", taskId = "", tasks = notYetAllowed, blankText = "+ Add a task", onPick = onAddTask)
                    }
                }
                if (profiles.size > 1) {
                    Text("AI model", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (profile in profiles) {
                            FilterChip(
                                selected = profile.id == chosenProfile,
                                onClick = { onPickProfile(profile.id) },
                                label = { Text(profile.label) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    goal: String,
    onGoal: (String) -> Unit,
    canSend: Boolean,
    starting: Boolean,
    running: Boolean,
    refusal: Refusal?,
    onFix: (FixTarget) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        refusal?.let { said ->
            Text(
                said.text,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(REFUSAL_TAG),
            )
            said.fix?.let { fix ->
                Button(onClick = { onFix(fix.target) }, modifier = Modifier.testTag(REFUSAL_FIX_TAG)) { Text(fix.label) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = goal,
                onValueChange = onGoal,
                label = { Text("Tell the agent what to do") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            if (running) {
                Button(onClick = onStop) { Text("Stop") }
            } else {
                Button(onClick = onSend, enabled = canSend) { Text(if (starting) "Starting…" else "Send") }
            }
        }
    }
}
