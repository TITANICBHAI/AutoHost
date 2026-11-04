package app.autohost.core.llm

import kotlinx.serialization.json.JsonObject

/**
 * P13-01 (ADR-090): provider-neutral types for the host's model client. One wire protocol in
 * Stage A — OpenAI-compatible Chat Completions, NON-STREAMING (a half-received tool call can
 * never be executed; cancellation is `disconnect()`; no SSE parser, no partial-state bugs).
 *
 * Secret boundary: an API key never appears in any of these types — it exists only inside the
 * engine's Authorization header at call time (`docs/MODEL_GATEWAY.md` §3).
 */

/** Message and tool names must match this pattern (`docs/MODEL_GATEWAY.md` §2). */
const val LLM_NAME_PATTERN: String = "[a-zA-Z0-9_-]{1,64}"

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val maxOutputTokens: Int,
    val temperature: Double? = null,
)

sealed interface ChatMessage {
    data class System(val text: String) : ChatMessage
    data class User(val text: String) : ChatMessage

    /** [text] is null for a pure tool-call turn. */
    data class Assistant(val text: String?, val toolCalls: List<ToolCall> = emptyList()) : ChatMessage
    data class Tool(val toolCallId: String, val content: String) : ChatMessage
}

data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema object for the tool's parameters. */
    val parameters: JsonObject,
)

/**
 * One tool call the model asked for. [argumentsJson] is the raw string from the wire;
 * [arguments] is null when that string did not parse — the loop answers the model with a typed
 * error, the gateway never "repairs" JSON.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val arguments: JsonObject?,
)

data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    /** True when the loop had to estimate (the endpoint returned no usage — unknown is never zero). */
    val estimated: Boolean,
)

enum class FinishReason { STOP, TOOL_CALLS, LENGTH, CONTENT_FILTER }

data class ChatResponse(
    val text: String?,
    val toolCalls: List<ToolCall>,
    val finishReason: FinishReason,
    val usage: Usage?,
    val latencyMs: Long,
    val attempts: Int,
)
