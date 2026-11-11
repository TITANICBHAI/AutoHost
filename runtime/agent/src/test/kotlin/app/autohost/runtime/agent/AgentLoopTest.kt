package app.autohost.runtime.agent

import app.autohost.core.common.AhResult
import app.autohost.core.llm.ChatMessage
import app.autohost.core.llm.ChatRequest
import app.autohost.core.llm.ChatResponse
import app.autohost.core.llm.FinishReason
import app.autohost.core.llm.LlmError
import app.autohost.core.llm.LlmResult
import app.autohost.core.llm.LlmResults
import app.autohost.core.llm.ToolCall
import app.autohost.core.llm.Usage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P13-10 (AGENT_RUNTIME §7.1): the turn loop against a FakeLlm — a scripted per-turn assistant.
 * The seams are scripted (observer returns a static snapshot; pipeline records the calls and
 * returns ok; the finish verifier is programmed per case).
 */
class AgentLoopTest {

    // -- fakes -----------------------------------------------------------------------------------

    private sealed interface FakeTurn {
        class Resp(val text: String? = null, val calls: List<ToolCall> = emptyList()) : FakeTurn
        class Err(val error: LlmError) : FakeTurn
    }

    /** The scripted per-turn assistant; records every request for the history assertions. */
    private class FakeLlm(turns: List<FakeTurn>) : AgentLoop.ModelClient {
        val requests = mutableListOf<ChatRequest>()
        private val iterator = turns.iterator()

        override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> {
            requests.add(request)
            val turn = iterator.next()
            return when (turn) {
                is FakeTurn.Err -> LlmResults.err(turn.error)
                is FakeTurn.Resp -> LlmResults.ok(
                    ChatResponse(
                        text = turn.text,
                        toolCalls = turn.calls,
                        finishReason = if (turn.calls.isEmpty()) FinishReason.STOP else FinishReason.TOOL_CALLS,
                        usage = Usage(20, 10, 30, estimated = false),
                        latencyMs = 5,
                        attempts = 1,
                    ),
                )
            }
        }
    }

    private class FakeObserver(vararg snapshots: JsonObject) : AgentLoop.Observer {
        private val snapshots = snapshots.toList()
        private var i = 0
        override suspend fun observe(): JsonObject = snapshots[minOf(i, size - 1)].also { i++ }
        private val size get() = snapshots.size
    }

    private class FakePipeline : AgentLoop.ToolExecutor {
        val calls = mutableListOf<Pair<ToolCall, AgentLoop.TurnContext>>()
        var result: JsonObject = okResult()
        override suspend fun execute(call: ToolCall, ctx: AgentLoop.TurnContext): JsonObject {
            calls.add(call to ctx)
            return result
        }

        companion object {
            fun okResult() = buildJsonObject { put("ok", true) }
        }
    }

    private open class FakeVerifier(private val verdict: AgentLoop.FinishResult) : AgentLoop.FinishVerifier {
        val asked = mutableListOf<String?>()
        override suspend fun verify(summary: String?): AgentLoop.FinishResult {
            asked.add(summary)
            return verdict
        }
    }

    private fun pass() = AgentLoop.Preflight { AhResult.ok(Unit) }

    private fun call(name: String, argsJson: String = """{"ref":"[1]"}""", id: String = "c1") =
        ToolCall(id, name, argsJson, Json.parseToJsonElement(argsJson).let { it as JsonObject })

    private fun snapshot(label: String) = Json.parseToJsonElement(
        """{"package":"com.x","keyboard":"hidden","node_count":1,"nodes":[
            {"ref":"ref-1","pkg":"com.x","class":"android.widget.Button","id":"btn","text":"$label","desc":"",
             "bounds":[40,812,1040,940],"flags":["clickable"],"index":0}]}""",
    ) as JsonObject

    private fun loop(
        llm: FakeLlm,
        observer: AgentLoop.Observer = FakeObserver(snapshot("Go")),
        pipeline: FakePipeline = FakePipeline(),
        verifier: AgentLoop.FinishVerifier = FakeVerifier(AgentLoop.FinishResult.Confirmed),
        budget: RunBudget = RunBudget(
            RunBudget.Limits(20, 600_000, 40, 400_000, 50, maxOutputTokens = 256),
        ) { 0 },
        view: AgentView = AgentView(1080, 2400),
    ) = AgentLoop(
        client = llm,
        catalog = ToolCatalog.load(),
        pipeline = pipeline,
        observer = observer,
        preflight = pass(),
        finishVerifier = verifier,
        budget = budget,
        view = view,
    )

    // -- cases -----------------------------------------------------------------------------------

    @Test
    fun `given click click finish when run then the outcome is Succeeded and the completion protocol ran`() = runBlocking {
        val pipeline = FakePipeline()
        val verifier = FakeVerifier(AgentLoop.FinishResult.Confirmed)
        val llm = FakeLlm(
            listOf(
                FakeTurn.Resp(calls = listOf(call("ui_click", id = "c1"))),
                FakeTurn.Resp(calls = listOf(call("ui_click", id = "c2"))),
                FakeTurn.Resp(calls = listOf(call("finish", """{"summary":"did it"}"""))),
            ),
        )
        val outcome = loop(llm, pipeline = pipeline, verifier = verifier).run("tap the button")
        val succeeded = outcome as AgentLoop.Outcome.Succeeded
        assertEquals("did it", succeeded.claimedSummary)
        assertEquals(3, succeeded.turns)
        assertEquals(2, pipeline.calls.size)
        assertEquals(1, verifier.asked.size)
        assertEquals("did it", verifier.asked[0])
        // turn 1 has no previous generation; turn 2's context carries it (the STALE_OBSERVATION seam)
        assertEquals(1, pipeline.calls[0].second.generation)
        assertEquals(null, pipeline.calls[0].second.previousView)
        assertEquals(2, pipeline.calls[1].second.generation)
        assertNotNull(pipeline.calls[1].second.previousView)
    }

    @Test
    fun `given two tool calls in one message when run then the extra gets ONE_ACTION_PER_TURN and is never executed`() = runBlocking {
        val pipeline = FakePipeline()
        val llm = FakeLlm(
            listOf(
                FakeTurn.Resp(calls = listOf(call("ui_click", id = "a"), call("ui_set_text", """{"ref":"[1]","text":"x"}""", id = "b"))),
                FakeTurn.Resp(calls = listOf(call("finish", """{"summary":"done"}"""))),
            ),
        )
        val outcome = loop(llm, pipeline = pipeline).run("tap the button")
        assertTrue(outcome is AgentLoop.Outcome.Succeeded)
        assertEquals(1, pipeline.calls.size) // only the FIRST call executed
        assertEquals("ui_click", pipeline.calls[0].first.name)
        // the second request's history holds BOTH tool results; the extra's says ONE_ACTION_PER_TURN
        val turn2Messages = llm.requests[1].messages
        val toolContents = turn2Messages.filterIsInstance<ChatMessage.Tool>().map { it.content }
        assertEquals(2, toolContents.size)
        assertTrue(toolContents[1].contains("ONE_ACTION_PER_TURN"))
    }

    @Test
    fun `given a turn without tool calls when run then the nudge is injected and the second empty turn counts as a failure`() = runBlocking {
        val llm = FakeLlm(
            listOf(
                FakeTurn.Resp(text = "let me think"),
                FakeTurn.Resp(text = "still thinking"),
                FakeTurn.Resp(text = "and again"),
                FakeTurn.Resp(text = "never calls"),
            ),
        )
        val outcome = loop(llm).run("do something")
        val failed = outcome as AgentLoop.Outcome.Failed
        assertEquals("AGENT_FAILURES", failed.reason)
        // the second request carries the nudge
        val turn2 = llm.requests[1].messages
        assertTrue(turn2.filterIsInstance<ChatMessage.User>().any { it.text.contains("call exactly one tool or call finish") })
    }

    @Test
    fun `given a turn budget of one when the second turn starts then BUDGET_TURNS`() = runBlocking {
        val llm = FakeLlm(listOf(FakeTurn.Resp(text = "no call yet")))
        val budget = RunBudget(RunBudget.Limits(1, 600_000, 40, 400_000, 50, maxOutputTokens = 256)) { 0 }
        val outcome = loop(llm, budget = budget).run("do something")
        assertEquals("BUDGET_TURNS", (outcome as AgentLoop.Outcome.Failed).reason)
    }

    @Test
    fun `given three identical actions on a changing screen when run then AGENT_STUCK (the action stop)`() = runBlocking {
        // the observer returns a DIFFERENT snapshot each turn (the screen changes — the
        // observation rule must NOT fire); the identical (tool,args,target) rule stops it anyway
        val observer = FakeObserver(snapshot("v1"), snapshot("v2"), snapshot("v3"), snapshot("v4"))
        val llm = FakeLlm(
            listOf(
                FakeTurn.Resp(calls = listOf(call("ui_click"))),
                FakeTurn.Resp(calls = listOf(call("ui_click"))),
                FakeTurn.Resp(calls = listOf(call("ui_click"))),
            ),
        )
        val outcome = loop(llm, observer = observer).run("tap the button")
        val failed = outcome as AgentLoop.Outcome.Failed
        assertEquals("AGENT_STUCK", failed.reason)
        assertTrue(failed.message.contains("repeated 3 times"))
    }

    @Test
    fun `given five unchanged screens when run then AGENT_STUCK (the observation stop)`() = runBlocking {
        // the model keeps ACTING (rotating args — the action-repetition rule must not fire) but
        // the screen never changes: the state-aware observation run reaches 5 → stop
        val same = snapshot("Stuck")
        val observer = FakeObserver(same, same, same, same, same, same)
        val llm = FakeLlm(
            (1..4).map { n -> FakeTurn.Resp(calls = listOf(call("ui_click", """{"n":"$n"}"""))) },
        )
        val outcome = loop(llm, observer = observer).run("do something")
        val failed = outcome as AgentLoop.Outcome.Failed
        assertEquals("AGENT_STUCK", failed.reason)
        assertEquals("stuck: the screen has not changed for 5 steps", failed.message)
    }

    @Test
    fun `given three gateway errors when run then AGENT_FAILURES surfaces the MODEL_ reason`() = runBlocking {
        val llm = FakeLlm(
            listOf(
                FakeTurn.Err(LlmError.Unreachable("dns")),
                FakeTurn.Err(LlmError.Unreachable("dns")),
                FakeTurn.Err(LlmError.Unreachable("dns")),
            ),
        )
        val outcome = loop(llm).run("do something")
        val failed = outcome as AgentLoop.Outcome.Failed
        assertEquals("MODEL_UNREACHABLE", failed.reason)
        // and every failed call still charged an ESTIMATED reserve (unknown usage is never 0)
        assertTrue(0L < estimateOf(llm))
    }

    private fun estimateOf(llm: FakeLlm): Long = RunBudget.estimateTokens(llm.requests[0].messages.sumOf { msg ->
        when (msg) {
            is ChatMessage.User -> msg.text.length
            is ChatMessage.System -> msg.text.length
            is ChatMessage.Tool -> msg.content.length
            is ChatMessage.Assistant -> msg.text?.length ?: 0
        }
    })

    @Test
    fun `given finish rejected by the user when run then COMPLETION_REJECTED`() = runBlocking {
        val llm = FakeLlm(listOf(FakeTurn.Resp(calls = listOf(call("finish", """{"summary":"done"}""")))))
        val outcome = loop(llm, verifier = FakeVerifier(AgentLoop.FinishResult.Rejected)).run("do something")
        assertEquals("COMPLETION_REJECTED", (outcome as AgentLoop.Outcome.Failed).reason)
    }

    @Test
    fun `given finish that timed out when run then COMPLETION_UNVERIFIED (the claim is never the verdict)`() = runBlocking {
        val llm = FakeLlm(listOf(FakeTurn.Resp(calls = listOf(call("finish", """{"summary":"done"}""")))))
        val outcome = loop(llm, verifier = FakeVerifier(AgentLoop.FinishResult.Unverified)).run("do something")
        assertEquals("COMPLETION_UNVERIFIED", (outcome as AgentLoop.Outcome.Failed).reason)
    }

    @Test
    fun `given four turns when the last request is built then the stale screens are compacted to notes`() = runBlocking {
        val observer = FakeObserver(snapshot("v1"), snapshot("v2"), snapshot("v3"), snapshot("v4"))
        val llm = FakeLlm(
            listOf(
                FakeTurn.Resp(calls = listOf(call("ui_click", id = "c1"))),
                FakeTurn.Resp(calls = listOf(call("ui_click", id = "c2"))),
                FakeTurn.Resp(calls = listOf(call("ui_click", id = "c3"))),
                FakeTurn.Resp(calls = listOf(call("finish", """{"summary":"done"}"""))),
            ),
        )
        loop(llm, observer = observer).run("tap the button")
        val lastRequest = llm.requests[3].messages
        val screens = lastRequest.filterIsInstance<ChatMessage.User>().filter { it.text.startsWith("<untrusted_screen") }
        assertEquals("only the LATEST observation travels as a screen", 1, screens.size)
        assertTrue(screens[0].text.contains("gen=\"4\""))
        assertTrue(
            "the stale screens left one-line notes",
            lastRequest.filterIsInstance<ChatMessage.User>().any { it.text.contains("stale observation was removed") },
        )
    }

    @Test
    fun `given a tool result over the cap when the next request is built then the content is capped`() = runBlocking {
        val pipeline = FakePipeline()
        pipeline.result = buildJsonObject {
            put("ok", true)
            put("text", "x".repeat(9_000))
        }
        val llm = FakeLlm(
            listOf(
                FakeTurn.Resp(calls = listOf(call("ui_find", """{"query":"x"}"""))),
                FakeTurn.Resp(calls = listOf(call("finish", """{"summary":"done"}"""))),
            ),
        )
        loop(llm, pipeline = pipeline).run("find x")
        val toolMsg = llm.requests[1].messages.filterIsInstance<ChatMessage.Tool>().first()
        assertTrue(toolMsg.content.length < 9_000)
        assertTrue(toolMsg.content.endsWith("[truncated]\"}"))
    }

    @Test
    fun `given a preflight failure when run then no model call happens and the reason surfaces`() = runBlocking {
        val llm = FakeLlm(emptyList())
        val loop = AgentLoop(
            client = llm,
            catalog = ToolCatalog.load(),
            pipeline = FakePipeline(),
            observer = FakeObserver(snapshot("x")),
            preflight = AgentLoop.Preflight { AhResult.err(app.autohost.core.model.ErrorCode.NEEDS_SETUP, "no AI profile", reason = "AI_NOT_CONFIGURED") },
            finishVerifier = FakeVerifier(AgentLoop.FinishResult.Confirmed),
            budget = RunBudget(RunBudget.Limits(5, 60_000, 10, 100_000, 10, maxOutputTokens = 128)) { 0 },
            view = AgentView(1080, 2400),
        )
        val outcome = loop.run("goal")
        assertEquals("AI_NOT_CONFIGURED", (outcome as AgentLoop.Outcome.Failed).reason)
        assertEquals(0, llm.requests.size)
    }

    @Test
    fun `given an unknown tool when run then the model gets a typed error and the pipeline is never called`() = runBlocking {
        val pipeline = FakePipeline()
        val llm = FakeLlm(
            listOf(
                FakeTurn.Resp(calls = listOf(call("ui_tap", """{"x":1}"""))),
                FakeTurn.Resp(calls = listOf(call("finish", """{"summary":"done"}"""))),
            ),
        )
        val outcome = loop(llm, pipeline = pipeline).run("goal")
        assertTrue(outcome is AgentLoop.Outcome.Succeeded)
        assertEquals(0, pipeline.calls.size)
        val toolMsg = llm.requests[1].messages.filterIsInstance<ChatMessage.Tool>().first()
        assertTrue(toolMsg.content.contains("unknown tool"))
    }
}
