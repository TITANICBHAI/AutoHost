package app.autohost.ui.agent

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * P14-05 (S31, UI_SPEC §10): the `.aarun` agent-section viewer state — pure and synchronous.
 * [read] extracts `agent.json` from a bundle's zip bytes (honest null when the bundle has none
 * or is malformed) and [AgentSection.parse] shapes it for display. Every free-text field passes
 * through the SAME redactor the export bundle used (Gate 5-d: the viewer cannot be less redacted
 * than the export), and the transcript is only ever what the exporter chose to include.
 */
class AgentSectionViewerState(private val redact: (String) -> String) {

    data class AgentSection(
        val goal: String,
        val packages: List<String>,
        val providerProfile: String,
        val usage: Usage,
        val traceKept: Int,
        val traceDropped: Int,
        val stopStatus: String?,
        val stopReason: String?,
        val transcript: List<TranscriptLine>,
    )

    data class Usage(val turns: Int, val modelCalls: Int, val actions: Int, val tokens: Long)

    data class TranscriptLine(val kind: String, val text: String)

    /** The parsed section, or null when the bundle carries no agent section (honest). */
    var section: AgentSection? = null
        private set

    /** Feeds a `.aarun` bundle's bytes; returns whether an agent section was found. */
    fun load(bundleBytes: ByteArray): Boolean {
        val json = readAgentJson(bundleBytes) ?: run {
            section = null
            return false
        }
        section = parse(json)
        return true
    }

    /** Feeds an already-extracted `agent.json` object (the tests' path). */
    fun load(json: JsonObject) {
        section = parse(json)
    }

    private fun parse(json: JsonObject): AgentSection = AgentSection(
        goal = redact(json.str("goal").orEmpty()),
        packages = (json["packages"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
        providerProfile = json.str("provider_profile").orEmpty(),
        usage = (json["usage"] as? JsonObject)?.let { u ->
            Usage(
                turns = u.int("turns") ?: 0,
                modelCalls = u.int("model_calls") ?: 0,
                actions = u.int("actions") ?: 0,
                tokens = u.long("tokens") ?: 0L,
            )
        } ?: Usage(0, 0, 0, 0),
        traceKept = (json["trace"] as? JsonObject).int("kept"),
        traceDropped = (json["trace"] as? JsonObject).int("dropped"),
        stopStatus = (json["stop"] as? JsonObject).str("status"),
        stopReason = (json["stop"] as? JsonObject).str("reason"),
        transcript = (json["transcript"] as? JsonArray)?.mapNotNull { raw ->
            val line = raw as? JsonObject ?: return@mapNotNull null
            TranscriptLine(
                kind = line.str("kind").orEmpty(),
                text = redact(line.str("text").orEmpty().ifEmpty { line.str("args").orEmpty() }),
            )
        }.orEmpty(),
    )

    companion object {
        /** The `agent.json` entry of a `.aarun` zip, or null (missing/malformed — never a crash). */
        fun readAgentJson(bundleBytes: ByteArray): JsonObject? = runCatching {
            ZipInputStream(ByteArrayInputStream(bundleBytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "agent.json") {
                        val text = zip.readBytes().decodeToString()
                        return Json.parseToJsonElement(text).jsonObject
                    }
                    entry = zip.nextEntry
                }
                null
            }
        }.getOrNull()

        private fun JsonObject?.str(key: String): String? =
            this?.get(key)?.let { (it as? JsonPrimitive)?.content }

        private fun JsonObject?.int(key: String): Int? = str(key)?.toIntOrNull() ?: 0

        private fun JsonObject?.long(key: String): Long? = str(key)?.toLongOrNull() ?: 0L
    }
}
