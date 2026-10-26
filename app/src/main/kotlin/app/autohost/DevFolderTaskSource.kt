package app.autohost

import android.content.Context
import app.autohost.core.common.AhJson
import app.autohost.core.common.Clock
import app.autohost.core.model.TaskManifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/** One task discovered in the dev folder: manifest plus its directory and resolved config. */
data class DevTask(
    val taskId: String,
    val name: String,
    val manifest: TaskManifest,
    val dir: File,
    val config: JsonObject,
)

/**
 * Wave-1 task source: scans `getExternalFilesDir("dev")/<task>/task.json` (HANDOFF §3). Config = schema
 * defaults overlaid by an optional `config.json` beside the manifest. Push targets:
 * `/sdcard/Android/data/app.autohost/files/dev/<task>/`.
 */
class DevFolderTaskSource(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") private val clock: Clock,
) {

    private val _tasks = MutableStateFlow<List<DevTask>>(emptyList())
    val tasks: StateFlow<List<DevTask>> = _tasks.asStateFlow()

    fun refresh() {
        val root = context.getExternalFilesDir("dev") ?: return
        val found = ArrayList<DevTask>()
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        for (dir in dirs) {
            val manifestFile = File(dir, "task.json")
            if (!manifestFile.isFile) continue
            val manifest = parseManifest(manifestFile) ?: continue
            found.add(DevTask(manifest.id, manifest.name, manifest, dir, resolveConfig(manifest, dir)))
        }
        _tasks.value = found
    }

    private fun parseManifest(file: File): TaskManifest? = try {
        AhJson.json.decodeFromString(TaskManifest.serializer(), file.readText())
    } catch (e: Exception) {
        null
    }

    /** Schema defaults from config_schema, overlaid by dev `<task>/config.json`. */
    private fun resolveConfig(manifest: TaskManifest, dir: File): JsonObject {
        val defaults = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        val schema = manifest.configSchema
        if (schema != null) {
            val properties = schema["properties"] as? JsonObject
            properties?.forEach { (name, spec) ->
                (spec as? JsonObject)?.get("default")?.let { defaults[name] = it }
            }
        }
        val overlayFile = File(dir, "config.json")
        if (overlayFile.isFile) {
            try {
                val overlay = AhJson.json.parseToJsonElement(overlayFile.readText()).jsonObject
                overlay.forEach { (name, value) -> defaults[name] = value }
            } catch (e: Exception) {
                // a broken config.json falls back to schema defaults (fail closed on values, not on launch)
            }
        }
        return JsonObject(defaults)
    }
}
