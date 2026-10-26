package app.autohost.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.autohost.AndroidHealthEnv
import app.autohost.AppGraph
import app.autohost.core.engine.health.CheckResult
import app.autohost.core.engine.health.HealthCheck
import app.autohost.core.engine.health.HealthEnv
import app.autohost.core.engine.health.HealthRegistry
import app.autohost.core.designsystem.components.SectionHeader
import app.autohost.core.designsystem.components.StatusChip

/** P2-15: Setup & Health screen — grouped registry rows (plan §13.2), Re-check, Fix buttons. */
@Composable
fun HealthScreen(graph: AppGraph, onOpenFix: (String) -> Unit) {
    val context = LocalContext.current
    var grouped by remember { mutableStateOf<HealthRegistry.Grouped?>(null) }

    fun recheck() {
        val env = AndroidHealthEnv.build(context, graph.a11yConnected(), workerAlive = true, devApiEnabled = graph.devApiEnabled)
        grouped = graph.healthRegistry.runGrouped(env)
    }
    LaunchedEffect(Unit) { recheck() }

    val result = grouped ?: return
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Setup & Health", style = MaterialTheme.typography.headlineSmall)
                OutlinedButton(onClick = { recheck() }) { Text("Re-check") }
            }
        }
        if (result.needsFix.isNotEmpty()) {
            item { SectionHeader("Needs fixing now") }
            items(result.needsFix, key = { it.check.id }) { CheckRow(it, onOpenFix) }
        }
        if (result.autofixable.isNotEmpty()) {
            item { SectionHeader("Can fix automatically") }
            items(result.autofixable, key = { it.check.id }) { CheckRow(it, onOpenFix) }
        }
        if (result.optional.isNotEmpty()) {
            item { SectionHeader("Optional") }
            items(result.optional, key = { it.check.id }) { CheckRow(it, onOpenFix) }
        }
        if (result.healthy.isNotEmpty()) {
            item { SectionHeader("Healthy") }
            items(result.healthy, key = { it.check.id }) { outcome ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(outcome.check.title, style = MaterialTheme.typography.bodyMedium)
                    StatusChip("OK", success = true)
                }
            }
        }
    }
}

@Composable
private fun CheckRow(outcome: HealthCheck.Outcome, onOpenFix: (String) -> Unit) {
    val issue = outcome.result as? CheckResult.Issue ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(outcome.check.title, style = MaterialTheme.typography.titleSmall)
                StatusChip(issue.reasonCode, warning = true)
            }
            Text(
                issue.message + if (outcome.check.description.isNotEmpty()) "\n" + outcome.check.description else "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            issue.fixAction?.let { action ->
                Button(onClick = { onOpenFix(action) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Fix")
                }
            }
        }
    }
}
