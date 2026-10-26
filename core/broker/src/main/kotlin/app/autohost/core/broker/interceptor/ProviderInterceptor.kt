package app.autohost.core.broker.interceptor

import app.autohost.core.broker.BrokerInterceptor
import app.autohost.core.broker.CapabilityRegistry
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.broker.PipelineCall
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode

/**
 * Provider — picks the concrete provider for the capability by availability (registration order = fallback
 * order), resolves the permission the method requires, and rejects unknown methods/capabilities. Every failure
 * here is an honest envelope: `NEEDS_SETUP` carries reason + fixAction so the UI can offer a Fix.
 */
class ProviderInterceptor(
    private val registry: CapabilityRegistry,
    private val env: DeviceEnv,
) : BrokerInterceptor {

    override suspend fun intercept(call: PipelineCall, next: suspend (PipelineCall) -> Envelope): Envelope {
        val providers = registry.providers(call.capability)
        if (providers.isEmpty()) {
            return Envelope.err(
                ErrorCode.CAPABILITY_UNAVAILABLE,
                "No provider implements capability '${call.capability}' on this device.",
                reason = "NO_PROVIDER",
            )
        }

        var setup: Availability.NeedsSetup? = null
        var unsupported: Availability.Unsupported? = null
        for (provider in providers) {
            when (val availability = provider.availability(env)) {
                is Availability.Available -> {
                    val permission = provider.permissionFor(call.method, call.args)
                        ?: return Envelope.err(
                            ErrorCode.INVALID_ARGS,
                            "Unknown method '${call.method}' for capability '${call.capability}'.",
                            reason = "UNKNOWN_METHOD",
                        )
                    call.provider = provider
                    call.permission = permission
                    return next(call)
                }
                is Availability.NeedsSetup -> if (setup == null) setup = availability
                is Availability.Unsupported -> if (unsupported == null) unsupported = availability
            }
        }

        return setup?.let {
            Envelope.err(
                ErrorCode.NEEDS_SETUP,
                "'${call.capability}' needs a one-time setup step before it can be used.",
                reason = it.reason,
                fixAction = it.fixAction,
            )
        } ?: unsupported?.let {
            Envelope.err(
                ErrorCode.CAPABILITY_UNAVAILABLE,
                "'${call.capability}' is not supported on this device: ${it.reason}",
                reason = it.reason,
            )
        } ?: Envelope.err(
            ErrorCode.CAPABILITY_UNAVAILABLE,
            "'${call.capability}' has no available provider.",
            reason = "NO_PROVIDER",
        )
    }
}
