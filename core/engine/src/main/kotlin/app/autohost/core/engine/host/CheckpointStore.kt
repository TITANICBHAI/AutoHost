package app.autohost.core.engine.host

import app.autohost.core.common.AhJson
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * P5-01 (plan §12.2): atomic per-run checkpoints. `ctx.save()` lands in [StateProvider], which keeps
 * the hot in-memory copy and delegates durability here: write `checkpoint.json.tmp`, fsync, atomic
 * rename — a crash mid-save never leaves a torn checkpoint. The app maps a run id to
 * `<filesDir>/runs/<runId>/` so the CrashReconciler (P5-05) can restore the checkpoint on resume.
 *
 * Pure JVM (temp dir in tests); file IO is deliberately boring and single-file.
 */
class CheckpointStore(private val dirFor: (runId: String) -> File?) {

    private val hot = ConcurrentHashMap<String, JsonObject>()

    fun save(runId: String, checkpoint: JsonObject) {
        hot[runId] = checkpoint
        val dir = dirFor(runId) ?: return
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "checkpoint.json.tmp")
            tmp.writeText(AhJson.json.encodeToString(JsonObject.serializer(), checkpoint))
            if (!tmp.renameTo(File(dir, "checkpoint.json"))) {
                tmp.delete()
            }
        }
    }

    fun load(runId: String): JsonObject? {
        hot[runId]?.let { return it }
        val file = dirFor(runId)?.let { File(it, "checkpoint.json") } ?: return null
        if (!file.isFile) return null
        return runCatching {
            AhJson.json.parseToJsonElement(file.readText()) as? JsonObject
        }.getOrNull()
    }

    fun drop(runId: String) {
        hot.remove(runId)
        dirFor(runId)?.let { File(it, "checkpoint.json").delete() }
    }
}
