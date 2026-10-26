package app.autohost.core.broker

import app.autohost.core.model.BrokerEvent
import app.autohost.core.model.Envelope
import kotlinx.serialization.json.JsonObject

/**
 * One capability call travelling through the interceptor chain. Interceptors mutate [context], [provider] and
 * [permission] as the call progresses toward the terminal provider invoke.
 */
class PipelineCall(
    val token: String,
    val capability: String,
    val method: String,
    val args: JsonObject,
    val startedMonotonicMs: Long,
    var permission: String? = null,
    var context: CallContext? = null,
    var provider: CapabilityProvider? = null,
)

/** One link of the broker chain; [next] runs the rest of the chain (OkHttp style). */
fun interface BrokerInterceptor {
    suspend fun intercept(call: PipelineCall, next: suspend (PipelineCall) -> Envelope): Envelope
}

/**
 * Runs the interceptor chain, outermost first. Order for wave 1 (HANDOFF §3):
 * Audit → Auth → Provider → Grant → Scope → provider.invoke. Later phases insert insecure-feature,
 * rate-limit and recorder links between Scope and the provider.
 */
class BrokerPipeline(private val interceptors: List<BrokerInterceptor>) {

    suspend fun execute(call: PipelineCall, terminal: suspend (PipelineCall) -> Envelope): Envelope {
        var index = 0
        suspend fun runNext(current: PipelineCall): Envelope {
            if (index < interceptors.size) {
                val interceptor = interceptors[index++]
                return interceptor.intercept(current, ::runNext)
            }
            return terminal(current)
        }
        return runNext(call)
    }
}

/** Where the run's fire-and-forget events land (the engine's orchestrator implements this). */
fun interface EventSink {
    fun onEvent(runToken: String, event: BrokerEvent)
}
