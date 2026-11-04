package app.autohost.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Sent to a runtime when a run starts. Shape: schemas/broker.schema.json#/$defs/RunSpec.
 *
 * Secret boundary (X-13 / ADR-099): [runToken] IS a secret-class bearer credential — the worker
 * needs it for every broker call, so it rides the RunSpec by necessity, and nowhere else. It is
 * never logged, persisted to Room, exported, echoed into evidence, or sent to a model; everything
 * else in this shape (config, profile, checkpoint) must stay free of plaintext secret values.
 */
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
