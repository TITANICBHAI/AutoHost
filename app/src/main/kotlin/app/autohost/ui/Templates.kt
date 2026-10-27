package app.autohost.ui

import android.content.Context
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.autohost.AppGraph
import app.autohost.core.engine.tasks.ImportPipeline
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * P2-20: New-from-template (plan §8.3, docs/UI_SPEC S02). The repo samples ship as APK assets
 * (`app/src/main/assets/templates/<id>`) and install through the one import pipeline — a template is
 * just a `Source.Sample` the user did not have to find first. Nothing here bypasses review: the same
 * report → install → Room record path as every other source runs, so grants and compat checks apply.
 */
data class TemplateDef(val id: String, val title: String, val blurb: String)

object TemplateCatalog {
    val ALL = listOf(
        TemplateDef(
            "hello",
            "Hello",
            "Logs progress, counts runs, needs no capabilities. Proves the runtime and run lifecycle.",
        ),
        TemplateDef(
            "press-button",
            "Press Button",
            "Opens an app, waits for a labelled button and presses it. Schedule + profiles make it recurring (archetype A).",
        ),
        TemplateDef(
            "settings-reader",
            "Settings Reader",
            "Opens Android Settings, reads the About screen, screenshots if possible. Self-test for the UI providers.",
        ),
        TemplateDef(
            "http-check",
            "HTTP Check",
            "Background utility: checks a URL, notifies on failure, asks for an earlier retry. Runs with the screen off.",
        ),
    )
}

/** Copies an asset template into the cache so `ImportPipeline` (which works on real directories) can review it. */
object TemplateInstaller {
    fun materialize(context: Context, id: String): File {
        val out = File(File(context.cacheDir, "templates"), id)
        out.deleteRecursively()
        out.mkdirs()
        copyDir(context, "templates/$id", out)
        if (!File(out, "task.json").isFile) {
            out.deleteRecursively()
            throw IllegalStateException("template '$id' has no task.json")
        }
        return out
    }

    private fun copyDir(context: Context, assetPath: String, out: File) {
        out.mkdirs()
        for (name in context.assets.list(assetPath).orEmpty()) {
            val child = "$assetPath/$name"
            val target = File(out, name)
            val nested = context.assets.list(child)
            if (!nested.isNullOrEmpty()) {
                copyDir(context, child, target)
            } else {
                context.assets.open(child).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}

/**
 * P2-20: the New-from-template screen. Create stages the template through `review()`, installs only when
 * the report is clean, records the version in Room (source = "template") and opens the new task's detail.
 */
@Composable
fun NewFromTemplateScreen(
    graph: AppGraph,
    onOpenTask: (String) -> Unit,
    onBack: () -> Unit,
) {
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LazyColumn(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("New from template", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Start from a working sample — the same import review runs, so grants and compat apply.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = onBack) { Text("‹ Back") }
            }
        }
        if (error != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Template rejected", style = MaterialTheme.typography.titleSmall)
                        Text(error ?: "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        items(TemplateCatalog.ALL, key = { it.id }) { t ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium)
                    Text(t.blurb, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                    Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { create(graph, t, scope, onBusy = { busy = it }, onError = { error = it }, onOpenTask = onOpenTask) }, enabled = busy == null) {
                            Text(if (busy == t.id) "Creating…" else "Create")
                        }
                    }
                }
            }
        }
    }
}

private fun create(
    graph: AppGraph,
    template: TemplateDef,
    scope: kotlinx.coroutines.CoroutineScope,
    onBusy: (String?) -> Unit,
    onError: (String?) -> Unit,
    onOpenTask: (String) -> Unit,
) {
    onBusy(template.id)
    onError(null)
    scope.launch(Dispatchers.IO) {
        try {
            val dir = TemplateInstaller.materialize(graph.appContext, template.id)
            val review = graph.importPipeline.review(ImportPipeline.Source.Sample(dir))
            val staged = review.staged
            if (!review.canInstall || staged == null) {
                val detail = review.report.errors.joinToString("; ") { it.describe() }
                    .ifEmpty { review.report.warnings.joinToString("; ") { w -> w.describe() } }
                dir.deleteRecursively()
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onError(detail.ifEmpty { "template failed review" })
                    onBusy(null)
                }
                return@launch
            }
            val manifest = app.autohost.core.common.AhJson.json
                .decodeFromString(app.autohost.core.model.TaskManifest.serializer(), staged.manifest)
            val installed = graph.bundleExtractor.install(staged)
            graph.data.tasks.installVersion(
                taskId = manifest.id,
                manifestJson = staged.manifest,
                version = staged.version,
                bundlePath = installed.absolutePath,
                sha256 = staged.sha256,
                importReportJson = review.report.toJson().toString(),
                source = "template",
                name = manifest.name,
            )
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                onBusy(null)
                onOpenTask(manifest.id)
            }
        } catch (e: Exception) {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                onError(e.message ?: "template install failed")
                onBusy(null)
            }
        }
    }
}
