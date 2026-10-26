package app.autohost.core.broker.interceptor

import app.autohost.core.broker.BrokerInterceptor
import app.autohost.core.broker.PipelineCall
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode

/**
 * Grant — the permission resolved by the provider interceptor must be present in the run's grants. Missing
 * grants are `PERMISSION_DENIED` with reason `CAPABILITY_NOT_GRANTED` and the review fix action, so the UI can
 * route the person straight to the permission review.
 */
class GrantInterceptor : BrokerInterceptor {

    override suspend fun intercept(call: PipelineCall, next: suspend (PipelineCall) -> Envelope): Envelope {
        val ctx = call.context ?: return Envelope.err(ErrorCode.INTERNAL, "call context missing before Grant")
        val permission = call.permission
            ?: return Envelope.err(ErrorCode.INTERNAL, "permission not resolved before Grant")
        if (ctx.grants.containsKey(permission)) return next(call)
        return Envelope.err(
            ErrorCode.PERMISSION_DENIED,
            "This run was not granted '$permission' (capability '${call.capability}').",
            reason = "CAPABILITY_NOT_GRANTED",
            fixAction = "OPEN_PERMISSION_REVIEW",
        )
    }
}
