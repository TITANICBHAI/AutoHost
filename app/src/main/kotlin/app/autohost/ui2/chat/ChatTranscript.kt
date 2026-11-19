package app.autohost.ui2.chat

import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** One message the person sent: the goal and the run it started. The run's steps live in the run store, not here. */
data class ChatEntry(val runId: String, val goal: String, val startedAtMs: Long)

/**
 * What the Agent tab remembers between visits: the goals sent (newest last) and the apps and model used last, so the next
 * message needs no setup. Pure; [ChatTranscriptStore] keeps it. A damaged or unknown saved value reads as empty.
 */
data class ChatTranscript(
    val entries: List<ChatEntry> = emptyList(),
    val packages: List<String> = emptyList(),
    val profileId: String? = null,
) {
    fun withEntry(entry: ChatEntry): ChatTranscript =
        copy(entries = (entries.filterNot { it.runId == entry.runId } + entry).takeLast(MAX_ENTRIES))

    fun withSetup(packages: List<String>, profileId: String?): ChatTranscript =
        copy(packages = packages.filter { it.isNotBlank() }.distinct(), profileId = profileId)

    fun cleared(): ChatTranscript = copy(entries = emptyList())

    fun toJson(): String = buildJsonObject {
        put("v", 1)
        put("packages", buildJsonArray { packages.forEach { add(it) } })
        profileId?.let { put("profile", it) }
        put(
            "entries",
            buildJsonArray {
                entries.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("run", entry.runId)
                            put("goal", entry.goal)
                            put("at", entry.startedAtMs)
                        },
                    )
                }
            },
        )
    }.toString()

    companion object {
        /** How many goals are kept. Older ones fall off; the Activity tab still has every run. */
        const val MAX_ENTRIES = 50

        fun parse(text: String?): ChatTranscript {
            if (text.isNullOrBlank()) return ChatTranscript()
            return runCatching {
                val root = Json.parseToJsonElement(text).jsonObject
                val entries = (root["entries"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    val run = item.str("run")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ChatEntry(run, item.str("goal").orEmpty(), item.str("at")?.toLongOrNull() ?: 0L)
                }
                val packages = (root["packages"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { pkg -> pkg.isNotBlank() } }
                ChatTranscript(entries.takeLast(MAX_ENTRIES), packages.distinct(), root.str("profile"))
            }.getOrDefault(ChatTranscript())
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
    }
}

/** Keeps the [ChatTranscript] in the app's private preferences. */
class ChatTranscriptStore(private val prefs: SharedPreferences) {
    fun load(): ChatTranscript = ChatTranscript.parse(prefs.getString(KEY, null))

    fun save(transcript: ChatTranscript) {
        prefs.edit().putString(KEY, transcript.toJson()).apply()
    }

    companion object {
        const val PREFS_NAME = "agent_chat"
        const val KEY = "transcript"
    }
}
