package app.autohost.core.engine.host

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonObject

/** Supplies the `system.info` payload. The app injects the Android implementation (Build.MODEL, …). */
fun interface SystemInfoSource {
    fun info(): JsonObject
}

/** The `system` capability: device facts a task may read without any grant beyond the implicit one. */
class SystemProvider(private val source: SystemInfoSource) : CapabilityProvider {

    override val capabilityId: String = "system"
    override val providerId: String = "host.system"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method == "info") "system.info" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope =
        if (method == "info") {
            try {
                Envelope.ok(source.info())
            } catch (e: Exception) {
                Envelope.err(ErrorCode.INTERNAL, "system.info failed: ${e.message}")
            }
        } else {
            Envelope.err(ErrorCode.INVALID_ARGS, "unknown system method '$method'")
        }
}
