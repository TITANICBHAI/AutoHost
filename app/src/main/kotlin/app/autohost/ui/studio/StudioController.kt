package app.autohost.ui.studio

import app.autohost.AppGraph
import app.autohost.core.common.AhLog
import app.autohost.core.engine.tasks.RepoIndex
import app.autohost.core.engine.tasks.StudioDraft
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * P11-05 (ADR-083): the Task Studio controller — dev-folder drafts (create/open/save through the
 * importer's own validator) plus the task-repository consumer (P11-03's other half): fetch an
 * `index.json`, show Install/Upgrade/Up-to-date/Rejected verdicts from the pure [RepoIndex], and
 * install through the same review → install path as any other import. HTTPS only — a repo is a
 * source of CODE.
 */
class StudioController(private val graph: AppGraph) {

    private val devRoot: File?
        get() = graph.app.getExternalFilesDir("dev")

    // ---- dev-folder drafts -------------------------------------------------------------------

    fun listDevTasks(): List<File> =
        devRoot?.listFiles()?.filter { it.isDirectory && File(it, "task.json").isFile }?.sortedBy { it.name } ?: emptyList()

    fun openDraft(dir: File): StudioDraft? = runCatching {
        val manifestFile = File(dir, "task.json")
        if (!manifestFile.isFile) return null
        val manifestJson = app.autohost.core.common.AhJson.json.parseToJsonElement(manifestFile.readText())
        val entry = app.autohost.core.common.AhJson.json.decodeFromString(
            app.autohost.core.model.TaskManifest.serializer(),
            manifestFile.readText(),
        ).runtime.entry
        val entryText = File(dir, entry).takeIf { it.isFile }?.readText() ?: ""
        StudioDraft.fromManifest(manifestJson as kotlinx.serialization.json.JsonObject, entryText)
    }.getOrNull()

    /** Creates `dev/<id>/` with task.json + the entry file (never overwrites an existing task). */
    fun create(draft: StudioDraft): Result<File> {
        val root = devRoot ?: return Result.failure(IllegalStateException("dev folder unavailable"))
        val dir = File(root, draft.taskId)
        if (dir.exists()) return Result.failure(IllegalStateException("dev task '${draft.taskId}' already exists"))
        return saveInto(dir, draft)
    }

    fun save(dir: File, draft: StudioDraft): Result<File> {
        val validation = draft.validate()
        if (!validation.ok) {
            return Result.failure(IllegalArgumentException(validation.errors.firstOrNull()?.describe() ?: "manifest invalid"))
        }
        return saveInto(dir, draft)
    }

    private fun saveInto(dir: File, draft: StudioDraft): Result<File> = runCatching {
        dir.mkdirs()
        File(dir, "task.json").writeText(draft.manifestJson().toString())
        val entryFile = File(dir, draft.entry)
        entryFile.parentFile?.mkdirs()
        entryFile.writeText(draft.entryText)
        graph.devTasks.refresh()
        AhLog.i(TAG, "studio saved dev task", buildJsonObject {
            put("task_id", kotlinx.serialization.json.JsonPrimitive(draft.taskId))
        })
        dir
    }

    // ---- the repo consumer (P11-03, part 2) ----------------------------------------------------

    data class RepoRow(
        val entry: RepoIndex.Entry,
        val verdict: RepoIndex.Verdict,
        val message: String,
    )

    data class RepoView(val name: String?, val rows: List<RepoRow>, val issues: List<RepoIndex.Issue>)

    /** Fetches + parses an index and computes per-entry verdicts against the installed tasks. */
    suspend fun loadRepo(indexUrl: String): Result<RepoView> = withContext(Dispatchers.IO) {
        runCatching {
            val url = java.net.URI(indexUrl.trim()).toURL()
            check(url.protocol == "https" || url.protocol == "file") { "repo index must be https (or file: for tests)" }
            val text = url.openConnection().apply { connectTimeout = 10_000; readTimeout = 10_000 }
                .getInputStream().use { it.readBytes().decodeToString() }
            val parsed = RepoIndex.parse(text)
            val index = parsed.index
                ?: return@runCatching RepoView(null, emptyList(), parsed.issues)
            val installed = installedVersions()
            val rows = index.entries.map { entry ->
                val verdict = RepoIndex.select(entry, installed[entry.id], HOST_API)
                val message = when (verdict) {
                    is RepoIndex.Verdict.Upgrade -> "${verdict.from} → ${verdict.to}"
                    is RepoIndex.Verdict.Reject -> verdict.message
                    else -> ""
                }
                RepoRow(entry, verdict, message)
            }
            RepoView(index.name, rows, parsed.issues)
        }
    }

    /** Fetches a bundle from the repo and installs it through the standard import path. */
    suspend fun installFromRepo(sourceUrl: String, expectedSha: String?): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = java.net.URI(sourceUrl.trim()).toURL()
            check(url.protocol == "https" || url.protocol == "file") { "repo bundles must be https (or file: for tests)" }
            val bytes = url.openConnection().apply { connectTimeout = 10_000; readTimeout = 30_000 }
                .getInputStream().use { it.readBytes() }
            if (expectedSha != null) {
                val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                check(RepoIndex.verifySha(expectedSha, actual)) { "bundle sha256 mismatch — refusing the install" }
            }
            val review = graph.importPipeline.review(
                app.autohost.core.engine.tasks.ImportPipeline.Source.Bytes(sourceUrl.substringAfterLast('/')) { bytes.inputStream() },
            )
            val staged = review.staged ?: error(review.report.errors.firstOrNull()?.describe() ?: "bundle rejected")
            check(review.canInstall) { review.report.errors.firstOrNull()?.describe() ?: "bundle rejected" }
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
                source = "repo",
                name = manifest.name,
            )
            manifest.id
        }
    }

    private suspend fun installedVersions(): Map<String, String> =
        graph.data.tasks.observeActive().first().associate { it.taskId to it.activeVersion }

    companion object {
        private const val TAG = "studio"
        /** The host API level reported to repo entries (`min_host_api`). Bump with recorded ADRs. */
        const val HOST_API = 8
    }
}
