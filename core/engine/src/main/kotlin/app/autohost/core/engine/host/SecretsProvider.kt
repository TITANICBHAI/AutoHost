package app.autohost.core.engine.host

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonObject

/**
 * The `secrets` capability: NOT_FOUND until Phase 2 brings the Keystore-backed SecretStore (HANDOFF §3).
 * Declaring the provider now keeps describe() honest and the SDK surface stable.
 */
class SecretsProvider : CapabilityProvider {

    override val capabilityId: String = "secrets"
    override val providerId: String = "host.secrets"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method == "get") "secrets.read" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope =
        Envelope.err(
            ErrorCode.NOT_FOUND,
            "Secrets are not available yet (arrive with the Phase 2 permission review).",
            reason = "SECRETS_NOT_AVAILABLE",
        )
}
