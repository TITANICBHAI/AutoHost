package app.autohost.core.broker.interceptor

import app.autohost.core.broker.BrokerInterceptor
import app.autohost.core.broker.CallContext
import app.autohost.core.broker.PipelineCall
import app.autohost.core.broker.RunTokenStore
import app.autohost.core.common.Clock
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode

/**
 * Auth — resolves the run token into a [CallContext]. An unknown, revoked or malformed token stops the call
 * here with `PERMISSION_DENIED` / reason `UNKNOWN_RUN_TOKEN` (fail closed; never guess identity).
 */
class AuthInterceptor(
    private val tokens: RunTokenStore,
    private val clock: Clock,
) : BrokerInterceptor {

    override suspend fun intercept(call: PipelineCall, next: suspend (PipelineCall) -> Envelope): Envelope {
        // X-13 (ADR-099): malformed tokens are refused before any lookup, with a message that
        // never echoes the rejected token (the token must not leak into logs or envelopes).
        if (!tokens.isWellFormed(call.token)) {
            return Envelope.err(
                ErrorCode.PERMISSION_DENIED,
                "This run's token is malformed; restart the run from the host.",
                reason = "MALFORMED_RUN_TOKEN",
            )
        }
        val grants = tokens.resolve(call.token, clock.nowMs())
            ?: return Envelope.err(
                ErrorCode.PERMISSION_DENIED,
                "This run's token is not valid any more; restart the run from the host.",
                reason = "UNKNOWN_RUN_TOKEN",
            )
        call.context = CallContext(
            runId = grants.runId,
            taskId = grants.taskId,
            profileId = grants.profileId,
            grants = grants.grants,
            nowMs = clock.nowMs(),
        )
        return next(call)
    }
}
