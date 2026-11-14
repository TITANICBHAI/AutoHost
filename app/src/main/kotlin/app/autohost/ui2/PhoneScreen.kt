package app.autohost.ui2

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.autohost.AutoHostApp
import app.autohost.MainActivity
import app.autohost.Routes
import app.autohost.core.designsystem.components.SectionHeader
import app.autohost.core.designsystem.components.StatusChip
import app.autohost.fix.FixHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI v2 "Phone" tab (docs/UI_V2.md §2–4): what works on this phone, what each thing needs, and the optional advanced
 * access with computer-free routes first. Everything shown comes from [PhonePlan], which is unit-tested; this file only draws it.
 */
@Composable
fun PhoneScreen(
    activity: MainActivity,
    onNavigate: (Routes, String?) -> Unit,
    onAddTask: () -> Unit = {},
    onFinish: () -> Unit = {},
) {
    val graph = (activity.application as AutoHostApp).graph
    var live by remember { mutableStateOf(LiveAccess()) }
    var facts by remember { mutableStateOf(SetupFacts()) }
    val onboarded by graph.onboarded.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val service by app.autohost.capabilities.a11y.A11yBridge.service.collectAsState()
    val expanded = remember { mutableStateMapOf<FeatureGroup, Boolean>() }
    val scope = rememberCoroutineScope()

    // Re-read when the person comes back from a system settings screen, and when the accessibility service connects.
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick += 1 }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(tick, service) {
        graph.loadOnboarded()
        withContext(Dispatchers.IO) {
            live = LiveAccessReader.read(activity, graph)
            facts = SetupFactsReader.read(graph)
        }
    }
    val started = remember(live, facts) { GetStarted.steps(live, facts) }
    val plan = remember(live) { PhonePlan.build(live, android.os.Build.VERSION.SDK_INT) }

    fun press(action: String?) {
        if (action == null) return
        when (action) {
            "OPEN_AI_SETTINGS" -> { onNavigate(Routes.AI_SETTINGS, null); return }
            "ADD_TASK" -> { onAddTask(); return }
        }
        if (action in FixHub.IN_APP_ROUTES) onNavigate(Routes.SETTINGS, null) else FixHub.launch(activity, action)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(if (onboarded) "Setup" else "Welcome to AutoHost", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (onboarded) "${plan.readyCount} of ${plan.totalCount} abilities work now. No computer needed for any of these."
                else "AutoHost runs tasks on this phone: it taps, types, reads and schedules. Nothing here needs a computer. " +
                    "Only the first step is required; the rest can wait.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            val (done, total) = GetStarted.counts(started)
            SectionHeader("Get started ($done of $total)")
        }
        items(started, key = { ListKeys.step(it.id) }) { step ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(step.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        StatusChip(
                            if (step.done) "Done" else when (step.level) {
                                Level.REQUIRED -> "Required"
                                Level.RECOMMENDED -> "Recommended"
                                Level.OPTIONAL -> "Optional"
                            },
                            success = step.done,
                            warning = !step.done && step.level == Level.REQUIRED,
                            neutral = !step.done && step.level != Level.REQUIRED,
                        )
                    }
                    Text(step.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!step.done) {
                        if (step.level == Level.REQUIRED || step.level == Level.RECOMMENDED) {
                            Button(onClick = { press(step.action) }) { Text(step.button) }
                        } else {
                            OutlinedButton(onClick = { press(step.action) }) { Text(step.button) }
                        }
                    }
                }
            }
        }
        if (!onboarded) {
            item {
                Button(
                    onClick = { scope.launch { graph.setOnboarded(true); onFinish() } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (GetStarted.requiredLeft(started) > 0) "Skip for now" else "Finish and go to my tasks") }
            }
        }
        item { SectionHeader("What your tasks can do") }
        items(plan.groups, key = { ListKeys.group(it.group) }) { card ->
            val open = expanded[card.group] == true
            Card(Modifier.fillMaxWidth().clickable { expanded[card.group] = !open }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(card.group.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        StatusChip("${card.ready}/${card.total}", success = card.allGood, warning = !card.allGood)
                    }
                    Text(card.group.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (open) {
                        card.rows.forEach { row -> FeatureLine(row, onFix = { press(row.fixAction) }, onAdvanced = { expanded[FeatureGroup.ADVANCED] = true }) }
                    }
                }
            }
        }
        item { SectionHeader("Advanced access (optional)") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (live.advancedLive) "Advanced access is working."
                        else "Everything above works without this. It only unlocks the advanced abilities (shell, install apps, radios, display, logs, mock location, system settings).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    plan.routes.forEach { r -> RouteLine(r) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch { graph.refreshShellTransports(); tick += 1 }
                        }) { Text("Check now") }
                        TextButton(onClick = { onNavigate(Routes.SETTINGS, null) }) { Text("More in Settings") }
                    }
                }
            }
        }
        item { SectionHeader("Keep it running") }
        item { AlwaysOnTips(activity) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onNavigate(Routes.HEALTH, null) }) { Text("Health check") }
                OutlinedButton(onClick = { onNavigate(Routes.SETTINGS, null) }) { Text("Settings") }
            }
        }
    }
}

@Composable
private fun AlwaysOnTips(activity: MainActivity) {
    app.autohost.ui.AlwaysOnChecklist(activity)
}

@Composable
private fun FeatureLine(row: FeatureRow, onFix: () -> Unit, onAdvanced: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(row.capability.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            val v = row.verdict
            StatusChip(
                when (v) {
                    Verdict.READY -> "Ready"
                    Verdict.ASKS_ON_USE -> "Asks once"
                    Verdict.NEEDS_SETUP -> "Switch"
                    Verdict.NEEDS_SWITCH -> "Off"
                    Verdict.NEEDS_ADVANCED -> "Optional"
                },
                success = v == Verdict.READY,
                neutral = v == Verdict.ASKS_ON_USE || v == Verdict.NEEDS_ADVANCED,
                warning = v == Verdict.NEEDS_SETUP || v == Verdict.NEEDS_SWITCH,
            )
        }
        Text(row.verdict.line(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (row.fixAction != null) TextButton(onClick = onFix) { Text("Fix") }
        else if (row.verdict == Verdict.NEEDS_ADVANCED) TextButton(onClick = onAdvanced) { Text("How to get it") }
    }
}

@Composable
private fun RouteLine(route: RouteInfo) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(route.route.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            StatusChip(
                when {
                    !route.implemented -> "Not available yet"
                    route.needsComputer -> "Needs a computer"
                    else -> "No computer"
                },
                success = route.implemented && !route.needsComputer,
                warning = route.implemented && route.needsComputer,
                neutral = !route.implemented,
            )
        }
        Text(route.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
