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

/**
 * Host provider for the `store` capability: task-scoped and profile-scoped key/value storage. Owner keys:
 * `task:<taskId>` and `profile:<taskId>:<profileId>` (HANDOFF §3). Missing reads return `{}` (empty result),
 * never an error — "no value yet" is a normal state for tasks.
 */
class StoreProvider(private val backend: KvBackend) : CapabilityProvider {

    override val capabilityId: String = "store"
    override val providerId: String = "host.store"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String? {
        val allowed = setOf("get", "set", "delete", "list")
        if (method !in allowed) return null
        return when (args["scope"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content) {
            "profile" -> "store.profile"
            "task" -> "store.task"
            else -> null
        }
    }

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = try {
        when (method) {
            "get" -> {
                val value = backend.get(owner(ctx, args), key(args))
                if (value == null) Envelope.ok() else Envelope.ok(buildJsonObject { put("value", value) })
            }
            "set" -> {
                backend.set(owner(ctx, args), key(args), value(args))
                Envelope.ok()
            }
            "delete" -> {
                val removed = backend.delete(owner(ctx, args), key(args))
                Envelope.ok(buildJsonObject { put("deleted", removed) })
            }
            "list" -> {
                val prefix = args["prefix"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: ""
                val keys = backend.keys(owner(ctx, args), prefix)
                Envelope.ok(buildJsonObject { put("keys", kotlinx.serialization.json.JsonArray(keys.map { kotlinx.serialization.json.JsonPrimitive(it) })) })
            }
            else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown store method '$method'")
        }
    } catch (e: Exception) {
        Envelope.err(ErrorCode.INTERNAL, "store failed: ${e.message}")
    }

    private fun owner(ctx: CallContext, args: JsonObject): String {
        val scope = args["scope"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
        return if (scope == "profile") "profile:${ctx.taskId}:${ctx.profileId ?: "default"}" else "task:${ctx.taskId}"
    }

    private fun key(args: JsonObject): String =
        args["key"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
            ?: throw IllegalArgumentException("store calls need a 'key' argument")

    private fun value(args: JsonObject): kotlinx.serialization.json.JsonElement =
        args["value"] ?: throw IllegalArgumentException("store.set needs a 'value' argument")
}
