package app.autohost.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * P13-01 (ADR-090): OpenAI-compatible Chat Completions codec (encode request / decode response).
 * Non-streaming. Malformed inputs decode into [LlmError.MalformedResponse] — the codec never
 * repairs JSON, never invents usage, never drops a tool call on the floor.
 */
object ChatCodec {

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    private val nameRegex = Regex(LLM_NAME_PATTERN)

    fun encodeRequest(request: ChatRequest, maxTokensField: String): JsonObject {
        require(request.model.isNotBlank()) { "model id is required" }
        require(maxTokensField == "max_tokens" || maxTokensField == "max_completion_tokens") {
            "unknown output-limit field $maxTokensField"
        }
        require(request.maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        for (message in request.messages) validateMessageName(message)
        for (tool in request.tools) validateName(tool.name, "tool name")
        return buildJsonObject {
            put("model", request.model)
            put("messages", buildJsonArray {
                for (m in request.messages) add(encodeMessage(m))
            })
            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    for (t in request.tools) add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", t.parameters)
                        })
                    })
                })
                put("tool_choice", "auto")
            }
            put(maxTokensField, request.maxOutputTokens)
            request.temperature?.let { put("temperature", it) }
            put("stream", false)
        }
    }

    private fun encodeMessage(m: ChatMessage): JsonObject = when (m) {
        is ChatMessage.System -> buildJsonObject { put("role", "system"); put("content", m.text) }
        is ChatMessage.User -> buildJsonObject { put("role", "user"); put("content", m.text) }
        is ChatMessage.Assistant -> buildJsonObject {
            put("role", "assistant")
            m.text?.let { put("content", it) }
            if (m.toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    for (call in m.toolCalls) add(buildJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", call.name)
                            put("arguments", call.argumentsJson)
                        })
                    })
                })
            }
        }
        is ChatMessage.Tool -> buildJsonObject {
            put("role", "tool")
            put("tool_call_id", m.toolCallId)
            put("content", m.content)
        }
    }

    private fun validateMessageName(m: ChatMessage) {
        // roles are fixed; only tool names are caller-chosen — validated at the ToolSpec level.
        for (call in (m as? ChatMessage.Assistant)?.toolCalls ?: emptyList()) validateName(call.name, "tool call name")
    }

    private fun validateName(name: String, what: String) {
        check(nameRegex.matches(name)) { "$what must match $LLM_NAME_PATTERN: \"$name\"" }
    }

    /**
     * Decodes a success (HTTP 200) response body. Returns [LlmError.MalformedResponse] for any
     * shape the contract does not promise. `finish_reason = length` WITH tool calls is malformed
     * (possibly truncated arguments — `docs/MODEL_GATEWAY.md` §2).
     */
    fun decodeResponse(body: String, latencyMs: Long, attempts: Int): LlmResult<ChatResponse> {
        val root = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return LlmResults.err(LlmError.MalformedResponse("response is not a JSON object"))
        }
        val choices = root["choices"] as? JsonArray
            ?: return LlmResults.err(LlmError.MalformedResponse("response has no choices array"))
        if (choices.isEmpty()) return LlmResults.err(LlmError.MalformedResponse("choices is empty"))
        val choice = choices[0].jsonObject
        val message = choice["message"] as? JsonObject
            ?: return LlmResults.err(LlmError.MalformedResponse("choice has no message"))
        val finishReasonRaw = (choice["finish_reason"] as? JsonPrimitive)?.content
            ?: return LlmResults.err(LlmError.MalformedResponse("choice has no finish_reason"))
        val finishReason = when (finishReasonRaw) {
            "stop" -> FinishReason.STOP
            "tool_calls" -> FinishReason.TOOL_CALLS
            "length" -> FinishReason.LENGTH
            "content_filter" -> FinishReason.CONTENT_FILTER
            else -> return LlmResults.err(LlmError.MalformedResponse("unknown finish_reason \"$finishReasonRaw\""))
        }
        // `content` is absent-or-null on a pure tool-call turn; JsonNull must not read as "null".
        val text = when (val content = message["content"]) {
            null, is JsonNull -> null
            is JsonPrimitive -> content.content.takeIf { it.isNotEmpty() }
            else -> return LlmResults.err(LlmError.MalformedResponse("message content is not a string"))
        }
        val toolCalls = ArrayList<ToolCall>()
        (message["tool_calls"] as? JsonArray)?.forEach { element ->
            val callObj = element.jsonObject
            val fn = callObj["function"] as? JsonObject
                ?: return@forEach
            val id = (callObj["id"] as? JsonPrimitive)?.content ?: ""
            val name = (fn["name"] as? JsonPrimitive)?.content
            if (id.isEmpty() || name.isNullOrBlank()) {
                return LlmResults.err(LlmError.MalformedResponse("tool call without id or name"))
            }
            val argsJson = (fn["arguments"] as? JsonPrimitive)?.content ?: "{}"
            val args = try {
                Json.parseToJsonElement(argsJson).jsonObject
            } catch (e: Exception) {
                null // invalid argument JSON is carried through with arguments = null
            }
            toolCalls.add(ToolCall(id, name, argsJson, args))
        }
        if (finishReason == FinishReason.LENGTH && toolCalls.isNotEmpty()) {
            return LlmResults.err(LlmError.MalformedResponse("finish_reason=length with tool calls (truncated arguments)"))
        }
        val usage = (root["usage"] as? JsonObject)?.let { u ->
            Usage(
                promptTokens = (u["prompt_tokens"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                completionTokens = (u["completion_tokens"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                totalTokens = (u["total_tokens"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                estimated = false,
            )
        }
        return LlmResults.ok(
            ChatResponse(
                text = text,
                toolCalls = toolCalls,
                finishReason = finishReason,
                usage = usage,
                latencyMs = latencyMs,
                attempts = attempts,
            ),
        )
    }

    /** Non-200 responses → [LlmError] by the §6 table. The body is never echoed into the error. */
    fun decodeError(status: Int, body: String, retryAfterMs: Long? = null): LlmError = when {
        status == 401 || status == 403 -> LlmError.Auth
        status == 429 -> LlmError.RateLimited(retryAfterMs)
        status == 408 || status == 425 || status >= 500 -> LlmError.Transient("HTTP $status")
        status == 400 || status == 413 -> {
            val overflow = body.contains("context_length", ignoreCase = true) ||
                body.contains("context length", ignoreCase = true) ||
                body.contains("maximum context", ignoreCase = true)
            if (overflow) LlmError.ContextOverflow else LlmError.InvalidRequest("HTTP $status")
        }
        status in 300..399 -> LlmError.InvalidRequest("HTTP $status (redirects are never followed)")
        else -> LlmError.InvalidRequest("HTTP $status")
    }

    /** Extracts a `Retry-After` (integer seconds) header value, capped at 30 s by the retry policy. */
    fun parseRetryAfterMs(headers: Map<String, String>): Long? =
        headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.times(1000)
}
