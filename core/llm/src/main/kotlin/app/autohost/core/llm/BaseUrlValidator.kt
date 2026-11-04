// Adapted from closepaw llm/OtherBaseUrlValidator.kt (Apache-2.0, Copyright 2026 ClosePaw Authors).
// Changes: BuildConfig.DEBUG replaced by the explicit allowLoopbackCleartext/debug inputs fed by the
// ai_cleartext_loopback switch; typed Verdict instead of kotlin.Result; no-echo enforced by the
// type; emulator host alias 10.0.2.2 on debug; vectors in testing/golden/llm_url_vectors.json
// (docs/REUSE_MATRIX.md RM-D1, P13-03).
package app.autohost.core.llm

/**
 * P13-03 (ADR-090, `docs/MODEL_GATEWAY.md` §4): the pure URL policy for provider base URLs.
 * Validated on EVERY gateway call (the cleartext switch can change between calls).
 *
 * Rules: https always allowed; http only for loopback (127.0.0.1/localhost) when the
 * `ai_cleartext_loopback` switch is on — or `10.0.2.2` additionally on a debug build (emulator
 * host alias); any other http host INCLUDING LAN IPs is refused (reach a PC-hosted model with
 * `adb reverse`); userinfo/query/fragment are refused; error messages NEVER echo the rejected
 * segment (it may contain a pasted key). No trust-all TrustManager, no HostnameVerifier
 * override, no pinning in v1. Redirects are never followed (the engine sets it).
 */
object BaseUrlValidator {

    enum class Reason { MALFORMED, CLEARTEXT_NOT_ALLOWED }

    sealed class Verdict {
        data class Accepted(val baseUrl: String) : Verdict() // trailing '/' trimmed; endpoint = base + /chat/completions

        data class Rejected(val reason: Reason) : Verdict()
    }

    data class UrlEnv(val cleartextLoopbackEnabled: Boolean, val debugBuild: Boolean)

    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost")
    private const val EMULATOR_HOST_ALIAS = "10.0.2.2"

    fun validate(raw: String, env: UrlEnv): Verdict {
        // Whitespace/control characters anywhere in the URL: refused before parsing (a pasted
        // key with a trailing newline must not slip through a "trim").
        if (raw.isBlank() || raw.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) {
            return Verdict.Rejected(Reason.MALFORMED)
        }
        val parsed = try {
            java.net.URI(raw)
        } catch (e: Exception) {
            return Verdict.Rejected(Reason.MALFORMED)
        }
        if (!parsed.isAbsolute) return Verdict.Rejected(Reason.MALFORMED)
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return Verdict.Rejected(Reason.MALFORMED)
        val host = parsed.host
        if (host.isNullOrBlank()) return Verdict.Rejected(Reason.MALFORMED)
        val port = parsed.port // -1 = default for the scheme
        if (port != -1 && (port < 1 || port > 65535)) return Verdict.Rejected(Reason.MALFORMED)
        if (parsed.userInfo != null) return Verdict.Rejected(Reason.MALFORMED)
        if (!parsed.query.isNullOrEmpty() || !parsed.fragment.isNullOrEmpty()) {
            return Verdict.Rejected(Reason.MALFORMED)
        }
        // Punycode/IDN hosts: the ASCII form must be a plain hostname (no underscores etc. are
        // already covered by the URI parser); anything that is not a name/IP literal is refused.
        if (raw.contains("@")) return Verdict.Rejected(Reason.MALFORMED)
        when (scheme) {
            "https" -> Unit // always allowed
            "http" -> {
                val loopbackOk = host.lowercase() in LOOPBACK_HOSTS ||
                    (host == EMULATOR_HOST_ALIAS && env.debugBuild)
                if (!loopbackOk || !env.cleartextLoopbackEnabled) {
                    return Verdict.Rejected(Reason.CLEARTEXT_NOT_ALLOWED)
                }
            }
        }
        val base = raw.trimEnd('/')
        return Verdict.Accepted(base)
    }

    /** The endpoint URL for a base (`/chat/completions`). */
    fun chatEndpoint(baseUrl: String): String = baseUrl.trimEnd('/') + "/chat/completions"
}
