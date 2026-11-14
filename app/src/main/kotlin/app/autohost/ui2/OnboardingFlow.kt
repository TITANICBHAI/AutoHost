package app.autohost.ui2

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.autohost.AutoHostApp
import app.autohost.MainActivity
import app.autohost.Routes
import app.autohost.core.designsystem.components.StatusChip
import app.autohost.fix.FixHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * First run, as its own full-screen steps (owner feedback 2026-11-14): welcome, one page per step, a finish page.
 * It re-reads the phone every time the person comes back from a system screen, so a step turns to "Done" by itself.
 * Every page can be skipped; [onFinished] is called for both finishing and skipping and must record that the flow was seen.
 */
@Composable
fun OnboardingFlow(
    activity: MainActivity,
    onNavigate: (Routes, String?) -> Unit,
    onAddTask: () -> Unit,
    onFinished: () -> Unit,
) {
    val graph = (activity.application as AutoHostApp).graph
    var live by remember { mutableStateOf(LiveAccess()) }
    var facts by remember { mutableStateOf(SetupFacts()) }
    var tick by remember { mutableIntStateOf(0) }
    var index by remember { mutableIntStateOf(OnboardingMemory.page) }
    val service by app.autohost.capabilities.a11y.A11yBridge.service.collectAsState()

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick += 1 }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(tick, service) {
        withContext(Dispatchers.IO) {
            live = LiveAccessReader.read(activity, graph)
            facts = SetupFactsReader.read(graph)
        }
    }

    val steps = remember(live, facts) { GetStarted.steps(live, facts) }
    val pages = remember(steps) { OnboardingPlan.pages(steps) }
    val current = OnboardingPlan.clamp(index, pages)

    fun go(to: Int) {
        index = OnboardingPlan.clamp(to, pages)
        OnboardingMemory.page = index
    }

    fun finish() {
        OnboardingMemory.page = 0
        onFinished()
    }

    fun press(action: String) {
        when (action) {
            "OPEN_AI_SETTINGS" -> { onNavigate(Routes.AI_SETTINGS, null); return }
            "ADD_TASK" -> { onAddTask(); return }
        }
        if (action in FixHub.IN_APP_ROUTES) onNavigate(Routes.SETTINGS, null) else FixHub.launch(activity, action)
    }

    BackHandler(enabled = current > 0) { go(current - 1) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    pages.indices.forEach { i ->
                        Box(
                            Modifier
                                .width(if (i == current) 22.dp else 8.dp)
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(if (i <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
                if (pages[current] !is OnboardingPlan.Page.Finish) TextButton(onClick = { finish() }) { Text("Skip all") }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (val page = pages[current]) {
                        OnboardingPlan.Page.Welcome -> {
                            BrandMark(88.dp)
                            Spacer(Modifier.height(4.dp))
                            Text("Welcome to AutoHost", style = MaterialTheme.typography.headlineMedium)
                            Text(
                                "AutoHost does jobs on this phone for you: it opens apps, taps, types, reads the screen and runs on a schedule. " +
                                    "You build a job as a macro, a Python script or by asking an AI agent. Nothing here needs a computer.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("Next: a few quick steps. Only the first one is required, and you can skip any of them.", style = MaterialTheme.typography.bodyMedium)
                        }
                        is OnboardingPlan.Page.Step -> {
                            val step = page.step
                            Text(OnboardingPlan.label(current, pages), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(step.title, style = MaterialTheme.typography.headlineSmall)
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
                            Text(step.why, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!step.done) {
                                Button(onClick = { press(step.action) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(step.button) }
                            }
                        }
                        OnboardingPlan.Page.Finish -> {
                            BrandMark(64.dp)
                            Text(
                                if (GetStarted.requiredLeft(steps) == 0) "You are ready" else "Almost there",
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            Text(OnboardingPlan.summary(steps), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            when (val page = pages[current]) {
                OnboardingPlan.Page.Welcome -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { go(1) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Set up my phone") }
                    TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Skip for now") }
                }
                is OnboardingPlan.Page.Step -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { go(current - 1) }, modifier = Modifier.height(48.dp)) { Text("Back") }
                    Button(onClick = { go(current + 1) }, modifier = Modifier.weight(1f).height(48.dp)) { Text(OnboardingPlan.nextLabel(page.step)) }
                }
                OnboardingPlan.Page.Finish -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { go(current - 1) }, modifier = Modifier.height(48.dp)) { Text("Back") }
                    Button(onClick = { finish() }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Open AutoHost") }
                }
            }
        }
    }
}
