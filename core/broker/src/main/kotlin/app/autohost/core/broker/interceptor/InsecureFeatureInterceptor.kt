package app.autohost.core.broker.interceptor

import app.autohost.core.broker.BrokerInterceptor
import app.autohost.core.broker.PipelineCall
import app.autohost.core.broker.security.InsecureFeatures
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P8-13: the second half of the two-level gate (plan §14.5) — sits between Scope and the provider,
 * exactly where the pipeline comment reserved a slot for it. Every call whose capability family
 * maps to an insecure feature must see that feature's switch ON (or relaxed mode minus denies),
 * otherwise the call fails with `INSECURE_FEATURE_DISABLED`, reason `DISABLED_BY_SETTING` and the
 * `OPEN_INSECURE_SWITCH` fix action so the UI can route straight to the switch.
 *
 * The state is injected as a suspending lambda — the app layer answers from a volatile snapshot,
 * so the hot path never touches the DB.
 */
class InsecureFeatureInterceptor(
    private val state: suspend () -> InsecureFeatures.State,
) : BrokerInterceptor {

    override suspend fun intercept(call: PipelineCall, next: suspend (PipelineCall) -> Envelope): Envelope {
        val decision = InsecureFeatures.evaluate(call.capability, call.permission, state())
        return when (decision) {
            is InsecureFeatures.Decision.Allowed -> next(call)
            is InsecureFeatures.Decision.Disabled -> Envelope.err(
                app.autohost.core.model.AhError.of(
                    ErrorCode.INSECURE_FEATURE_DISABLED,
                    "'${call.capability}' is a high-risk capability and the '${
                        decision.feature
                    }' switch is off. Enable it in Settings → Developer & security after reviewing what it unlocks.",
                    reason = "DISABLED_BY_SETTING",
                    fixAction = "OPEN_INSECURE_SWITCH",
                    details = buildJsonObject {
                        put("feature", decision.feature)
                        put("capability", call.capability)
                    },
                ),
            )
        }
    }
}
