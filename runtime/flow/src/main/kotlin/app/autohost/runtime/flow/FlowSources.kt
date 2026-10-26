package app.autohost.runtime.flow

import app.autohost.core.model.FlowDocument
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * P6-06: flow entry resolution (HANDOFF §4 P6-06). A flow task ships its document beside
 * `task.json`; the manifest names it (`runtime.entry`, e.g. `flow.yaml`), and when the entry is
 * blank the resolver falls back to `flow.json`, then `flow.yaml` (ADR-052 order: JSON first —
 * it needs no dependency). Subflows for `runFlow` are every other flow-shaped file in the task
 * folder, registered under both the file name (`child.json`) and the stem (`child`), so
 * `runFlow {file: child}` from the golden-vector style and `runFlow {file: child.json}` both work.
 */
object FlowSources {

    const val DEFAULT_JSON = "flow.json"
    const val DEFAULT_YAML = "flow.yaml"

    /** Finds the flow document for [entry] (blank = default resolution). Null when nothing matches. */
    fun resolveFlowFile(taskPath: File, entry: String?): File? {
        val named = entry?.takeIf { it.isNotBlank() }
        if (named != null) {
            val file = File(taskPath, named)
            if (file.isFile) return file
            return null // explicit entry that does not exist must NOT silently fall back
        }
        File(taskPath, DEFAULT_JSON).takeIf { it.isFile }?.let { return it }
        File(taskPath, DEFAULT_YAML).takeIf { it.isFile }?.let { return it }
        return null
    }

    /**
     * Loads and parses the main flow document. `.json` goes through [FlowParser.parse]; `.yaml`/
     * `.yml` requires an installed [YamlParserPlug] (ADR-052 — no hand-rolled YAML). Every failure
     * is a typed [FlowSourceException]; the provider maps it to START_FAILED.
     */
    fun loadFlowDocument(file: File): FlowDocument {
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            throw FlowSourceException("cannot read flow file ${file.name}: ${e.message}", e)
        }
        return parseFlowText(text, file.name)
    }

    /** Parses flow text by extension. Kept public: the flow editor (P6-10) validates the same way. */
    fun parseFlowText(text: String, fileName: String): FlowDocument {
        val lower = fileName.lowercase()
        val doc = when {
            lower.endsWith(".json") -> parseJsonText(text, fileName)
            lower.endsWith(".yaml") || lower.endsWith(".yml") -> {
                val plug = YamlParserPlug.installed ?: throw FlowSourceException(
                    "flow '$fileName' is YAML but no YAML parser is installed (ADR-052: the " +
                        "yamlkt plug lands when a Gradle machine can licence-check the dependency). " +
                        "Ship a flow.json beside task.json, or install the plug in :app wiring.",
                )
                try {
                    plug.parseToJsonObject(text)
                } catch (e: Exception) {
                    throw FlowSourceException("flow '$fileName' is not valid YAML: ${e.message}", e)
                }
            }
            else -> throw FlowSourceException(
                "flow entry '$fileName' must be flow.json (or flow.yaml once the YAML plug is installed)",
            )
        }
        return try {
            FlowParser.fromJson(doc)
        } catch (e: FlowParseException) {
            throw FlowSourceException("flow '$fileName' failed to parse: ${e.message}", e)
        }
    }

    private fun parseJsonText(text: String, fileName: String): JsonObject {
        val element = try {
            app.autohost.core.common.AhJson.json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw FlowSourceException("flow '$fileName' is not valid JSON: ${e.message}", e)
        }
        return element as? JsonObject
            ?: throw FlowSourceException("flow '$fileName' must be a JSON object")
    }

    /**
     * Loads `runFlow` targets: every `.json`/`.yaml` file in the task folder except the main
     * document (and `task.json`, which is a manifest, not a flow). Keys: file name and stem.
     * Missing/unparsable subflows do NOT fail the start — `runFlow` fails typed INVALID_ARGS at
     * execution time only when the step actually runs (same fail-closed point as the reference).
     */
    fun loadSubflows(taskPath: File, mainFlow: File?): Map<String, FlowDocument> {
        val out = LinkedHashMap<String, FlowDocument>()
        val files = taskPath.listFiles { f -> f.isFile } ?: return out
        for (file in files.sortedBy { it.name }) {
            val lower = file.name.lowercase()
            if (file == mainFlow) continue
            if (file.name == "task.json") continue
            if (!lower.endsWith(".json") && !lower.endsWith(".yaml") && !lower.endsWith(".yml")) continue
            val doc = try {
                loadFlowDocument(file)
            } catch (e: Exception) {
                null // fail closed at execution time, only if a runFlow step actually targets it
            } ?: continue
            out[file.name] = doc
            out[file.nameWithoutExtension] = doc
        }
        return out
    }
}
