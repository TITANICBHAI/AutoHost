package app.autohost.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P13-01: codec golden cases (`docs/MODEL_GATEWAY.md` §9.1) — request encode; response decode for
 * text-only, one tool call, several tool calls, invalid argument JSON, content:null, each
 * finish_reason, usage present/absent; malformed shapes. Under D-26 these are written, not run.
 */
class ChatCodecTest {

    private val tools = listOf(
        ToolSpec("device_ui_click", "Click a node", Json.parseToJsonElement("""{"type":"object"}""").jsonObject),
    )

    // ---- request encode ------------------------------------------------------------------------

    @Test
    fun `given a request when encoded then the wire shape matches the contract`() {
        val request = ChatRequest(
            model = "test-model",
            messages = listOf(
                ChatMessage.System("sys"),
                ChatMessage.User("usr"),
                ChatMessage.Assistant(null, listOf(ToolCall("c1", "device_ui_click", "{}", JsonObject(emptyMap())))),
                ChatMessage.Tool("c1", "done"),
            ),
            tools = tools,
            maxOutputTokens = 512,
            temperature = 0.2,
        )
        val wire = ChatCodec.encodeRequest(request, "max_tokens").jsonObject
        assertEquals("test-model", wire["model"]!!.jsonPrimitive.content)
        assertEquals(4, wire["messages"]!!.jsonArray.size)
        assertEquals("auto", wire["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(false, wire["stream"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals(512, wire["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(0.2, wire["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0001)
        val firstTool = wire["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject
        assertEquals("device_ui_click", firstTool["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `given max_completion_tokens profile when encoded then the alternate field is used`() {
        val wire = ChatCodec.encodeRequest(
            ChatRequest("m", listOf(ChatMessage.User("x")), maxOutputTokens = 10),
            "max_completion_tokens",
        ).jsonObject
        assertEquals(10, wire["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
        assertNull(wire["max_tokens"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given a bad tool name when encoded then the codec refuses it`() {
        ChatCodec.encodeRequest(
            ChatRequest("m", listOf(ChatMessage.User("x")), tools = listOf(ToolSpec("bad name!", "d", JsonObject(emptyMap()))), maxOutputTokens = 10),
            "max_tokens",
        )
    }

    // ---- response decode -----------------------------------------------------------------------

    @Test
    fun `given a text-only response when decoded then text and finish reason come back`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
        val result = ChatCodec.decodeResponse(body, latencyMs = 42, attempts = 1)
        val response = result.okOrNull()!!
        assertEquals("hello", response.text)
        assertEquals(0, response.toolCalls.size)
        assertEquals(FinishReason.STOP, response.finishReason)
        assertEquals(15, response.usage!!.totalTokens)
        assertEquals(false, response.usage.estimated)
        assertEquals(42, response.latencyMs)
    }

    @Test
    fun `given one tool call when decoded then the call is parsed with arguments`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"device_ui_click","arguments":"{\"x\":1}"}}]},"finish_reason":"tool_calls"}]}"""
        val response = ChatCodec.decodeResponse(body, 1, 1).okOrNull()!!
        assertEquals(1, response.toolCalls.size)
        assertEquals("c1", response.toolCalls[0].id)
        assertEquals("device_ui_click", response.toolCalls[0].name)
        assertEquals(1, response.toolCalls[0].arguments!!.size)
        assertNull(response.text)
        assertNull(response.usage) // usage absent stays null — unknown is never zero
    }

    @Test
    fun `given several tool calls when decoded then all come back in order`() {
        val body = """{"choices":[{"message":{"tool_calls":[
            {"id":"a","type":"function","function":{"name":"t1","arguments":"{}"}},
            {"id":"b","type":"function","function":{"name":"t2","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        val response = ChatCodec.decodeResponse(body, 1, 1).okOrNull()!!
        assertEquals(listOf("a", "b"), response.toolCalls.map { it.id })
        assertEquals(listOf("t1", "t2"), response.toolCalls.map { it.name })
    }

    @Test
    fun `given invalid argument JSON when decoded then arguments is null and the raw text is kept`() {
        val body = """{"choices":[{"message":{"tool_calls":[{"id":"c","type":"function","function":{"name":"t","arguments":"{not json"}}]},"finish_reason":"tool_calls"}]}"""
        val response = ChatCodec.decodeResponse(body, 1, 1).okOrNull()!!
        assertNull(response.toolCalls[0].arguments)
        assertEquals("{not json", response.toolCalls[0].argumentsJson)
    }

    // ---- INT-A10: a provider's per-call extras (Gemini 3's thought signature) go back unchanged --------------------

    private val geminiBody = """{"choices":[{"message":{"role":"assistant","tool_calls":[
        {"id":"function-call-1","type":"function","function":{"name":"ui_click","arguments":"{}"},
         "extra_content":{"google":{"thought_signature":"SIG-A"}}},
        {"id":"function-call-2","type":"function","function":{"name":"ui_scroll","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""

    @Test
    fun `given a Gemini 3 style tool call with a thought signature when decoded then the extra content is kept on that call only`() {
        val response = ChatCodec.decodeResponse(geminiBody, 1, 1).okOrNull()!!
        val signature = response.toolCalls[0].extraContent!!["google"]!!.jsonObject["thought_signature"]!!.jsonPrimitive.content
        assertEquals("SIG-A", signature)
        assertNull(response.toolCalls[1].extraContent)
    }

    @Test
    fun `given decoded tool calls when the next request is encoded then the extra content goes back unchanged on the same call`() {
        val calls = ChatCodec.decodeResponse(geminiBody, 1, 1).okOrNull()!!.toolCalls
        val request = ChatRequest(
            model = "m",
            messages = listOf(
                ChatMessage.User("go"),
                ChatMessage.Assistant(null, calls),
                ChatMessage.Tool("function-call-1", "ok"),
                ChatMessage.Tool("function-call-2", "skipped"),
            ),
            maxOutputTokens = 10,
        )
        val sent = ChatCodec.encodeRequest(request, "max_tokens")["messages"]!!.jsonArray[1].jsonObject["tool_calls"]!!.jsonArray
        val signature = sent[0].jsonObject["extra_content"]!!.jsonObject["google"]!!.jsonObject["thought_signature"]!!.jsonPrimitive.content
        assertEquals("SIG-A", signature)
        assertEquals(setOf("id", "type", "function", "extra_content"), sent[0].jsonObject.keys)
        // a call that came without extras goes back without any (what every other provider sees is unchanged)
        assertEquals(setOf("id", "type", "function"), sent[1].jsonObject.keys)
    }

    @Test
    fun `given an extra content that is not an object or is far too big when decoded then it is dropped and the call still works`() {
        val big = "x".repeat(20_000)
        val body = """{"choices":[{"message":{"tool_calls":[
            {"id":"a","type":"function","function":{"name":"t1","arguments":"{}"},"extra_content":"just text"},
            {"id":"b","type":"function","function":{"name":"t2","arguments":"{}"},"extra_content":{"google":{"thought_signature":"$big"}}}]},"finish_reason":"tool_calls"}]}"""
        val response = ChatCodec.decodeResponse(body, 1, 1).okOrNull()!!
        assertEquals(listOf("a", "b"), response.toolCalls.map { it.id })
        assertNull(response.toolCalls[0].extraContent)
        assertNull(response.toolCalls[1].extraContent)
    }

    @Test
    fun `given finish_reason length with tool calls when decoded then malformed (truncated arguments)`() {
        val body = """{"choices":[{"message":{"tool_calls":[{"id":"c","type":"function","function":{"name":"t","arguments":"{\"x\":"}}]},"finish_reason":"length"}]}"""
        val error = ChatCodec.decodeResponse(body, 1, 1).errorOrNull()!!
        assertTrue(error is LlmError.MalformedResponse)
    }

    @Test
    fun `given each finish_reason when decoded then the enum maps`() {
        for ((raw, expected) in mapOf("stop" to FinishReason.STOP, "tool_calls" to FinishReason.TOOL_CALLS, "length" to FinishReason.LENGTH, "content_filter" to FinishReason.CONTENT_FILTER)) {
            val body = """{"choices":[{"message":{"content":"x"},"finish_reason":"$raw"}]}"""
            assertEquals(expected, ChatCodec.decodeResponse(body, 1, 1).okOrNull()!!.finishReason)
        }
    }

    @Test
    fun `given malformed bodies when decoded then MalformedResponse with a detail`() {
        for (body in listOf("not json", """{"no_choices":1}""", """{"choices":[]}""", """{"choices":[{"message":{}}]}""", """{"choices":[{"message":{"content":"x"},"finish_reason":"weird"}]}""")) {
            val error = ChatCodec.decodeResponse(body, 1, 1).errorOrNull()
            assertNotNull("body should be malformed: $body", error)
            assertTrue(error is LlmError.MalformedResponse)
        }
    }

    // ---- error mapping --------------------------------------------------------------------------

    @Test
    fun `given non-200 statuses when mapped then the §6 table holds`() {
        assertEquals(LlmError.Auth, ChatCodec.decodeError(401, ""))
        assertEquals(LlmError.Auth, ChatCodec.decodeError(403, ""))
        assertTrue(ChatCodec.decodeError(429, "") is LlmError.RateLimited)
        assertTrue(ChatCodec.decodeError(500, "") is LlmError.Transient)
        assertEquals(LlmError.ContextOverflow, ChatCodec.decodeError(400, """{"error":{"message":"context length exceeded"}}"""))
        assertTrue(ChatCodec.decodeError(400, """{"error":{"message":"bad field"}}""") is LlmError.InvalidRequest)
        assertTrue(ChatCodec.decodeError(302, "") is LlmError.InvalidRequest)
    }

    @Test
    fun `given a Retry-After header when parsed then milliseconds capped by the policy seam later`() {
        assertEquals(20_000L, ChatCodec.parseRetryAfterMs(mapOf("Retry-After" to "20")))
        assertEquals(null, ChatCodec.parseRetryAfterMs(mapOf("Retry-After" to "later")))
    }
}
