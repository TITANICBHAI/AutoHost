package app.autohost.runtime.agent

import app.autohost.core.model.BrokerEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P14-05 (S31, with P13-15): rebuilds the `.aarun` agent section from a run's PERSISTED events —
 * by export time the live [AgentEventLog] may be gone, so the section comes from the same §11
 * events the log recorded. The shape is the one [AgentEventLog.agentSection] produces; [redact]
 * applies to every free-text field — IDENTICAL to the export bundle's redaction (Gate 5-d).
 * Returns null when the events carry no agent kinds (a non-agent run gets no agent.json).
 */
object AgentSectionBuilder {

    fun fromEvents(
        events: List<BrokerEvent>,
        redact: (String) -> String,
        keepTranscript: Boolean = false,
    ): JsonObject? {
        var sawAgentEvent = false
        var turns = 0
        var modelCalls = 0
        var tokens = 0L
        var actions = 0
        var stop: JsonObject? = null
        val transcript = ArrayList<JsonObject>()

        for (event in events) {
            when (event.type) {
                "progress" -> turns = event.data.str("turn")?.toIntOrNull() ?: turns
                "finished" -> stop = buildJsonObject {
                    put("status", event.data.str("status") ?: "FAILED")
                    event.data.str("reason")?.let { put("reason", it) }
                    event.data.str("message")?.let { put("message", it) }
                }
                "log" -> {
                    val data = event.data ?: continue
                    when (data.str("kind")) {
                        "agent.thought" -> {
                            sawAgentEvent = true
                            if (keepTranscript) {
                                transcript.add(
                                    buildJsonObject {
                                        put("kind", "agent.thought")
                                        put("text", redact(data.str("text").orEmpty()))
                                    },
                                )
                            }
                        }
                        "agent.action" -> {
                            sawAgentEvent = true
                            if (keepTranscript) {
                                transcript.add(
                                    buildJsonObject {
                                        put("kind", "agent.action")
                                        put("tool", data.str("tool") ?: "")
                                        put("args", redact(data.str("args").orEmpty()))
                                    },
                                )
                            }
                        }
                        "agent.result" -> {
                            sawAgentEvent = true
                            actions++
                        }
                        "agent.usage" -> {
                            sawAgentEvent = true
                            modelCalls++
                            tokens += (data.long("prompt_tokens") ?: 0L) + (data.long("completion_tokens") ?: 0L)
                        }
                        "agent.budget" -> sawAgentEvent = true
                    }
                }
            }
        }
        if (!sawAgentEvent) return null
        return buildJsonObject {
            put("usage", buildJsonObject {
                put("turns", turns)
                put("model_calls", modelCalls)
                put("actions", actions)
                put("tokens", tokens)
            })
            put("trace", buildJsonObject {
                put("kept", if (keepTranscript) transcript.size else 0)
                put("keep_transcript", keepTranscript)
            })
            stop?.let { put("stop", it) }
            if (keepTranscript) {
                put("transcript", JsonArray(transcript))
            }
        }
    }

    private val BrokerEvent.data: JsonObject get() = this.data ?: JsonObject(emptyMap())

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()
}
