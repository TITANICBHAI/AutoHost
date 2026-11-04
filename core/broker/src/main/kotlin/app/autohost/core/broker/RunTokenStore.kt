package app.autohost.core.broker

import app.autohost.core.common.Ids
import java.util.concurrent.ConcurrentHashMap

/**
 * Issues and resolves run tokens (32 random bytes, hex). The token is the only credential a worker carries;
 * `resolve` never throws, an unknown/expired/revoked token simply resolves to null (fail closed upstream).
 * Tokens are revoked when the run reaches a terminal state (RunOrchestrator) or explicitly (kill switch).
 *
 * X-13 (ADR-099): the token is a bearer capability — unpredictable (256-bit random), bound to one
 * run, attempt and grant snapshot, and carrying an expiry the broker enforces from its own state.
 * Malformed tokens are refused before any lookup; resolution never trusts claims the worker sends.
 */
class RunTokenStore {
    private val tokens = ConcurrentHashMap<String, RunGrants>()

    fun issue(grants: RunGrants): String {
        val token = Ids.hexToken(32)
        tokens[token] = grants
        return token
    }

    /** A token is 64 lowercase hex chars (32 bytes). Malformed tokens are refused before lookup. */
    fun isWellFormed(token: String): Boolean =
        token.length == 64 && token.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * Strict resolve (X-13): refuses malformed tokens and expired grants. Revoked/unknown tokens
     * miss the map and also resolve to null — the caller cannot distinguish theft from revocation.
     * The default `nowMs = 0` skips the expiry check (legacy callers); the broker gate passes the
     * clock and enforces the deadline.
     */
    fun resolve(token: String, nowMs: Long = 0L): RunGrants? {
        if (!isWellFormed(token)) return null
        val grants = tokens[token] ?: return null
        if (grants.expiresAtMs in 1..nowMs) return null // expired (0 = no expiry; tests)
        return grants
    }

    /** Expiry-ignoring resolve for display/audit paths — never the broker gate. */
    fun resolveIgnoreExpiry(token: String): RunGrants? = tokens[token]

    fun revoke(token: String) {
        tokens.remove(token)
    }

    /** Revoke every token belonging to a run (kill switch, finish). Returns how many were dropped. */
    fun revokeRun(runId: String): Int {
        var count = 0
        val iterator = tokens.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.runId == runId) {
                iterator.remove()
                count++
            }
        }
        return count
    }

    fun revokeAll() = tokens.clear()

    fun size(): Int = tokens.size
}
