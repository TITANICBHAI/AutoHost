package app.autohost.capabilities.net

/**
 * P8-08 (+ the W1 net.http first-build gap): pure network policy — domain allowlist matching,
 * URL validation and size caps shared by `net.http` and `net.ws` (plan §6.1: "Requests with
 * domain allowlist, size caps, header redaction").
 *
 * The grant scope for both capabilities is `{ "domains": ["example.com", "*.example.org"] }`:
 * - null grant → unrestricted (the run holds the permission with no scope object);
 * - empty list → nothing allowed (explicit deny);
 * - `*.suffix` matches one leading label and all deeper ones (`*.example.org` covers
 *   `a.b.example.org` but never `example.org` itself, and never an attacker domain like
 *   `evilexample.org`).
 */
object NetPolicy {

    const val DEFAULT_MAX_BYTES = 5 * 1024 * 1024 // 5 MB per response
    const val MAX_TIMEOUT_MS = 120_000

    sealed class DomainVerdict {
        data object Allowed : DomainVerdict()
        data class Denied(val host: String) : DomainVerdict()
        data object NoGrant : DomainVerdict()
    }

    fun domainVerdict(grant: kotlinx.serialization.json.JsonObject?, url: String): DomainVerdict {
        val host = hostOf(url) ?: return DomainVerdict.Denied("")
        if (grant == null) return DomainVerdict.Allowed
        val domains = (grant["domains"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: return DomainVerdict.NoGrant
        if (domains.isEmpty()) return DomainVerdict.Denied(host)
        for (pattern in domains) {
            if (pattern == "*") return DomainVerdict.Allowed
            if (pattern.startsWith("*.")) {
                val suffix = pattern.removePrefix("*.")
                if (host == suffix || host.endsWith(".$suffix")) return DomainVerdict.Allowed
            } else if (host == pattern) {
                return DomainVerdict.Allowed
            }
        }
        return DomainVerdict.Denied(host)
    }

    /** Host of an http(s)/ws(s) URL; null for anything else (fail closed). */
    fun hostOf(url: String): String? = runCatching {
        val uri = java.net.URI(url)
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in setOf("http", "https", "ws", "wss")) return null
        uri.host?.lowercase()
    }.getOrNull()

    /** ws/wss URLs are only for net.ws; http/https only for net.http. */
    fun schemeAllowed(url: String, websocket: Boolean): Boolean {
        val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull() ?: return false
        return if (websocket) scheme in setOf("ws", "wss") else scheme in setOf("http", "https")
    }

    fun timeoutMs(requested: Long?): Long = (requested ?: 30_000L).coerceIn(1, MAX_TIMEOUT_MS.toLong())

    /** Header redaction before anything is logged or recorded (plan §6.3 step 7). */
    fun redactHeaders(headers: Map<String, String>): Map<String, String> = headers.mapValues { (name, value) ->
        if (name.lowercase() in SENSITIVE_HEADERS) "«redacted»" else value
    }

    val SENSITIVE_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key")
}
