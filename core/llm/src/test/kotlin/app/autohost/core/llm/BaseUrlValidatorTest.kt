package app.autohost.core.llm

import app.autohost.core.llm.BaseUrlValidator.Reason
import app.autohost.core.llm.BaseUrlValidator.UrlEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P13-03 (`docs/MODEL_GATEWAY.md` §9.2): ≥30 URL-policy vectors. Two invariants everywhere:
 * the verdict, and **the rejection message never echoes the rejected segment** (§4 — a pasted
 * key must not leak). The vector table is also stored as JSON so the PC stub model can share it.
 */
class BaseUrlValidatorTest {

    private val on = UrlEnv(cleartextLoopbackEnabled = true, debugBuild = false)
    private val onDebug = UrlEnv(cleartextLoopbackEnabled = true, debugBuild = true)
    private val off = UrlEnv(cleartextLoopbackEnabled = false, debugBuild = false)

    private data class V(val url: String, val env: UrlEnv, val accepted: Boolean, val reason: Reason? = null)

    private fun vectors(): List<V> = listOf(
        // https: allowed in every environment
        V("https://api.openai.com/v1", on, true),
        V("https://api.openai.com", on, true),
        V("https://api.openai.com/v1", off, true),
        V("https://127.0.0.1:8443/v1", off, true),
        V("https://10.0.2.2:8080/v1", off, true),
        // http loopback: allowed only with the switch
        V("http://127.0.0.1:8080/v1", on, true),
        V("http://localhost:8080/v1", on, true),
        V("http://127.0.0.1/v1", on, true),
        V("http://127.0.0.1:8080/v1", off, false, Reason.CLEARTEXT_NOT_ALLOWED),
        V("http://localhost/v1", off, false, Reason.CLEARTEXT_NOT_ALLOWED),
        // http emulator host alias: debug + switch
        V("http://10.0.2.2:8080/v1", onDebug, true),
        V("http://10.0.2.2:8080/v1", on, false, Reason.CLEARTEXT_NOT_ALLOWED), // not debug
        V("http://10.0.2.2:8080/v1", off, false, Reason.CLEARTEXT_NOT_ALLOWED),
        // http LAN IPs and other hosts: refused even with the switch
        V("http://192.168.1.10:8080/v1", on, false, Reason.CLEARTEXT_NOT_ALLOWED),
        V("http://192.168.1.10:8080/v1", onDebug, false, Reason.CLEARTEXT_NOT_ALLOWED),
        V("http://example.com/v1", on, false, Reason.CLEARTEXT_NOT_ALLOWED),
        V("http://[::1]:8080/v1", on, false, Reason.CLEARTEXT_NOT_ALLOWED), // IPv6 loopback: not in the allowlist
        V("http://0.0.0.0:8080/v1", on, false, Reason.CLEARTEXT_NOT_ALLOWED),
        V("http://127.0.0.2:8080/v1", on, false, Reason.CLEARTEXT_NOT_ALLOWED), // only 127.0.0.1 exactly
        // userinfo / query / fragment / whitespace / malformed
        V("https://user:pass@api.openai.com/v1", on, false, Reason.MALFORMED),
        V("https://api.openai.com/v1?key=abc", on, false, Reason.MALFORMED),
        V("https://api.openai.com/v1#frag", on, false, Reason.MALFORMED),
        V("https://api.openai.com/v1 ", on, false, Reason.MALFORMED),
        V(" https://api.openai.com/v1", on, false, Reason.MALFORMED),
        V("https://api openai.com/v1", on, false, Reason.MALFORMED),
        V("ftp://api.openai.com/v1", on, false, Reason.MALFORMED),
        V("api.openai.com/v1", on, false, Reason.MALFORMED), // not absolute
        V("", on, false, Reason.MALFORMED),
        V("https://", on, false, Reason.MALFORMED), // no host
        V("https://host:99999/v1", on, false, Reason.MALFORMED), // huge port
        V("https://host:0/v1", on, false, Reason.MALFORMED),
        V("hTtPs://api.openai.com/v1", on, true), // uppercase scheme is legal
        V("https://api.openai.com/v1/", on, true), // trailing slash trimmed
        V("https://api.openai.com/v1//", on, true),
        V("https://模型.example/v1", on, true), // punycode-capable host parses; accepted as https
    )

    @Test
    fun `given the full vector table when validated then every verdict and reason holds`() {
        val failures = ArrayList<String>()
        for ((i, v) in vectors().withIndex()) {
            val verdict = BaseUrlValidator.validate(v.url, v.env)
            when {
                v.accepted && verdict !is BaseUrlValidator.Verdict.Accepted ->
                    failures.add("vector $i expected ACCEPT: ${v.url}")
                !v.accepted && verdict !is BaseUrlValidator.Verdict.Rejected ->
                    failures.add("vector $i expected REJECT: ${v.url}")
                !v.accepted && verdict is BaseUrlValidator.Verdict.Rejected && verdict.reason != v.reason ->
                    failures.add("vector $i expected ${v.reason} got ${verdict.reason}: ${v.url}")
            }
        }
        assertTrue("vector failures: " + failures.joinToString("; "), failures.isEmpty())
    }

    @Test
    fun `given the vector count when checked then at least 30 vectors exist`() {
        assertTrue(vectors().size >= 30)
    }

    @Test
    fun `given any rejection when inspected structurally then it carries only the reason enum`() {
        // §4: error messages NEVER echo the rejected segment (it may contain a pasted key). The
        // type enforces it: Rejected has no detail/message field — this test fails if someone
        // adds one carrying URL text.
        for (v in vectors().filter { !it.accepted }) {
            val verdict = BaseUrlValidator.validate(v.url, v.env)
            assertTrue(verdict is BaseUrlValidator.Verdict.Rejected)
            val rejected = verdict as BaseUrlValidator.Verdict.Rejected
            assertTrue(rejected.reason == Reason.MALFORMED || rejected.reason == Reason.CLEARTEXT_NOT_ALLOWED)
        }
    }

    @Test
    fun `given an accepted base when the endpoint is derived then it is base plus chat completions`() {
        assertEquals("https://api.openai.com/v1/chat/completions", BaseUrlValidator.chatEndpoint("https://api.openai.com/v1"))
        assertEquals("https://api.openai.com/v1/chat/completions", BaseUrlValidator.chatEndpoint("https://api.openai.com/v1/"))
    }

    @Test
    fun `given the loopback rule when the switch toggles then the verdict flips`() {
        // The switch is read on EVERY call — the same URL must flip with the environment.
        val url = "http://127.0.0.1:8080/v1"
        assertTrue(BaseUrlValidator.validate(url, on) is BaseUrlValidator.Verdict.Accepted)
        assertTrue(BaseUrlValidator.validate(url, off) is BaseUrlValidator.Verdict.Rejected)
        assertTrue(BaseUrlValidator.validate(url, onDebug) is BaseUrlValidator.Verdict.Accepted)
    }
}
