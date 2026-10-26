package app.autohost.capabilities.browser

import java.net.URI

/**
 * P10-02: pure browser policy (ADR-070) — the `browser.webview` gate chain, shared by every
 * browser provider so the a11y and cdp links cannot drift looser than the webview one.
 *
 * Grant scope mirrors NetPolicy semantics exactly (`{ "domains": ["example.com", "*.example.org"] }`):
 * null grant object → unrestricted within the permission; empty list → explicit deny-all;
 * `*.suffix` matches one leading label and all deeper ones, never the bare suffix, never a
 * lookalike like `evilexample.org`.
 *
 * URL rules (plan §6.1 browser row: "No stealth features"):
 *  - http/https only for web pages; the `task:` scheme addresses a file inside the *calling task's
 *    own extracted bundle* (`task:web/login.html`) so Gate 10-a can use a fully local page without
 *    granting any network at all. `..` segments and absolute escapes are refused (fail closed).
 *  - `file:` and everything else → Denied. There is no user-agent, viewport or locale spoofing
 *    knob anywhere in the module by construction.
 */
object BrowserPolicy {

    const val MAX_PAGES = 4
    const val DEFAULT_TIMEOUT_MS: Long = 20_000
    const val MAX_TIMEOUT_MS: Long = 120_000
    const val MAX_EXTRACT_BYTES = 2 * 1024 * 1024

    sealed class UrlVerdict {
        /** Normalized target: either the http(s) URL or the resolved bundle relative path. */
        data class Allowed(val kind: Kind, val target: String) : UrlVerdict() {
            enum class Kind { WEB, TASK_FILE }
        }

        data class Denied(val detail: String) : UrlVerdict()
        data object NoGrant : UrlVerdict()
    }

    fun urlVerdict(grant: kotlinx.serialization.json.JsonObject?, url: String): UrlVerdict {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return UrlVerdict.Denied("url is empty")
        val scheme = runCatching { URI(trimmed).scheme?.lowercase() }.getOrNull()
        return when (scheme) {
            "http", "https" -> webVerdict(grant, trimmed)
            "task" -> taskFileVerdict(trimmed)
            else -> UrlVerdict.Denied("only http, https and task: URLs are allowed (got '$scheme')")
        }
    }

    private fun webVerdict(grant: kotlinx.serialization.json.JsonObject?, url: String): UrlVerdict {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
            ?: return UrlVerdict.Denied("URL has no host")
        if (grant == null) return UrlVerdict.Allowed(UrlVerdict.Kind.WEB, url)
        val domains = (grant["domains"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: return UrlVerdict.NoGrant
        if (domains.isEmpty()) return UrlVerdict.Denied(host)
        for (pattern in domains) {
            if (pattern == "*") return UrlVerdict.Allowed(UrlVerdict.Kind.WEB, url)
            if (pattern.startsWith("*.")) {
                val suffix = pattern.removePrefix("*.")
                if (host == suffix || host.endsWith(".$suffix")) {
                    return UrlVerdict.Allowed(UrlVerdict.Kind.WEB, url)
                }
            } else if (host == pattern) {
                return UrlVerdict.Allowed(UrlVerdict.Kind.WEB, url)
            }
        }
        return UrlVerdict.Denied(host)
    }

    /** `task:<relpath>` — resolved by the host against the calling task's bundle directory. */
    private fun taskFileVerdict(url: String): UrlVerdict {
        val rel = url.removePrefix("task:").removePrefix("//")
        if (rel.isBlank()) return UrlVerdict.Denied("task: URL needs a relative path")
        if (rel.contains('\\')) return UrlVerdict.Denied("task: paths use '/' separators")
        if (rel.contains('\u0000')) return UrlVerdict.Denied("task: path contains NUL")
        val parts = rel.split('/')
        if (parts.any { it == "." || it == ".." || it.isEmpty() }) {
            return UrlVerdict.Denied("task: paths must stay inside the task bundle (no '.', '..' or empty segments)")
        }
        if (rel.length > 512) return UrlVerdict.Denied("task: path too long")
        return UrlVerdict.Allowed(UrlVerdict.Kind.TASK_FILE, rel)
    }

    fun timeoutMs(requested: Long?): Long = (requested ?: DEFAULT_TIMEOUT_MS).coerceIn(1, MAX_TIMEOUT_MS)

    /**
     * The one open/read permission split, shared by all three browser providers:
     * acting (open/navigate/click/type/clear_cookies/close/pages) needs `browser.open`; reading
     * page content (extract/cookies) needs `browser.read`. This predicate is the *minimum*
     * enforcement; providers may not narrow it further.
     */
    fun permissionFor(method: String): String? = when (method) {
        "extract", "cookies" -> "browser.read"
        "open", "navigate", "click", "type", "clear_cookies", "close", "pages" -> "browser.open"
        else -> null
    }
}
