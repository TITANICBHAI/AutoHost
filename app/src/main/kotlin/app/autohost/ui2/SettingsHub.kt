package app.autohost.ui2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.autohost.AutoHostApp
import app.autohost.MainActivity
import app.autohost.Routes
import app.autohost.core.designsystem.components.SectionHeader
import kotlinx.coroutines.launch

/** One row of the Settings hub: a title, one plain line, and where it leads (null = information only). */
data class HubRow(val title: String, val line: String, val target: HubTarget?)

sealed interface HubTarget {
    data class Go(val route: Routes) : HubTarget
    data object AddTask : HubTarget
}

data class HubSection(val title: String, val rows: List<HubRow>)

/**
 * Settings hub content (docs/UI_V2.md §2): grouped by what the person wants to do, so nothing the app can do is only
 * reachable through a developer screen. Pure and unit-tested: every route named here must exist and each section is non-empty.
 */
object SettingsHubModel {

    fun sections(pythonInBuild: Boolean, devFolder: String?): List<HubSection> = listOf(
        HubSection(
            "AI and agent",
            listOf(
                HubRow("AI model (your own key)", "Add a provider, model and API key. Nothing is sent until you turn it on.", HubTarget.Go(Routes.AI_SETTINGS)),
                HubRow("Ask the agent", "Describe a job in words; the agent does it step by step and you can stop it.", HubTarget.Go(Routes.ASK)),
                HubRow("Runs and history", "Every run, including agent runs: what it did and how it ended.", HubTarget.Go(Routes.RUNS_HISTORY)),
            ),
        ),
        HubSection(
            "Tasks, scripts and folders",
            listOf(
                HubRow("Add a task", "From a template, a quick macro, or a .zip task file on your phone.", HubTarget.AddTask),
                HubRow("Quick macro", "When something happens, run a few steps. No code.", HubTarget.Go(Routes.MACRO)),
                HubRow(
                    "Python scripts",
                    if (pythonInBuild) "This build runs Python tasks. Add one with a .zip task file."
                    else "This build has no Python. Flows, macros and JavaScript tasks still work.",
                    null,
                ),
                HubRow(
                    "Task folder",
                    if (devFolder != null) "$devFolder — newer Android hides this from file managers, so use a .zip task file instead."
                    else "Not available on this phone.",
                    null,
                ),
            ),
        ),
        HubSection(
            "Automatic",
            listOf(
                HubRow("Schedules and triggers", "Run tasks at times or when something happens on the phone.", HubTarget.Go(Routes.SCHEDULES)),
                HubRow("Tiles, shortcuts and bubble", "Start tasks from quick settings, the home screen or a floating bubble.", HubTarget.Go(Routes.SURFACES)),
            ),
        ),
        HubSection(
            "Safety and system",
            listOf(
                HubRow("Health check", "What is working, what is not, and how to fix it.", HubTarget.Go(Routes.HEALTH)),
                HubRow("Security, backup and developer", "Risky-feature switches, backup and restore, packs, webhooks, shell access, developer tools.", HubTarget.Go(Routes.SETTINGS)),
            ),
        ),
    )
}

@Composable
fun SettingsHub(activity: MainActivity, onNavigate: (Routes, String?) -> Unit, onAddTask: () -> Unit) {
    val graph = (activity.application as AutoHostApp).graph
    val labs by graph.labsEnabled.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { graph.loadLabsEnabled() }
    val sections = SettingsHubModel.sections(
        pythonInBuild = app.autohost.BuildConfig.FLAVOR.contains("py", ignoreCase = true),
        devFolder = activity.getExternalFilesDir("dev")?.absolutePath,
    )

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }
        sections.forEach { section ->
            item { SectionHeader(section.title) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        section.rows.forEachIndexed { i, row ->
                            if (i > 0) HorizontalDivider()
                            val click = when (val t = row.target) {
                                is HubTarget.Go -> ({ onNavigate(t.route, null) })
                                HubTarget.AddTask -> onAddTask
                                null -> null
                            }
                            Column(
                                Modifier.fillMaxWidth()
                                    .let { if (click != null) it.clickable(onClick = click) else it }
                                    .padding(12.dp),
                            ) {
                                Text(row.title, style = MaterialTheme.typography.titleSmall)
                                Text(row.line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Labs: Record and Studio", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Unfinished tools. Record a flow by tapping through an app; write tasks in the Studio.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = labs, onCheckedChange = { on -> scope.launch { graph.setLabsEnabled(on) } })
                }
            }
        }
    }
}
