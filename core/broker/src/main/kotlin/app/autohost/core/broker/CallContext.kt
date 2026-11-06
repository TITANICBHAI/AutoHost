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

    /**
     * P12-16 (A8): a scope FLAG from the grant object (e.g. `scope.allow_coordinates`):
     *  - null → the grant is unrestricted (no grant object or the literal `"*"`) — the flags do
     *    not bind (dev-mode grant-all keeps its pre-ADR-098 behaviour);
     *  - else → the flag's boolean; ABSENT means **false** — an explicit grant without the flag
     *    refuses the flagged behaviour (fail closed, the A8 rule).
     */
    fun scopeFlag(permission: String, flag: String): Boolean? {
        val grant = grants[permission] ?: return null
        val packages = grant["packages"]
        val unrestricted = when (packages) {
            null -> true
            is JsonPrimitive -> packages.content == "*"
            else -> false
        }
        if (unrestricted) return null
        return (grant[flag] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
    }

    private fun JsonElement.asPackageSet(): Set<String>? = when (this) {
        is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        is JsonPrimitive -> if (content == "*") null else setOf(content)
        else -> null
    }
}

/**
 * Grants bound into a run token (X-13 / ADR-099: the token is a bearer capability bound to one
 * run, attempt and grant snapshot, with an expiry the broker decides from its own state).
 */
class RunGrants(
    val runId: String,
    val taskId: String,
    val profileId: String?,
    val grants: Map<String, JsonObject>,
    val issuedMs: Long = 0L,
    /** The attempt this token serves; a resume mints a fresh token for the new attempt. */
    val attempt: Int = 1,
    /** Wall-clock deadline; `0` = no expiry (tests). The broker refuses expired tokens. */
    val expiresAtMs: Long = 0L,
)
