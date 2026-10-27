package app.autohost.importer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import app.autohost.AutoHostApp
import app.autohost.core.common.AhJson
import app.autohost.core.designsystem.components.EmptyState
import app.autohost.core.engine.tasks.BundleExtractor
import app.autohost.core.engine.tasks.ImportReport
import app.autohost.core.model.TaskManifest
import app.autohost.ui.ImportReportScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P2-04: "Open with AutoHost" / share-sheet entry point (docs/UI_SPEC S03). One pipeline for every
 * source: file streams land in the same review → report → install flow as every other source.
 *
 * Dev-folder and sample sources never come through here (they are directories, not shares); the
 * Library screen reviews those through the import pipeline directly.
 */
class ImportActivity : ComponentActivity() {

    private data class UiState(
        val report: ImportReport,
        val staged: BundleExtractor.Staged?,
    )

    private val state = mutableStateOf<UiState?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reviewIntent(intent)
        setContent {
            val current = state.value
            Surface {
                when {
                    current == null -> EmptyState(
                        title = "Nothing to import",
                        body = "Share a .aatask bundle with AutoHost, or copy a task into the dev folder.",
                    )
                    else -> ImportReportScreen(
                        report = current.report,
                        canInstall = current.staged != null && !current.report.blocking,
                        onReviewPermissions = null,
                        onInstall = { install(current) },
                        onCancel = { discardAndFinish(current) },
                    )
                }
            }
        }
    }

    private fun reviewIntent(sharedIntent: Intent?) {
        val graph = (application as AutoHostApp).graph
        val uri: Uri? = when (sharedIntent?.action) {
            Intent.ACTION_SEND -> sharedIntent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> sharedIntent.data
            else -> null
        }
        if (uri == null) {
            state.value = null
            return
        }
        val name = uri.lastPathSegment ?: "shared-bundle"
        state.value = try {
            val staged = contentResolver.openInputStream(uri)!!.use { stream ->
                graph.bundleExtractor.stage(stream, name)
            }
            val manifest = AhJson.json.decodeFromString(TaskManifest.serializer(), staged.manifest)
            UiState(
                report = ImportReport(
                    source = if (sharedIntent?.action == Intent.ACTION_SEND) "shared" else "open-with",
                    bundleName = name,
                    sha256 = staged.sha256,
                    taskId = staged.taskId,
                    taskName = manifest.name,
                    version = staged.version,
                ),
                staged = staged,
            )
        } catch (e: BundleExtractor.BundleException) {
            UiState(
                ImportReport(
                    source = name, bundleName = name, sha256 = "",
                    taskId = null, taskName = null, version = null,
                    errors = listOf(ImportReport.Issue("", e.rule, e.message ?: "bundle rejected")),
                ),
                staged = null,
            )
        } catch (e: Exception) {
            UiState(
                ImportReport(
                    source = name, bundleName = name, sha256 = "",
                    taskId = null, taskName = null, version = null,
                    errors = listOf(ImportReport.Issue("", "IO", e.message ?: "unreadable bundle")),
                ),
                staged = null,
            )
        }
    }

    private fun install(current: UiState) {
        val graph = (application as AutoHostApp).graph
        val staged = current.staged ?: return
        try {
            val dir = graph.bundleExtractor.install(staged)
            val manifest = AhJson.json.decodeFromString(TaskManifest.serializer(), staged.manifest)
            kotlinx.coroutines.runBlocking {
                withContext(Dispatchers.IO) {
                    graph.data.tasks.installVersion(
                        taskId = manifest.id,
                        manifestJson = staged.manifest,
                        version = staged.version,
                        bundlePath = dir.absolutePath,
                        sha256 = staged.sha256,
                        importReportJson = current.report.toJson().toString(),
                        source = "bundle",
                        name = manifest.name,
                    )
                }
            }
            finish()
        } catch (e: BundleExtractor.BundleException) {
            state.value = current.copy(
                report = current.report.copy(errors = listOf(ImportReport.Issue("", e.rule, e.message ?: "install failed"))),
            )
        }
    }

    private fun discardAndFinish(current: UiState) {
        current.staged?.let { staged ->
            if (staged.dir.path.contains("import-")) staged.dir.deleteRecursively()
        }
        finish()
    }
}
