package app.autohost.core.llm

import kotlinx.coroutines.delay

/**
 * P13-07 (ADR-090, `docs/MODEL_GATEWAY.md` §7): the host's model client. One call =
 * load profile → **validate the URL every call** (the cleartext switch can change) → require the
 * egress acceptance → read the key at call time → encode → engine under [RetryPolicy] → decode →
 * map errors → one audit record (SIZES AND HASHES, never content). No implicit provider
 * fallback: a profile failure is that profile's failure. CancellationException is rethrown
 * (ADR-091 (9)) — never converted into an error result.
 */
class LlmGateway(
    private val profiles: ProfileStore,
    private val engine: LlmHttpEngine,
    private val audit: AuditSink,
    private val nowMs: () -> Long,
    private val urlEnv: () -> BaseUrlValidator.UrlEnv,
) {

    /** Sizes and hashes only — never prompt, screen, tool-result or response text (§8). */
    fun interface AuditSink {
        fun record(event: AuditEvent)
    }

    data class AuditEvent(
        val providerId: String,
        val host: String,
        val model: String,
        val requestBytes: Int,
        val responseBytes: Int,
        val promptTokens: Int?,
        val completionTokens: Int?,
        val estimated: Boolean,
        val latencyMs: Long,
        val attempts: Int,
        val outcome: String, // "ok" | the LlmError class simple name
    )

    /** The chat entry point. [deadlineMs] is the run's remaining wall-clock budget. */
    suspend fun chat(profileId: String, request: ChatRequest, deadlineMs: Long): LlmResult<ChatResponse> {
        val profile = profiles.load().firstOrNull { it.id == profileId }
            ?: return LlmResults.err(LlmError.NotConfigured)
        if (profile.egressAcceptedAtMs == null) return LlmResults.err(LlmError.NotConfigured)
        val baseUrl = when (val verdict = BaseUrlValidator.validate(profile.baseUrl, urlEnv())) {
            is BaseUrlValidator.Verdict.Accepted -> verdict.baseUrl
            is BaseUrlValidator.Verdict.Rejected -> return LlmResults.err(LlmError.NotConfigured)
        }
        val key = profiles.readApiKey(profileId)
            ?: return LlmResults.err(LlmError.NotConfigured)
        profiles.registerKeyForRedaction(key)

        val wire = ChatCodec.encodeRequest(request, profile.maxTokensField).toString()
        val wireBytes = wire.encodeToByteArray()
        val url = BaseUrlValidator.chatEndpoint(baseUrl)
        val headers = buildMap {
            put("Authorization", "Bearer $key")
            for ((k, v) in profile.extraHeaders) put(k, v)
            if (profile.parallelToolCallsParam) put("parallel_tool_calls", "false")
        }

        var attempt = 0
        while (true) {
            attempt += 1
            val remaining = deadlineMs - nowMs()
            if (remaining <= 0) {
                return LlmResults.err(LlmError.Transient("run wall-clock budget exhausted"))
            }
            val started = nowMs()
            val response = try {
                engine.post(url, headers, wireBytes, timeoutMs = profile.readTimeoutMs.coerceAtMost(remaining.toInt()))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                null // network failure before any response: classified below
            }
            val latency = nowMs() - started

            val terminal: LlmResult<ChatResponse>? = if (response == null) {
                audit(profile, url, request, wireBytes, latency, attempt, "Unreachable", 0)
                LlmResults.err(LlmError.Unreachable("network failure"))
            } else if (response.status in 200..299) {
                when (val decoded = ChatCodec.decodeResponse(response.body, latency, attempt)) {
                    is LlmResult.Ok -> {
                        audit(profile, url, request, wireBytes, latency, attempt, "ok", response.body.length, decoded.value.usage)
                        LlmResults.ok(decoded.value)
                    }
                    is LlmResult.Err -> {
                        audit(profile, url, request, wireBytes, latency, attempt, decoded.error.javaClass.simpleName, response.body.length)
                        LlmResults.err(decoded.error)
                    }
                }
            } else {
                val retryAfter = ChatCodec.parseRetryAfterMs(response.headers)
                val error = ChatCodec.decodeError(response.status, response.body, retryAfter)
                audit(profile, url, request, wireBytes, latency, attempt, error.javaClass.simpleName, response.body.length)
                LlmResults.err(error)
            }
            if (terminal != null) return terminal

            val outcome = when {
                response == null -> RetryPolicy.Outcome(RetryPolicy.OutcomeKind.NETWORK, attempt)
                response.status == 429 -> RetryPolicy.Outcome(
                    RetryPolicy.OutcomeKind.RATE_LIMITED, attempt,
                    ChatCodec.parseRetryAfterMs(response.headers),
                )
                response.status == 408 || response.status == 425 || response.status >= 500 ->
                    RetryPolicy.Outcome(RetryPolicy.OutcomeKind.TRANSIENT, attempt)
                else -> RetryPolicy.Outcome(RetryPolicy.OutcomeKind.INVALID, attempt)
            }
            when (val decision = RetryPolicy.plan(outcome, deadlineMs - nowMs())) {
                is RetryPolicy.Decision.GiveUp -> return LlmResults.err(mapOutcome(outcome))
                is RetryPolicy.Decision.Retry -> delay(decision.delayMs) // cancellable suspend — Stop cancels it
            }
        }
    }

    private fun mapOutcome(outcome: RetryPolicy.Outcome): LlmError = when (outcome.kind) {
        RetryPolicy.OutcomeKind.RATE_LIMITED -> LlmError.RateLimited(outcome.retryAfterMs)
        RetryPolicy.OutcomeKind.TRANSIENT, RetryPolicy.OutcomeKind.NETWORK -> LlmError.Unreachable("retries exhausted")
        else -> LlmError.InvalidRequest("retries exhausted")
    }

    private fun audit(
        profile: ProviderProfile,
        url: String,
        request: ChatRequest,
        requestBytes: ByteArray,
        latencyMs: Long,
        attempts: Int,
        outcome: String,
        responseBytes: Int,
        usage: Usage? = null,
    ) {
        audit.record(
            AuditEvent(
                providerId = profile.id,
                host = hostOf(url),
                model = request.model,
                requestBytes = requestBytes.size,
                responseBytes = responseBytes,
                promptTokens = usage?.promptTokens,
                completionTokens = usage?.completionTokens,
                estimated = usage?.estimated ?: true,
                latencyMs = latencyMs,
                attempts = attempts,
                outcome = outcome,
            ),
        )
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrDefault("<unparsed>")
}
