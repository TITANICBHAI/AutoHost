package app.autohost.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Fire-and-forget event from a runtime. Types: log, progress, stage_begin, stage_end, evidence, result, next_run, heartbeat, finished. */
@Serializable
data class BrokerEvent(
    val type: String,
    val ts: Long = 0,
    val level: String? = null,
    val message: String? = null,
    val stage: String? = null,
    val data: JsonObject? = null,
)
