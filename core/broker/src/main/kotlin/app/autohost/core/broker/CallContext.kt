package app.autohost.core.broker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The identity and authority of one capability call. Built by `AuthInterceptor` from the run token's grants and
 * carried through the pipeline. Grants map permission id → grant object; only the `packages` key matters today.
 */
class CallContext(
    val runId: String,
    val taskId: String,
    val profileId: String?,
    val grants: Map<String, JsonObject>,
    val nowMs: Long,
) {
    /**
     * Granted packages for [permission]:
     *  - null  → unrestricted (no grant object, no `packages` key, or the literal `"*"`);
     *  - else  → the exact package set; anything outside is a `SCOPE_VIOLATION`.
     */
    fun scopePackages(permission: String): Set<String>? {
        val grant = grants[permission] ?: return null
        return grant["packages"]?.asPackageSet()
    }

    private fun JsonElement.asPackageSet(): Set<String>? = when (this) {
        is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        is JsonPrimitive -> if (content == "*") null else setOf(content)
        else -> null
    }
}

/** Grants bound into a run token. */
class RunGrants(
    val runId: String,
    val taskId: String,
    val profileId: String?,
    val grants: Map<String, JsonObject>,
    val issuedMs: Long = 0L,
)
