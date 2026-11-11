package app.autohost.runtime.agent

import app.autohost.core.common.AhResult
import app.autohost.core.llm.ChatMessage
import app.autohost.core.llm.ChatRequest
import app.autohost.core.llm.ChatResponse
import app.autohost.core.llm.LlmError
import app.autohost.core.llm.LlmResult
import app.autohost.core.llm.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P13-10 (AGENT_RUNTIME §7.1): the turn loop, the §7.1 pseudocode verbatim:
 *
 * ```
 * preflight()                                   // once (§12; P13-13's seam here)
 * obs := observe()                              // host-initiated: settle + snapshot, floor-filtered
 * loop:
 *   budget.beforeModelCall()                    // turns/wall-clock/model-calls/tokens-reserve
 *   msgs := builder.build(state)                // system prompt v1 + goal + compacted history + latest observation
 *   resp := gateway.chat(request)               // cancellable; retry/backoff inside the gateway
 *   budget.account(resp.usage)                  // unknown usage is ESTIMATED and flagged, never 0
 *   if resp.toolCalls.isEmpty(): nudge once; a second time counts as a failure
 *   call := resp.toolCalls[0]                   // ONE action per turn; extras get ONE_ACTION_PER_TURN error results
 *   result := pipeline.execute(call)            // P13-11's ToolPipeline is the ONLY path to the device
 *   if call is finish: completion protocol (§9)
 *   settle + observe()                          // host-initiated; the top of the next turn
 *   stuck.check(); failures.check()             // §7.4
 * ```
 *
 * CancellationException is NEVER swallowed (ADR-091 (9)) — it propagates.
 */
class AgentLoop(
    private val client: ModelClient,
    private val catalog: ToolCatalog,
    private val pipeline: ToolExecutor,
    private val observer: Observer,
    private val preflight: Preflight,
    private val finishVerifier: FinishVerifier,
    private val budget: RunBudget,
    private val view: AgentView,
    private val config: Config = Config(),
    private val sink: Sink = Sink { _, _ -> },
    private val pauseGate: PauseGate = PauseGate { },
) {

    /** The model seam — the production adapter wraps `LlmGateway` (P13-12's wiring). */
    fun interface ModelClient {
        suspend fun chat(request: ChatRequest): LlmResult<ChatResponse>
    }

    /**
     * The one-action-path seam — P13-11's `ToolPipeline.execute`. Contract: the tool-call's
     * context carries the CURRENT [AgentView.ViewIndex] (node-id resolution, STALE_OBSERVATION);
     * the result is the tool-result JSON the model sees (already capped by the pipeline).
     */
    fun interface ToolExecutor {
        suspend fun execute(call: ToolCall, ctx: TurnContext): JsonObject
    }

    /**
     * The host-initiated observation seam: `wait_for_settle` (≤ 3 s) + snapshot, ALREADY run
     * through the observation floor (P13-08's `ObservationFilter`) — a withheld observation
     * never reaches this loop.
     */
    fun interface Observer {
        suspend fun observe(): JsonObject
    }

    /** The §12 preflight seam (P13-13); runs once, before the first model call. */
    fun interface Preflight {
        suspend fun check(): AhResult<Unit>
    }

    /** The §9 completion-protocol seam (P13-14). */
    fun interface FinishVerifier {
        suspend fun verify(summary: String?): FinishResult
    }

    sealed interface FinishResult {
        data object Confirmed : FinishResult
        data object Rejected : FinishResult
        data object Unverified : FinishResult
    }

    /** Loop events; P13-15's AgentEventLog maps these onto the §11 BrokerEvent table. */
    fun interface Sink {
        fun on(kind: String, data: JsonObject)
    }

    /**
     * P13-12 (AGENT_RUNTIME §11): pause is COOPERATIVE at turn boundaries — the gate waits while
     * paused before a new turn observes/acts; the in-flight model call and broker call finish
     * first. Cancellation propagates through the gate's wait.
     */
    fun interface PauseGate {
        suspend fun gate()
    }

    data class TurnContext(
        val generation: Int,
        val view: AgentView.ViewIndex,
        val previousView: AgentView.ViewIndex?,
    )

    data class Config(
        val model: String = "autohost-agent",
        val maxOutputTokens: Int = 1_024,
        val maxConsecutiveFailures: Int = 3,
        val toolResultCharCap: Int = 4_000,
    )

    sealed interface Outcome {
        data class Succeeded(val claimedSummary: String?, val turns: Int) : Outcome
        data class Failed(val reason: String, val message: String, val turns: Int) : Outcome
    }

    suspend fun run(goal: String): Outcome {
        when (val pf = preflight.check()) {
            is AhResult.Err -> return Outcome.Failed(pf.error.reason ?: pf.error.code, pf.error.message, 0)
            is AhResult.Ok -> {}
        }
        budget.start()
        val system = SystemPrompt.load()
        val history = ArrayList<ChatMessage>()
        history.add(ChatMessage.User(goalBlock(goal)))
        var generation = 0
        var previousIndex: AgentView.ViewIndex? = null
        var currentIndex: AgentView.ViewIndex? = null
        var consecutiveEmpty = 0
        var consecutiveFailures = 0
        val stuck = StuckDetector()

        while (true) {
            // pause is cooperative: no new turn starts while paused (§11); cancellation
            // propagates from the gate's wait
            pauseGate.gate()
            // obs := observe() — host-initiated (settle rides the observer's contract);
            // §11 stage pairs: observe → think → act, each announced begin/end
            sink.on("stage_begin", buildJsonObject { put("stage", "observe"); put("generation", generation + 1) })
            val snapshot = try {
                observer.observe()
            } catch (e: CancellationException) {
                throw e
            }
            generation++
            val rendered = view.render(snapshot, generation)
            previousIndex = currentIndex
            currentIndex = rendered.index
            history.add(ChatMessage.User(untrustedScreen(generation, rendered.text)))
            when (val verdict = stuck.observe(view.fingerprint(snapshot))) {
                is StuckDetector.Verdict.Stop -> return Outcome.Failed("AGENT_STUCK", verdict.message, budget.turns)
                is StuckDetector.Verdict.Warn ->
                    history.add(ChatMessage.User("[warning] ${verdict.message} — change your approach or finish."))
                StuckDetector.Verdict.None -> {}
            }
            sink.on("stage_end", buildJsonObject { put("stage", "observe") })
            sink.on(
                "progress",
                buildJsonObject { put("turn", budget.turns + 1); put("generation", generation) },
            )

            // budget.beforeModelCall()
            val promptChars = promptChars(system, history)
            when (val verdict = budget.beforeModelCall(promptChars)) {
                is RunBudget.Verdict.Exhausted -> {
                    sink.on("agent.budget", buildJsonObject { put("budget", verdict.reason) })
                    return Outcome.Failed(verdict.reason, verdict.message, budget.turns)
                }
                RunBudget.Verdict.Ok -> {}
            }

            // resp := gateway.chat(request)
            sink.on("stage_begin", buildJsonObject { put("stage", "think") })
            val request = ChatRequest(
                model = config.model,
                messages = buildMessages(system, history, config),
                tools = catalog.specs(),
                maxOutputTokens = config.maxOutputTokens,
            )
            val response = when (val result = client.chat(request)) {
                is LlmResult.Ok -> result.value
                is LlmResult.Err -> {
                    sink.on("stage_end", buildJsonObject { put("stage", "think") })
                    // A failed call still consumed the turn's reservation: estimate its tokens.
                    budget.accountModelCall(null, promptChars)
                    consecutiveFailures++
                    if (consecutiveFailures >= config.maxConsecutiveFailures) {
                        return Outcome.Failed(modelReason(result.error), result.error.javaClass.simpleName, budget.turns)
                    }
                    continue
                }
            }
            budget.accountModelCall(response.usage, promptChars)
            sink.on("stage_end", buildJsonObject { put("stage", "think") })
            sink.on(
                "agent.usage",
                buildJsonObject {
                    put("prompt_tokens", response.usage?.promptTokens ?: 0)
                    put("completion_tokens", response.usage?.completionTokens ?: 0)
                    put("estimated", response.usage?.estimated ?: true)
                    put("latency_ms", response.latencyMs)
                },
            )

            // the no-tool-call nudge: once is free, a second consecutive time is a failure (§7.1)
            if (response.toolCalls.isEmpty()) {
                if (!response.text.isNullOrEmpty()) {
                    sink.on("agent.thought", buildJsonObject { put("text", response.text!!) })
                    history.add(ChatMessage.Assistant(response.text))
                }
                consecutiveEmpty++
                if (consecutiveEmpty == 1) {
                    history.add(ChatMessage.User(NUDGE))
                    continue
                }
                consecutiveEmpty = 0
                consecutiveFailures++
                if (consecutiveFailures >= config.maxConsecutiveFailures) {
                    return Outcome.Failed(
                        "AGENT_FAILURES",
                        "the model called no tool in $consecutiveFailures consecutive turns",
                        budget.turns,
                    )
                }
                continue
            }
            consecutiveEmpty = 0
            history.add(ChatMessage.Assistant(response.text, response.toolCalls))

            // ONE action per turn: the first call executes; every extra gets a typed error result
            // (OpenAI-compatible APIs reject a missing tool result — never silence, §7.1).
            val call = response.toolCalls.first()
            for (extra in response.toolCalls.drop(1)) {
                history.add(
                    ChatMessage.Tool(
                        extra.id,
                        errorResult("ONE_ACTION_PER_TURN", "one action per turn — this extra call was not executed").toString(),
                    ),
                )
            }

            // if call is finish: completion protocol (§9)
            if (call.name == "finish") {
                val summary = call.arguments?.get("summary")?.let { (it as? JsonPrimitive)?.content }
                return when (finishVerifier.verify(summary)) {
                    FinishResult.Confirmed -> {
                        sink.on("result", buildJsonObject { put("verified_by", "user_confirm") })
                        Outcome.Succeeded(summary, budget.turns)
                    }
                    FinishResult.Rejected -> Outcome.Failed("COMPLETION_REJECTED", "the user rejected the claimed result", budget.turns)
                    FinishResult.Unverified -> Outcome.Failed("COMPLETION_UNVERIFIED", "the claimed result was never confirmed", budget.turns)
                }
            }

            // an unknown tool fails closed (it was never in the model's specs — defense in depth)
            // and counts as a tool failure (§7.4); the typed result goes back so the model can correct
            val unknownTool = catalog.find(call.name) == null
            val result = if (unknownTool) {
                errorResult("INVALID_ARGS", "unknown tool '${call.name}'")
            } else {
                // result := pipeline.execute(call) — the ONLY path to the device. The ACTION
                // budget reserve lives inside the pipeline (§10's order); a BUDGET_* or
                // APPROVAL_TIMEOUT reason in the result stops the run (below).
                sink.on("stage_begin", buildJsonObject { put("stage", "act") })
                // §11: the action event carries the tool name and a (log-side) redacted args summary
                sink.on("agent.action", buildJsonObject { put("tool", call.name); put("args", call.argumentsJson) })
                try {
                    pipeline.execute(call, TurnContext(generation, currentIndex!!, previousIndex))
                } catch (e: CancellationException) {
                    throw e
                }
            }
            // a BUDGET_* reserve miss or an approval timeout inside the pipeline is a RUN stop,
            // not a tool error the model could retry (§10: approval timeout → deny and stop)
            val error = result["error"] as? kotlinx.serialization.json.JsonObject
            val stopReason = error?.get("reason")?.let { (it as? JsonPrimitive)?.content }
                ?.takeIf { it.startsWith("BUDGET_") || it == "APPROVAL_TIMEOUT" }
            if (stopReason != null) {
                sink.on("agent.budget", buildJsonObject { put("budget", stopReason) })
                return Outcome.Failed(
                    stopReason,
                    error?.get("message")?.let { (it as? JsonPrimitive)?.content } ?: stopReason,
                    budget.turns,
                )
            }
            history.add(ChatMessage.Tool(call.id, capToolResult(result.toString(), config.toolResultCharCap)))
            val ok = result["ok"]?.let { (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull() } == true
            sink.on("agent.result", buildJsonObject { put("tool", call.name); put("ok", ok) })
            sink.on("stage_end", buildJsonObject { put("stage", "act") })
            consecutiveFailures = if (ok) 0 else consecutiveFailures + 1
            if (consecutiveFailures >= config.maxConsecutiveFailures) {
                return Outcome.Failed(
                    "AGENT_FAILURES",
                    "$consecutiveFailures consecutive tool failures",
                    budget.turns,
                )
            }

            // stuck: the same (tool, args, target) three times is a stop, screen changed or not.
            // Only EXECUTED device actions count — not unknown tools (they never ran) and not
            // host tools (repeating ask_user/wait is legitimate, §7.4 "actions").
            if (!unknownTool && catalog.find(call.name)?.capability != null) {
                when (val verdict = stuck.recordAction(call.name, call.argumentsJson, targetOf(call))) {
                    is StuckDetector.Verdict.Stop -> return Outcome.Failed("AGENT_STUCK", verdict.message, budget.turns)
                    else -> {}
                }
            }
        }
    }

    // -- message building (§7.2) ------------------------------------------------------------------

    private fun buildMessages(system: String, history: List<ChatMessage>, config: Config): List<ChatMessage> {
        val messages = ArrayList<ChatMessage>()
        messages.add(ChatMessage.System(system))
        // Stale-output compaction: every <untrusted_screen> wrapper EXCEPT the last one is
        // stale by definition (its node ids are rejected), so older screens collapse to a
        // one-line note. Tool results stay but are capped (the §7.1 compacted history).
        val lastScreen = history.indexOfLast { it is ChatMessage.User && it.text.startsWith(UNTRUSTED_OPEN) }
        history.forEachIndexed { index, message ->
            when {
                message is ChatMessage.User && message.text.startsWith(UNTRUSTED_OPEN) && index != lastScreen ->
                    messages.add(ChatMessage.User(STALE_SCREEN_NOTE))
                message is ChatMessage.Tool ->
                    messages.add(message.copy(content = capToolResult(message.content, config.toolResultCharCap)))
                else -> messages.add(message)
            }
        }
        return messages
    }

    private fun capToolResult(content: String, cap: Int): String =
        if (content.length <= cap) content else content.take(cap) + "\"...[truncated]\"}"

    private fun promptChars(system: String, history: List<ChatMessage>): Int =
        system.length + history.sumOf { message ->
            when (message) {
                is ChatMessage.System -> message.text.length
                is ChatMessage.User -> message.text.length
                is ChatMessage.Assistant -> (message.text?.length ?: 0) + message.toolCalls.sumOf { it.argumentsJson.length }
                is ChatMessage.Tool -> message.content.length
            } + 8
        }

    private fun targetOf(call: ToolCall): String? = call.arguments?.get("ref")?.let { (it as? JsonPrimitive)?.content }
        ?: call.arguments?.get("selector")?.let { (it as? JsonPrimitive)?.content }

    companion object {
        const val UNTRUSTED_OPEN = "<untrusted_screen"
        const val NUDGE = "You produced no tool call. Either call exactly one tool or call finish."
        private const val STALE_SCREEN_NOTE = "[a stale observation was removed from history; the latest screen is below]"

        fun untrustedScreen(generation: Int, text: String): String =
            "<untrusted_screen gen=\"$generation\">\n$text\n</untrusted_screen>"

        fun goalBlock(goal: String): String = "<goal>\n$goal\n</goal>"

        fun errorResult(reason: String, message: String): JsonObject = buildJsonObject {
            put("ok", false)
            put("error", buildJsonObject {
                put("code", "CAPABILITY_UNAVAILABLE")
                put("reason", reason)
                put("message", message)
            })
        }

        /** `LlmError` → the run reason code (`docs/MODEL_GATEWAY.md` §6). */
        fun modelReason(error: LlmError): String = when (error) {
            LlmError.NotConfigured -> "AI_NOT_CONFIGURED"
            LlmError.Auth -> "MODEL_AUTH"
            is LlmError.RateLimited -> "MODEL_RATE_LIMITED"
            is LlmError.Transient, is LlmError.Unreachable -> "MODEL_UNREACHABLE"
            LlmError.ContextOverflow -> "MODEL_CONTEXT_OVERFLOW"
            is LlmError.InvalidRequest, is LlmError.MalformedResponse -> "MODEL_BAD_RESPONSE"
            LlmError.ContentFiltered -> "MODEL_FILTERED"
        }
    }
}
