package app.autohost.capabilities.rawbridge

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
 * P8-12: the `android.api.direct` capability (plan §6.1 W2: "Direct Android API access from
 * Python (jclass, system services). DANGEROUS; breaks the sandbox by design").
 *
 * The two-level gate (plan §14.5): the run must hold the `android.api.direct` grant **and** the
 * global `raw_api` switch must be on — the switch is enforced by the broker's
 * InsecureFeatureInterceptor (which runs before this provider), and the grant by
 * GrantInterceptor; [invoke] therefore only sees calls that passed both, re-checks nothing, and
 * returns the worker marker the SDK handshake expects (ADR-062).
 *
 * The provider itself stays honest and small: it is the handshake seam, not the sandbox hole.
 * The Chaquopy passthroughs live in the worker (module-global `__autohost_context__`), armed by
 * the worker bridge only after this authorize call returned authorized=true.
 */
class RawBridgeProvider(
    private val workerPid: () -> Int,
    private val workerArmer: (runToken: String, armed: Boolean) -> Unit = { _, _ -> },
) : CapabilityProvider {

    override val capabilityId: String = "android.api.direct"
    override val providerId: String = "rawbridge.host"
    override val tier: String = "T2"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method == "authorize") "android.api.direct" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "authorize" -> {
            workerArmer(ctx.runId, true)
            Envelope.ok(buildJsonObject {
                put("authorized", true)
                put("worker_pid", workerPid())
            })
        }
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown android.api.direct method '$method'")
    }
}
