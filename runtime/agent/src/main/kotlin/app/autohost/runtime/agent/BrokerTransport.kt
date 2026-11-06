package app.autohost.runtime.agent

import kotlinx.serialization.json.JsonObject

/**
 * P13-11 (AGENT_RUNTIME §3/§10): THE one action path. Every model-initiated call — including the
 * approval ask itself (`ui.prompt.ask`) — reaches the device through this seam and nothing else.
 * The production implementation is wired in :app beside the flow runtime (P13-12 decides whether
 * `FlowBinderTransport` is lifted into `:runtime:api` or duplicated — the choice is RECORDED
 * there); the tests script it.
 *
 * Contract: [call] returns the broker Envelope JSON (`{ok, result?}` / `{ok:false, error?}`);
 * cancellation propagates; the transport NEVER widens the call's permission or args.
 */
fun interface BrokerTransport {
    suspend fun call(permission: String, method: String, args: JsonObject): JsonObject
}
