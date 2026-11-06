package app.autohost.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.autohost.ai.AiSettingsState
import app.autohost.ai.AskSheetState
import app.autohost.ai.AppProfileStore
import kotlinx.coroutines.launch

/**
 * P14-07 (S33, UI_SPEC §10): the Ask bar/sheet — the goal (≤ 2 000 chars), EXPLICIT target apps
 * (required ≥ 1), the provider from the AI settings, optional tighter budgets, SUPERVISED by
 * default. The Start button refuses (before the preflight too) without the master switch. All
 * rules live in [AskSheetState] (tested).
 */
@Composable
fun AskSheetScreen(
    state: AskSheetState,
    model: AskSheetState.Model,
    onBack: () -> Unit,
    onOpenRun: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var packageInput by remember { mutableStateOf("") }
    var turnsInput by remember { mutableStateOf("") }
    var wallInput by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Ask the agent", style = MaterialTheme.typography.titleLarge)
        Text(
            "State a goal; the agent drives the phone one step at a time. It is supervised: risky " +
                "actions ask you, and only YOUR confirmation ends a run as succeeded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = model.goal,
            onValueChange = { state.setGoal(it) },
            label = { Text("goal (≤ 2000 chars)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            supportingText = { Text("${model.goal.length}/2000") },
        )

        Text("Target apps (required — the agent cannot leave these)", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            model.selectedPackages.sorted().forEach { pkg ->
                FilterChip(selected = true, onClick = { state.togglePackage(pkg) }, label = { Text(pkg) })
            }
        }
        // R4: the app picker — launchable installed apps, filtered as you type. The list is the
        // USER'S own phone content (the privacy rule binds the MODEL, never this screen).
        OutlinedTextField(
            value = model.appQuery,
            onValueChange = { state.setAppQuery(it) },
            label = { Text("search installed apps") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        val visible = state.visibleApps().take(8)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            visible.forEach { app ->
                FilterChip(
                    selected = app.pkg in model.selectedPackages,
                    onClick = { state.togglePackage(app.pkg) },
                    label = { Text(app.label) },
                )
            }
            if (model.availableApps.isEmpty()) {
                Text(
                    "no launchable apps found — type a package name below",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // the manual fallback stays: a target app need not be launchable
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = packageInput,
                onValueChange = { packageInput = it },
                label = { Text("package name (manual)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(
                onClick = {
                    state.togglePackage(packageInput.trim())
                    packageInput = ""
                },
                enabled = packageInput.isNotBlank(),
            ) { Text("add") }
        }

        Text("Provider", style = MaterialTheme.typography.titleMedium)
        if (model.profiles.isEmpty()) {
            Text("no profiles — add one in Settings → AI features", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            model.profiles.forEach { profile ->
                FilterChip(
                    selected = model.selectedProfileId == profile.id,
                    onClick = { state.selectProfile(profile.id) },
                    label = { Text(profile.label) },
                )
            }
        }

        Text("Budgets (optional — may only tighten)", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = turnsInput,
                onValueChange = { turnsInput = it; state.setMaxTurns(it.toIntOrNull()) },
                label = { Text("max turns (≤ ${AskSheetState.HOST_MAX_TURNS})") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = wallInput,
                onValueChange = { wallInput = it; state.setMaxWallClockS(it.toLongOrNull()) },
                label = { Text("wall clock s (≤ ${AskSheetState.HOST_MAX_WALL_CLOCK_S})") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }

        model.refusal?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = { scope.launch { state.start() } },
            enabled = state.canStart && !model.starting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (model.starting) "starting…" else "start (supervised)")
        }
        model.startedRunId?.let { runId ->
            OutlinedButton(onClick = { onOpenRun(runId) }, modifier = Modifier.fillMaxWidth()) {
                Text("open the run")
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("back") }
    }
}

/** The wiring host: the state over the real [AgentRunStarter]; the AI facts hydrate the gate. */
@Composable
fun AskSheetHost(graph: app.autohost.AppGraph, onBack: () -> Unit, onOpenRun: (String) -> Unit) {
    val state = remember { AskSheetState(AskSheetState.Starter { goal, packages, profileId, override ->
        graph.agentRunStarter.start(goal, packages, profileId, override)
    }) }
    var model by remember { mutableStateOf(state.model) }
    androidx.compose.runtime.LaunchedEffect(graph.aiFacts) {
        state.hydrate(
            aiEnabled = graph.aiFacts.enabled,
            profiles = graph.aiProfileStore.load(),
        )
        // R4: the picker's app list — launchable installed apps, the user's own phone content
        val pm = graph.application.packageManager
        val launchables = runCatching {
            pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER),
                0,
            )
        }.getOrDefault(emptyList()).map { info ->
            AskSheetState.AppEntry(
                pkg = info.activityInfo.applicationInfo.packageName,
                label = info.loadLabel(pm).toString(),
            )
        }.filter { it.pkg != graph.application.packageName }
        state.hydrateApps(launchables.distinctBy { it.pkg })
        model = state.model
    }
    androidx.compose.runtime.LaunchedEffect(state.model) { model = state.model }
    AskSheetScreen(state = state, model = model, onBack = onBack, onOpenRun = onOpenRun)
}
