package app.autohost.ui2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.autohost.AutoHostApp
import app.autohost.MainActivity
import app.autohost.Routes

/** The Build tab: every way to make a task in one list. What it shows comes from [BuildModel]; this file only draws it. */
@Composable
fun BuildScreen(
    activity: MainActivity,
    onNavigate: (Routes, String?) -> Unit,
    onAddTask: () -> Unit,
) {
    val graph = (activity.application as AutoHostApp).graph
    val labs by graph.labsEnabled.collectAsState()
    LaunchedEffect(Unit) { graph.loadLabsEnabled() }
    val ways = BuildModel.items(
        pythonInBuild = app.autohost.BuildConfig.FLAVOR.contains("py", ignoreCase = true),
        labs = labs,
    )
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Build", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Make something AutoHost can run. Pick a way.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(ways, key = { "build:" + it.id }) { item ->
            val click: (() -> Unit)? = when (val t = item.target) {
                is HubTarget.Go -> ({ onNavigate(t.route, null) })
                HubTarget.AddTask -> onAddTask
                null -> null
            }
            Card(Modifier.fillMaxWidth().let { if (click != null) it.clickable(onClick = click) else it }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(item.line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
