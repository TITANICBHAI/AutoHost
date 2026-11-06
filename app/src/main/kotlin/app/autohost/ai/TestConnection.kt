package app.autohost.ai

import app.autohost.core.llm.BaseUrlValidator
import app.autohost.core.llm.ChatMessage
import app.autohost.core.llm.ChatRequest
import app.autohost.core.llm.HttpUrlConnectionEngine
import app.autohost.core.llm.LlmGateway
import app.autohost.core.llm.LlmError
import app.autohost.core.llm.LlmResult

/**
 * P14-06 (S32): "test connection" — one minimal chat call through the REAL gateway (the same
 * URL policy, key read, engine and retry policy a run uses; nothing is cached or faked). The
 * result message carries NO key material and no prompt content — only reachability, latency
 * and the mapped reason on failure.
 */
object TestConnection {

    fun seam(store: AppProfileStore, graph: app.autohost.AppGraph): AiSettingsState.ConnectionTester =
        AiSettingsState.ConnectionTester { profile ->
            val loopback = graph.data.settings.bool(AppProfileStore.SETTING_CLEARTEXT_LOOPBACK)
            val urlEnv = BaseUrlValidator.UrlEnv(
                cleartextLoopbackEnabled = loopback,
                debugBuild = graph.application.applicationInfo.flags and
                    android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0,
            )
            val gateway = LlmGateway(
                profiles = store,
                engine = HttpUrlConnectionEngine(),
                audit = { }, // the screen's test call is not a run; sizes-only audit not needed here
                nowMs = { graph.clock.nowMs() },
                urlEnv = { urlEnv },
            )
            val result = gateway.chat(
                profile.id,
                ChatRequest(
                    model = profile.model,
                    messages = listOf(ChatMessage.User("ping")),
                    maxOutputTokens = 1,
                ),
                deadlineMs = 30_000L,
            )
            when (result) {
                is LlmResult.Ok -> AiSettingsState.ConnectionTester.Result(
                    ok = true,
                    message = "reachable in ${result.value.latencyMs} ms (${result.value.attempts} attempt(s))",
                )
                is LlmResult.Err -> AiSettingsState.ConnectionTester.Result(
                    ok = false,
                    message = when (val e = result.error) {
                        LlmError.NotConfigured -> "no profile, key or egress acceptance"
                        LlmError.Auth -> "the provider rejected the key (401/403)"
                        is LlmError.RateLimited -> "rate limited by the provider"
                        is LlmError.Transient, is LlmError.Unreachable -> "the provider is unreachable"
                        LlmError.ContextOverflow -> "context too small for even the test call"
                        is LlmError.InvalidRequest, is LlmError.MalformedResponse -> "the provider answered something malformed"
                        LlmError.ContentFiltered -> "the provider filtered the test call"
                    },
                )
            }
        }
}
