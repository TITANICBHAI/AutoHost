package app.autohost.core.engine.tasks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * P11-05 (ADR-083): the Task Studio draft — the pure state behind the in-app editor.
 *
 * The Studio edits **dev-folder tasks** (the authoring surface — installed bundles stay
 * round-tripped through the import pipeline, never hand-edited on device). A draft holds the
 * manifest's editable fields plus the entry file's text, validates the manifest against
 * [ManifestValidator] (the same rules an import applies — the Studio cannot produce a bundle
 * the importer would refuse), and tracks dirty state so the UI can warn before discarding.
 *
 * Pure: strings in, decisions out, no IO — the app-side controller owns the files.
 */
data class StudioDraft(
    val taskId: String,
    val name: String,
    val description: String,
    val version: String,
    val runtimeType: String,
    val entry: String,
    val entryText: String,
    /** The original manifest JSON (unchanged fields survive the round trip). */
    val originalManifest: JsonObject,
    val dirty: Boolean = false,
) {

    /** Builds the full manifest JSON: edited fields overlaid on the original. */
    fun manifestJson(): JsonObject = kotlinx.serialization.json.buildJsonObject {
        for ((key, value) in originalManifest) put(key, value)
        put("name", JsonPrimitive(name.trim()))
        put("description", JsonPrimitive(description))
        put("version", JsonPrimitive(version.trim()))
        put("runtime", buildJsonObject {
            put("type", JsonPrimitive(runtimeType))
            put("entry", JsonPrimitive(entry.trim()))
        })
    }

    /** The same validation an import runs — the Studio cannot drift from the importer's rules. */
    fun validate(): ManifestValidator.Result {
        val validation = ManifestValidator.validateJson(manifestJson().toString())
        return validation.second
    }

    fun with(
        name: String = this.name,
        description: String = this.description,
        version: String = this.version,
        entry: String = this.entry,
        entryText: String = this.entryText,
    ): StudioDraft = copy(
        name = name,
        description = description,
        version = version,
        entry = entry,
        entryText = entryText,
        dirty = true,
    )

    companion object {
        const val RUNTIME_PYTHON = "python"
        const val RUNTIME_FLOW = "flow"
        const val RUNTIME_JAVASCRIPT = "javascript"

        /** A blank draft for a new dev-folder task (the Studio saves it as `dev/<id>/task.json`). */
        fun newTask(taskId: String, name: String, runtimeType: String): StudioDraft {
            val entry = when (runtimeType) {
                RUNTIME_PYTHON -> "main.py"
                RUNTIME_FLOW -> "flow.yaml"
                RUNTIME_JAVASCRIPT -> "main.js"
                else -> "main.py"
            }
            val template = when (runtimeType) {
                RUNTIME_PYTHON -> "import autohost\n\n\ndef main(ctx):\n    ctx.log(\"hello from $name\")\n    return ctx.result(\"OK\", {\"done\": True})\n"
                RUNTIME_FLOW -> "version: 1\nsteps:\n  - do: log\n    message: hello from $name\n"
                RUNTIME_JAVASCRIPT -> "async function main() {\n  await autohost.log(\"hello from $name\");\n  return { code: \"OK\", data: { done: true } };\n}\n"
                else -> ""
            }
            return StudioDraft(
                taskId = taskId,
                name = name,
                description = "",
                version = "0.1.0",
                runtimeType = runtimeType,
                entry = entry,
                entryText = template,
                originalManifest = buildJsonObject {},
            )
        }

        /** Opens an existing dev-folder task. The entry file's text comes from the controller. */
        fun fromManifest(manifestJson: JsonObject, entryText: String): StudioDraft {
            val runtime = manifestJson["runtime"] as? JsonObject
            fun str(key: String): String = (manifestJson[key] as? JsonPrimitive)?.content ?: ""
            return StudioDraft(
                taskId = str("id"),
                name = str("name"),
                description = str("description"),
                version = str("version").ifBlank { "0.1.0" },
                runtimeType = (runtime?.get("type") as? JsonPrimitive)?.content ?: RUNTIME_PYTHON,
                entry = (runtime?.get("entry") as? JsonPrimitive)?.content ?: "main.py",
                entryText = entryText,
                originalManifest = manifestJson,
            )
        }
    }
}
