package app.autohost.core.broker

import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import kotlinx.serialization.json.JsonObject

/**
 * One implementation of a capability id (e.g. `device.ui`). The broker pipeline runs interceptors and finally
 * invokes the provider selected by [availability]. Providers never see raw handles cross the boundary — only
 * JSON arguments in, [Envelope] out (HANDOFF §3 contract; do not change the shape).
 */
interface CapabilityProvider {
    /** Capability id as the SDK passes it to `call`: `device.ui`, `device.app`, `store`, … */
    val capabilityId: String

    /** Stable name of this concrete provider (fallback chains carry several per capability). */
    val providerId: String

    /** Tier label (T0 … T4) from plan §3; informational for the catalog and describe(). */
    val tier: String

    /** Honest availability for the given environment; never silently missing. */
    fun availability(env: DeviceEnv): Availability

    /**
     * Permission id this method requires, given the args (e.g. `store.get` depends on `args.scope`), or null when
     * the method (or args) are unknown — which the pipeline reports as `INVALID_ARGS`.
     */
    fun permissionFor(method: String, args: JsonObject): String?

    /** The call itself. Expected failures are `Envelope.err`, never a thrown exception. */
    suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope
}
