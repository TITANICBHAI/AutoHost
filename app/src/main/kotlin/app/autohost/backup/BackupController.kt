package app.autohost.backup

import app.autohost.AppGraph
import app.autohost.core.common.AhLog
import app.autohost.core.common.Ids
import app.autohost.core.engine.backup.BackupCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * P11-08 (ADR-082): backup/restore of tasks + profiles, minus secrets, by construction.
 *
 * Export reads the installed tasks (active version) + their profiles and encodes them through
 * the pure [BackupCodec]; bundle files are re-zipped into `bundle_b64` so a restore can
 * reinstall the CODE, not just the metadata (dev-folder tasks are exported without a bundle —
 * the restore plan reports that honestly). Secrets ride as NAMES only; restore re-asks the
 * person, which is the entire point of "minus secrets".
 *
 * The UI entry is Settings → Backup (S26): Export (SAF create) / Restore (SAF open) with the
 * restore plan shown before anything is applied.
 */
class BackupController(private val graph: AppGraph) {

    // ---- export ------------------------------------------------------------------------------

    suspend fun export(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val tasks = ArrayList<BackupCodec.BackupTask>()
            val profiles = ArrayList<BackupCodec.BackupProfile>()
            for (entity in graph.data.tasks.observeActive().first()) {
                val version = graph.data.tasks.latestVersion(entity.taskId) ?: continue
                val bundleB64 = bundleBase64(File(version.bundlePath))
                tasks += BackupCodec.BackupTask(
                    id = entity.taskId,
                    manifest = version.manifestJson,
                    sha256 = version.bundleSha256 ?: "",
                    bundleB64 = bundleB64,
                )
                for (profile in graph.data.profiles.allOf(entity.taskId)) {
                    profiles += BackupCodec.BackupProfile(
                        id = profile.profileId,
                        taskId = profile.taskId,
                        name = profile.name,
                        values = parseObject(profile.valuesJson),
                        secretNames = profile.secretKeys,
                        target = profile.targetJson?.let { parseObject(it) },
                    )
                }
            }
            val document = BackupCodec.Document(
                createdAt = java.time.Instant.ofEpochMilli(graph.clock.nowMs()).toString(),
                appVersion = null, // the flavor's BuildConfig rides the APK; the doc stays flavor-free
                tasks = tasks,
                profiles = profiles,
            )
            val encoded = BackupCodec.encode(document)
            if (!encoded.ok) {
                return@withContext Result.failure(IllegalStateException("backup refused: ${encoded.issues.joinToString()}"))
            }
            AhLog.i(TAG, "backup exported", buildJsonObject {
                put("tasks", kotlinx.serialization.json.JsonPrimitive(tasks.size))
                put("profiles", kotlinx.serialization.json.JsonPrimitive(profiles.size))
            })
            Result.success(encoded.json!!)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- restore -----------------------------------------------------------------------------

    data class Restored(val tasks: Int, val profiles: Int, val skipped: Int, val plan: BackupCodec.RestorePlan)

    /** Plans and applies a restore. Secrets are NEVER restored — profiles come back with empty secrets. */
    suspend fun restore(text: String, allowOverwrite: Boolean): Result<Restored> = withContext(Dispatchers.IO) {
        val decoded = BackupCodec.decode(text)
        val document = decoded.document
            ?: return@withContext Result.failure(IllegalArgumentException("backup unreadable: ${decoded.issues.joinToString()}"))
        val installedTaskIds = graph.data.tasks.observeActive().first().map { it.taskId }.toSet()
        val installedProfileIds = HashSet<String>()
        for (entity in graph.data.tasks.observeActive().first()) {
            graph.data.profiles.allOf(entity.taskId).forEach { installedProfileIds += it.profileId }
        }
        val plan = BackupCodec.planRestore(document, installedTaskIds, installedProfileIds, allowOverwrite)

        var restoredTasks = 0
        var restoredProfiles = 0
        var skipped = 0
        for (taskPlan in plan.tasks) {
            when (taskPlan.action) {
                BackupCodec.TaskAction.SKIP -> skipped += 1
                BackupCodec.TaskAction.RESTORE_NEW, BackupCodec.TaskAction.OVERWRITE -> {
                    val bytes = taskPlan.task.bundleB64
                    if (bytes == null) {
                        skipped += 1
                        continue
                    }
                    val ok = runCatching { reinstallBundle(bytes) }.getOrDefault(false)
                    if (ok) restoredTasks += 1 else skipped += 1
                }
            }
        }
        for (profilePlan in plan.profiles) {
            when (profilePlan.action) {
                BackupCodec.ProfileAction.SKIP_EXISTS -> skipped += 1
                BackupCodec.ProfileAction.RESTORE_NEW, BackupCodec.ProfileAction.SECRET_CLEARED -> {
                    runCatching {
                        graph.data.profiles.create(
                            taskId = profilePlan.profile.taskId,
                            name = profilePlan.profile.name,
                            valuesJson = profilePlan.profile.values.toString(),
                            secretKeys = profilePlan.profile.secretNames, // names only — the store stays empty until re-asked
                            targetJson = profilePlan.profile.target?.toString(),
                        )
                    }.onSuccess {
                        restoredProfiles += 1
                        // Wipe any stale secret blobs for the fresh id so "re-enter" is literally true.
                        runCatching { graph.secrets.deleteForProfile(profilePlan.profile.id) }
                    }.onFailure { skipped += 1 }
                }
            }
        }
        AhLog.i(TAG, "backup restored", buildJsonObject {
            put("tasks", kotlinx.serialization.json.JsonPrimitive(restoredTasks))
            put("profiles", kotlinx.serialization.json.JsonPrimitive(restoredProfiles))
            put("skipped", kotlinx.serialization.json.JsonPrimitive(skipped))
        })
        Result.success(Restored(restoredTasks, restoredProfiles, skipped, plan))
    }

    /** One bundle through the same review→install path as a manual import (source "backup"). */
    private suspend fun reinstallBundle(bundleB64: String): Boolean {
        val bytes = java.util.Base64.getDecoder().decode(bundleB64)
        val name = "backup-${Ids.ulid(graph.clock.nowMs())}.aatask"
        val review = graph.importPipeline.review(
            app.autohost.core.engine.tasks.ImportPipeline.Source.Bytes(name) { bytes.inputStream() },
        )
        val staged = review.staged ?: return false
        if (review.report.errors.isNotEmpty() || !review.canInstall) {
            staged.dir.deleteRecursively()
            return false
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
            source = "backup",
            name = manifest.name,
        )
        return true
    }

    /** Zips a bundle directory (dev-folder install) or reads the .aatask file directly. */
    private fun bundleBase64(path: File): String? = runCatching {
        val bytes = if (path.isDirectory) zipDirectory(path) else path.readBytes()
        java.util.Base64.getEncoder().encodeToString(bytes)
    }.getOrNull()

    private fun zipDirectory(dir: File): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entry = ZipEntry(file.relativeTo(dir).path.replace('\\', '/'))
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun parseObject(text: String): JsonObject = runCatching {
        app.autohost.core.common.AhJson.json.decodeFromString(JsonObject.serializer(), text)
    }.getOrDefault(JsonObject(emptyMap()))

    private companion object {
        const val TAG = "backup"
    }
}
