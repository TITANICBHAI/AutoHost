package app.autohost.core.llm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P13-07 (`docs/MODEL_GATEWAY.md` §9.6): gateway integration over a `FakeEngine` — auth failure,
 * rate-limit exhaustion, context-overflow mapping, the not-configured paths, the egress refusal,
 * and the §9.5 secret canary (the key appears ONLY in the Authorization header the engine sees —
 * never in any exception, audit record or error text). Under D-26 these are written, not run.
 */
class LlmGatewayTest {

    private val key = "sk-CANARY-0123456789abcdef"
    private val profile = ProviderProfile(
        id = "prov-1",
        label = "Test",
        baseUrl = "http://127.0.0.1:9/v1", // no server: any network path would fail, not hang
        model = "test-model",
        egressAcceptedAtMs = 1_000L,
    )

    private class FakeEngine(var status: Int = 200, var body: String = "{}") : LlmHttpEngine {
        val seenAuthorization: MutableList<String?> = mutableListOf()
        val seenUrls: MutableList<String> = mutableListOf()
        var calls: Int = 0

        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int): HttpResponse {
            calls += 1
            seenUrls.add(url)
            seenAuthorization.add(headers["Authorization"])
            return HttpResponse(status, emptyMap(), this.body) // `this.body`: the String the test configured, not the request bytes
        }
    }

    // inner: it reads the outer test's `profile` and `key`
    private inner class FakeProfiles : ProfileStore {
        var registered: MutableList<String> = mutableListOf()
        override fun load(): List<ProviderProfile> = listOf(profile)
        override fun save(profiles: List<ProviderProfile>) = Unit
        override fun readApiKey(profileId: String): String? = if (profileId == "prov-1") key else null
        override fun registerKeyForRedaction(value: String) {
            registered.add(value)
        }
    }

    private class RecordingAudit : LlmGateway.AuditSink {
        val events: MutableList<LlmGateway.AuditEvent> = mutableListOf()
        override fun record(event: LlmGateway.AuditEvent) {
            events.add(event)
        }
    }

    private fun gateway(engine: FakeEngine, profiles: ProfileStore = FakeProfiles(), now: Long = 1_000L): Pair<LlmGateway, RecordingAudit> {
        val audit = RecordingAudit()
        return LlmGateway(profiles, engine, audit, nowMs = { now }, urlEnv = {
            BaseUrlValidator.UrlEnv(cleartextLoopbackEnabled = true, debugBuild = false)
        }) to audit
    }

    private val request = ChatRequest("test-model", listOf(ChatMessage.User("goal")), maxOutputTokens = 64)

    @Test
    fun `given a 401 when called then MODEL_AUTH and one audit record with sizes only`() = runBlocking {
        val (gw, audit) = gateway(FakeEngine(status = 401))
        val result = gw.chat("prov-1", request, deadlineMs = 30_000)
        assertEquals(LlmError.Auth, result.errorOrNull())
        assertEquals(1, audit.events.size)
        val event = audit.events.single()
        assertEquals("Auth", event.outcome)
        assertEquals(wireBytes(), event.requestBytes)
        assertTrue("audit never carries content", event.outcome.length < 32)
    }

    private fun wireBytes(): Int =
        ChatCodec.encodeRequest(request, "max_tokens").toString().encodeToByteArray().size

    @Test
    fun `given a 429 then a success when called then the retry lands and the usage is audited`() = runBlocking {
        var calls = 0
        val engine = FakeEngine().apply {
            // first call 429, second call a tool-call response — the engine seam is replaced per call
        }
        val scripted = arrayOf(
            Pair(429, """{"error":{"message":"rate limited"}}"""),
            Pair(200, """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop","usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}]}"""),
        )
        val gw = LlmGateway(FakeProfiles(), { url, headers, body, timeout ->
            val (status, responseBody) = scripted[minOf(calls, scripted.size - 1)]
            calls += 1
            HttpResponse(status, emptyMap(), responseBody)
        }, RecordingAudit(), nowMs = { 1_000L }, urlEnv = {
            BaseUrlValidator.UrlEnv(cleartextLoopbackEnabled = true, debugBuild = false)
        })
        val result = gw.chat("prov-1", request, deadlineMs = 60_000)
        assertTrue(result.okOrNull() != null)
        assertEquals(2, calls) // one retry after the 429
    }

    @Test
    fun `given a context-length 400 when called then ContextOverflow maps for the run`() = runBlocking {
        val (gw, _) = gateway(FakeEngine(status = 400, body = """{"error":{"message":"context length exceeded"}}"""))
        assertEquals(LlmError.ContextOverflow, gw.chat("prov-1", request, 30_000).errorOrNull())
    }

    @Test
    fun `given no profile or no egress acceptance when called then NotConfigured and NO engine call`() = runBlocking {
        val engine = FakeEngine()
        val (noProfile, audit1) = gateway(engine, profiles = object : ProfileStore {
            override fun load() = emptyList<ProviderProfile>()
            override fun save(profiles: List<ProviderProfile>) = Unit
            override fun readApiKey(profileId: String): String? = null
            override fun registerKeyForRedaction(value: String) = Unit
        })
        assertEquals(LlmError.NotConfigured, noProfile.chat("missing", request, 30_000).errorOrNull())
        assertEquals(0, engine.calls)

        val noEgress = profile.copy(egressAcceptedAtMs = null)
        val fakeProfiles = FakeProfiles()
        val (gw2, _) = gateway(engine, profiles = object : ProfileStore {
            override fun load() = listOf(noEgress)
            override fun save(profiles: List<ProviderProfile>) = Unit
            override fun readApiKey(profileId: String): String? = key
            override fun registerKeyForRedaction(value: String) = Unit
        })
        assertEquals(LlmError.NotConfigured, gw2.chat("prov-1", request, 30_000).errorOrNull())
        assertEquals(0, engine.calls)
    }

    @Test
    fun `given a rejected URL when called then NotConfigured without a network attempt`() = runBlocking {
        val engine = FakeEngine()
        val fakeProfiles = FakeProfiles()
        val (gw, _) = gateway(engine, profiles = object : ProfileStore {
            override fun load() = listOf(profile.copy(baseUrl = "http://192.168.1.9/v1")) // LAN http: refused
            override fun save(profiles: List<ProviderProfile>) = Unit
            override fun readApiKey(profileId: String): String? = key
            override fun registerKeyForRedaction(value: String) = Unit
        })
        assertEquals(LlmError.NotConfigured, gw.chat("prov-1", request, 30_000).errorOrNull())
        assertEquals(0, engine.calls)
    }

    @Test
    fun `given the canary key when the whole flow runs then it appears ONLY in the Authorization header`() = runBlocking {
        val engine = FakeEngine(status = 401)
        val (gw, audit) = gateway(engine)
        gw.chat("prov-1", request, 30_000)
        // §9.5 canary: the key exists in exactly one place — the header the engine was given.
        assertTrue(engine.seenAuthorization.all { it == "Bearer $key" })
        for (event in audit.events) {
            val text = event.toString()
            assertTrue("audit must never carry the key", !text.contains(key))
        }
        // the URL the engine saw carries no key either
        assertTrue(engine.seenUrls.all { !it.contains(key) })
    }
}
