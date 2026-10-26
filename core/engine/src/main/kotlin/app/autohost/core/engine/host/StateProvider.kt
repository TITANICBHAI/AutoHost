package app.autohost.core.engine.host

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * The `state` capability: per-run checkpoint + stage state (plan §12.2).
 *
 * P5-01: the SDK's `ctx.save()` sends `{"state": {…}}` — that shape is canonical (the simulator and
 * the reference engine use it); `{"value": …}` stays accepted as an alias. Every accepted write is
 * mirrored into the [CheckpointStore] (atomic file under the run dir) so a resume after
 * INTERRUPTED (P5-05) restores exactly the last saved state.
 */
class StateProvider(private val checkpoints: CheckpointStore? = null) : CapabilityProvider {

    override val capabilityId: String = "state"
    override val providerId: String = "host.state"
    override val tier: String = "T0"

    private val hot = ConcurrentHashMap<String, JsonObject>()

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method in setOf("get", "set", "stage_begin", "stage_end")) "state.checkpoint" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "get" -> {
            val value = hot[ctx.runId]
            if (value == null) Envelope.ok() else Envelope.ok(buildJsonObject { put("value", value) })
        }
        "set" -> {
            val value = (args["state"] as? JsonObject)
                ?: (args["value"] as? JsonObject)
                ?: return Envelope.err(
                    ErrorCode.INVALID_ARGS,
                    "state.set needs a JSON object 'state' (the SDK's ctx.save() shape)",
                )
            hot[ctx.runId] = value
            checkpoints?.save(ctx.runId, value)
            Envelope.ok()
        }
        "stage_begin" -> {
            val stage = args["stage"]?.toString()?.trim('"')
                ?: return Envelope.err(ErrorCode.INVALID_ARGS, "stage_begin needs a 'stage' name")
            update(ctx.runId) { it.copy2("stage", stage, "stage_status", "running") }
            Envelope.ok()
        }
        "stage_end" -> {
            update(ctx.runId) { it.minus("stage").minus("stage_status") }
            Envelope.ok()
        }
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown state method '$method'")
    }

    private fun update(runId: String, transform: (JsonObject) -> JsonObject) {
        val next = transform(hot[runId] ?: JsonObject(emptyMap()))
        hot[runId] = next
        checkpoints?.save(runId, next)
    }

    private fun JsonObject.copy2(k1: String, v1: String, k2: String, v2: String): JsonObject =
        buildJsonObject {
            forEach { (key, value) -> if (key != "stage" && key != "stage_status") put(key, value) }
            put(k1, v1)
            put(k2, v2)
        }

    /** Latest checkpoint for the run (hot copy first, then the durable file). */
    fun checkpointOf(runId: String): JsonObject? =
        hot[runId] ?: checkpoints?.load(runId)

    fun drop(runId: String) {
        hot.remove(runId)
        checkpoints?.drop(runId)
    }
}
