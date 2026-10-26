package app.autohost.core.broker

import app.autohost.core.broker.interceptor.AuditInterceptor
import app.autohost.core.broker.interceptor.AuthInterceptor
import app.autohost.core.broker.interceptor.GrantInterceptor
import app.autohost.core.broker.interceptor.ProviderInterceptor
import app.autohost.core.broker.interceptor.ScopeInterceptor
import app.autohost.core.common.Clock
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The broker service: the single entry point for capability calls. Owns the pipeline and guarantees that
 * *nothing throws across the boundary* — every outcome is an [Envelope]. [BrokerBinder] delegates here.
 *
 * Interceptor order, outermost first (HANDOFF §3): Audit → Auth → Provider → Grant → Scope → provider.invoke.
 * Extra app-registered interceptors (slow motion, tracing, fault injection) run after Scope.
 */
class BrokerService(
    val registry: CapabilityRegistry,
    val tokens: RunTokenStore,
    private val env: DeviceEnv,
    private val clock: Clock,
    auditSink: app.autohost.core.broker.interceptor.AuditSink,
    interceptors: List<BrokerInterceptor> = emptyList(),
) {
    private val pipeline = BrokerPipeline(
        listOf(
            AuditInterceptor(auditSink, clock),
            AuthInterceptor(tokens, clock),
            ProviderInterceptor(registry, env),
            GrantInterceptor(),
            ScopeInterceptor(),
        ) + interceptors,
    )

    suspend fun call(token: String, capability: String, method: String, args: JsonObject): Envelope {
        val call = PipelineCall(
            token = token,
            capability = capability,
            method = method,
            args = args,
            startedMonotonicMs = clock.monotonicMs(),
        )
        return try {
            withContext(Dispatchers.Default) {
                pipeline.execute(call) { c ->
                    val provider = c.provider
                        ?: return@execute Envelope.err(ErrorCode.INTERNAL, "no provider selected")
                    provider.invoke(c.context ?: return@execute Envelope.err(ErrorCode.INTERNAL, "no call context"), c.method, c.args)
                }
            }
        } catch (e: Throwable) {
            Envelope.err(ErrorCode.INTERNAL, "Broker internal error in $capability.$method: ${e.javaClass.simpleName}")
        }
    }

    /**
     * describe(runToken): granted capabilities + live availability, for `autohost.capabilities()`. Shape:
     * `{"capabilities": {<capabilityId>: {"tier","available","reason"?,"fix_action"?,"provider"?}}}`.
     * A token that does not resolve gets an empty set (fail closed — no capability leak).
     */
    fun describe(token: String): JsonObject {
        if (tokens.resolve(token) == null) return buildJsonObject { putJsonObject("capabilities") { } }
        val capabilities = buildJsonObject {
            for (capabilityId in registry.capabilityIds().sorted()) {
                val candidates = registry.providers(capabilityId).map { provider ->
                    provider to provider.availability(env)
                }
                val best = candidates.firstOrNull { it.second is Availability.Available }
                    ?: candidates.firstOrNull { it.second is Availability.NeedsSetup }
                    ?: candidates.firstOrNull { it.second is Availability.Unsupported }
                putJsonObject(capabilityId) {
                    put("tier", best?.first?.tier ?: "")
                    put("provider", best?.first?.providerId ?: "")
                    when (val availability = best?.second) {
                        is Availability.Available -> put("available", true)
                        is Availability.NeedsSetup -> {
                            put("available", false)
                            put("reason", availability.reason)
                            put("fix_action", availability.fixAction)
                        }
                        is Availability.Unsupported -> {
                            put("available", false)
                            put("reason", availability.reason)
                        }
                        null -> {
                            put("available", false)
                            put("reason", "NO_PROVIDER")
                        }
                    }
                }
            }
        }
        return buildJsonObject { put("capabilities", capabilities) }
    }
}
