package app.autohost.core.broker.interceptor

import app.autohost.core.broker.BrokerInterceptor
import app.autohost.core.broker.PipelineCall
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Scope — a `package`/`packages` target in the args must be inside the packages granted for the call's
 * permission. Unrestricted grants (null) pass everything; restricted grants with no target in the args pass
 * (the target will be enforced at read time by the provider, e.g. UI windows are filtered by granted packages).
 */
class ScopeInterceptor : BrokerInterceptor {

    override suspend fun intercept(call: PipelineCall, next: suspend (PipelineCall) -> Envelope): Envelope {
        val ctx = call.context ?: return Envelope.err(ErrorCode.INTERNAL, "call context missing before Scope")
        val permission = call.permission ?: return next(call)
        val allowed = ctx.scopePackages(permission) ?: return next(call)
        for (target in ScopeMatcher.targetPackages(call.args)) {
            if (target == "*") {
                return Envelope.err(
                    ErrorCode.SCOPE_VIOLATION,
                    "This run may only act inside granted packages; '*' is not allowed.",
                    reason = "SCOPE_NOT_GRANTED",
                )
            }
            if (target !in allowed) {
                return Envelope.err(
                    ErrorCode.SCOPE_VIOLATION,
                    "Package '$target' is outside the packages granted to this run.",
                    reason = "SCOPE_NOT_GRANTED",
                    fixAction = "OPEN_PERMISSION_REVIEW",
                )
            }
        }
        return next(call)
    }
}

/** Extracts package targets from call args. Pure and unit-tested. */
object ScopeMatcher {
    fun targetPackages(args: JsonObject): Set<String> {
        val targets = LinkedHashSet<String>()
        args["package"]?.let { if (it is JsonPrimitive) targets.add(it.content) }
        args["packages"]?.let { if (it is JsonArray) it.forEach { e -> if (e is JsonPrimitive) targets.add(e.content) } }
        (args["scope"] as? JsonObject)?.let { scope ->
            scope["package"]?.let { if (it is JsonPrimitive) targets.add(it.content) }
            scope["packages"]?.let { if (it is JsonArray) it.forEach { e -> if (e is JsonPrimitive) targets.add(e.content) } }
        }
        return targets
    }
}
