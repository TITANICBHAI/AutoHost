package app.autohost.runtime.flow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P6-08: the pre-state a recorded step expects to see on screen right before it executes
 * (plan §7.9 recorder). Semantic node keys only — no raw view tree, no coordinates in the
 * similarity input: keys are `cls|id|text|desc` rows, so the comparison survives layout shifts
 * that keep the same widgets.
 *
 * Storage: recorded flows keep their schema-strict, diffable flow document; the per-step
 * snapshots live in a **sidecar** `<flow>.recording.json` beside it (ADR-053). The flow file
 * never grows recorder metadata; GuardedReplay matches snapshots to steps by label.
 */
data class StepSnapshot(
    val packageName: String,
    val activity: String,
    /** `cls|id|text|desc` rows of the visible nodes, in document order, normalized. */
    val keys: List<String>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("package", packageName)
        put("activity", activity)
        put("keys", JsonArray(keys.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }

    companion object {
        fun fromJson(obj: JsonObject): StepSnapshot {
            val pkg = (obj["package"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            val activity = (obj["activity"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            val keys = (obj["keys"] as? JsonArray)
                ?.mapNotNull { it as? kotlinx.serialization.json.JsonPrimitive }
                ?.map { it.content }
                ?: emptyList()
            return StepSnapshot(pkg, activity, keys)
        }

        /** Semantic key for one visible node (the recorder and the matcher share this shape). */
        fun nodeKey(cls: String, id: String?, text: String?, desc: String?): String =
            listOf(
                cls.substringAfterLast('.'),
                id.orEmpty(),
                text.orEmpty(),
                desc.orEmpty(),
            ).joinToString("|")
    }
}

/** One sidecar entry: what the recorder captured for the step named [label]. */
data class RecordingStepMeta(
    val label: String,
    val snapshot: StepSnapshot,
    /** Bounds-only (coordinate) selectors are flagged and need explicit confirmation (G6-e). */
    val needsConfirmation: Boolean,
    /** Why the selector rank was chosen: id | desc | text | relational | bounds. */
    val selectorRank: String,
    val elapsedMs: Long,
    /** Password inputs are recorded as ${secret.INPUT_n}; the review UI binds them before save. */
    val secretNames: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("label", label)
        put("pre_state", snapshot.toJson())
        put("needs_confirmation", needsConfirmation)
        put("selector_rank", selectorRank)
        put("elapsed_ms", elapsedMs)
        if (secretNames.isNotEmpty()) put("secrets", JsonArray(secretNames.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }

    companion object {
        fun fromJson(obj: JsonObject): RecordingStepMeta {
            val label = (obj["label"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            val snapshot = (obj["pre_state"] as? JsonObject)?.let { StepSnapshot.fromJson(it) }
                ?: StepSnapshot("", "", emptyList())
            val needs = (obj["needs_confirmation"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
            val rank = (obj["selector_rank"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "text"
            val elapsed = (obj["elapsed_ms"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            val secrets = (obj["secrets"] as? JsonArray)
                ?.mapNotNull { it as? kotlinx.serialization.json.JsonPrimitive }?.map { it.content }
                ?: emptyList()
            return RecordingStepMeta(label, snapshot, needs, rank, elapsed, secrets)
        }
    }
}

/** The whole sidecar document: `<flow>.recording.json` = {"schema": 1, "steps": [...], ...}. */
data class RecordingSidecar(
    val flowName: String,
    val recordedAtMs: Long,
    /** What to do when replay meets a different screen: stop | ask_user | continue (default stop). */
    val onMismatch: String = "stop",
    /** Poll budget for the state check before declaring a mismatch. */
    val stateTimeoutMs: Long = 3_000L,
    val steps: List<RecordingStepMeta>,
) {
    fun byLabel(label: String): RecordingStepMeta? = steps.firstOrNull { it.label == label }

    fun toJson(): JsonObject = buildJsonObject {
        put("schema", 1)
        put("flow", flowName)
        put("recorded_at_ms", recordedAtMs)
        put("on_mismatch", onMismatch)
        put("state_timeout_ms", stateTimeoutMs)
        put("steps", JsonArray(steps.map { it.toJson() }))
    }

    companion object {
        fun fromJson(obj: JsonObject): RecordingSidecar {
            val flow = (obj["flow"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            val at = (obj["recorded_at_ms"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            val onMismatch = (obj["on_mismatch"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "stop"
            val timeout = (obj["state_timeout_ms"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 3_000L
            val steps = (obj["steps"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.map { RecordingStepMeta.fromJson(it) }
                ?: emptyList()
            return RecordingSidecar(flow, at, onMismatch, timeout, steps)
        }
    }
}
