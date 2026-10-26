package app.autohost.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Sent to a runtime when a run starts. Shape: schemas/broker.schema.json#/$defs/RunSpec. Never contains secrets. */
@Serializable
data class RunSpec(
    val runId: String,
    val taskId: String,
    val taskVersion: String = "",
    val runtime: String,
    val entry: String,
    val taskPath: String,
    val runToken: String,
    val attempt: Int = 1,
    val profileId: String? = null,
    val config: JsonObject = JsonObject(emptyMap()),
    val profile: JsonObject = JsonObject(emptyMap()),
    val checkpoint: JsonObject? = null,
    val trigger: TriggerInfo = TriggerInfo(),
    val grants: List<String> = emptyList(),
    val limits: RunLimits = RunLimits(),
    val paths: RunPaths = RunPaths(),
)

@Serializable
data class TriggerInfo(
    val type: String = "manual",
    val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class RunLimits(
    val timeoutMinutes: Int = 10,
    val idleTimeoutSeconds: Int = 300,
)

@Serializable
data class RunPaths(
    val runDir: String = "",
    val evidenceDir: String = "",
    val tmpDir: String = "",
)
