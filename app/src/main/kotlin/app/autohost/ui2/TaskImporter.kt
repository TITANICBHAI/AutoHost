package app.autohost.ui2

import app.autohost.AppGraph
import app.autohost.core.engine.tasks.ImportPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ImportOutcome {
    data class Installed(val taskId: String, val name: String) : ImportOutcome
    data class Rejected(val reasons: List<String>) : ImportOutcome
}

/**
 * Installs a task bundle (.zip with task.json) the person picked from the phone's storage, through the same review and
 * install path the repo installer uses (`ImportPipeline.review` → `BundleExtractor.install` → `tasks.installVersion`).
 * This is the no-computer way to add a Python, JavaScript or flow task: pick the file, see why it was refused or open it.
 */
object TaskImporter {

    /** A task bundle is small text; refuse anything bigger rather than read an arbitrary file into memory. */
    const val MAX_BYTES = 20 * 1024 * 1024

    suspend fun installZip(graph: AppGraph, displayName: String, bytes: ByteArray): ImportOutcome = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext ImportOutcome.Rejected(listOf("The file is empty."))
        if (bytes.size > MAX_BYTES) return@withContext ImportOutcome.Rejected(listOf("The file is larger than 20 MB, so it is not a task bundle."))
        try {
            val review = graph.importPipeline.review(ImportPipeline.Source.Bytes(displayName) { bytes.inputStream() })
            val staged = review.staged
            if (staged == null || !review.canInstall) {
                val reasons = review.report.errors.map { it.describe() }.ifEmpty { listOf("This file is not a valid AutoHost task bundle.") }
                return@withContext ImportOutcome.Rejected(reasons)
            }
            val dir = graph.bundleExtractor.install(staged)
            val manifest = app.autohost.core.common.AhJson.json.decodeFromString(
                app.autohost.core.model.TaskManifest.serializer(),
                staged.manifest,
            )
            graph.data.tasks.installVersion(
                taskId = manifest.id,
                manifestJson = staged.manifest,
                version = staged.version,
                bundlePath = dir.absolutePath,
                sha256 = staged.sha256,
                importReportJson = review.report.toJson().toString(),
                source = "file",
                name = manifest.name,
            )
            ImportOutcome.Installed(manifest.id, manifest.name)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportOutcome.Rejected(listOf(e.message ?: "The file could not be installed."))
        }
    }
}
