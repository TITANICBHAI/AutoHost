package app.autohost.core.broker

import app.autohost.core.common.Ids
import java.util.concurrent.ConcurrentHashMap

/**
 * Issues and resolves run tokens (32 random bytes, hex). The token is the only credential a worker carries;
 * `resolve` never throws, an unknown/expired/revoked token simply resolves to null (fail closed upstream).
 * Tokens are revoked when the run reaches a terminal state (RunOrchestrator) or explicitly (kill switch).
 */
class RunTokenStore {
    private val tokens = ConcurrentHashMap<String, RunGrants>()

    fun issue(grants: RunGrants): String {
        val token = Ids.hexToken(32)
        tokens[token] = grants
        return token
    }

    fun resolve(token: String): RunGrants? = tokens[token]

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
