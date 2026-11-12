package app.autohost.runtime.agent

import app.autohost.core.common.AhResult
import app.autohost.core.llm.ChatMessage
import app.autohost.core.llm.ChatRequest
import app.autohost.core.llm.ChatResponse
import app.autohost.core.llm.FinishReason
import app.autohost.core.llm.LlmResult
import app.autohost.core.llm.LlmResults
import app.autohost.core.llm.ToolCall
import app.autohost.core.llm.Usage
import app.autohost.core.model.RunSpec
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P13-12 (AGENT_RUNTIME §4/§11): the provider and handle — the finished-event mapping, the
 * AGENT_INTERNAL_ERROR-not-worker-death rule, IMMEDIATE cancellation (no finished event, the
 * CancellationException propagates), the cooperative pause gate, and the fail-closed start
 * validation. Events are collected through the emit seam exactly as :app adapts the binder.
 */
class AgentRuntimeProviderTest {

    /** [thinkMs] > 0 makes the model call SUSPEND (virtual time), like a real HTTP call would. */
    private class ScriptedClient(
        private val calls: List<ToolCall>,
        private val thinkMs: Long = 0,
    ) : AgentLoop.ModelClient {
        override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> {
            if (thinkMs > 0) kotlinx.coroutines.delay(thinkMs)
            return LlmResults.ok(
                ChatResponse(null, calls, FinishReason.TOOL_CALLS, Usage(20, 10, 30, false), 5, 1),
            )
        }
    }

    private class ScriptedPipeline : AgentLoop.ToolExecutor {
        override suspend fun execute(call: ToolCall, ctx: AgentLoop.TurnContext): JsonObject =
            buildJsonObject { put("ok", true) }
    }

    private val staticSnapshot = Json.parseToJsonElement(
        """{"package":"com.android.settings","keyboard":"hidden","node_count":0,"nodes":[]}""",
    ) as JsonObject

    private fun spec(goal: String = "tap the thing", packages: String = "com.android.settings") = RunSpec(
        runId = "run-1",
        taskId = "builtin.agent.adhoc",
        runtime = "agent",
        entry = "agent",
        taskPath = "",
        runToken = "tok-1",
        config = buildJsonObject {
            put(
                "agent",
                buildJsonObject {
                    put("goal", goal)
                    put("packages", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(packages))))
                },
            )
        },
    )

    private class Emitted {
        val events = mutableListOf<JsonObject>()
        val finished get() = events.firstOrNull { it["type"]?.let { t -> (t as JsonPrimitive).content } == "finished" }

        fun status(): String? = finished?.let { ((it["data"] as JsonObject)["status"]) as? JsonPrimitive }?.content
        fun reason(): String? = finished?.let { ((it["data"] as JsonObject)["reason"]) as? JsonPrimitive }?.content
        fun message(): String? = finished?.let { ((it["data"] as JsonObject)["message"]) as? JsonPrimitive }?.content
        fun heartbeats() = events.count { (it["type"] as? JsonPrimitive)?.content == "heartbeat" }
    }

    private fun provider(
        scope: kotlinx.coroutines.CoroutineScope,
        emitted: Emitted,
        client: AgentLoop.ModelClient,
        deathLog: MutableList<String> = mutableListOf(),
    ): AgentRuntimeProvider {
        // The run must live on the TEST dispatcher: with Dispatchers.Unconfined a loop whose fakes never suspend
        // finishes inside start() (so requestPause() comes too late and the heartbeat child is cancelled before it
        // ever runs), and delay() would use real time instead of the test clock.
        val testDispatcher = scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher
        return AgentRuntimeProvider(
            dispatchers = app.autohost.core.common.AppDispatchers(
                main = testDispatcher,
                io = testDispatcher,
                default = testDispatcher,
            ),
            scope = scope,
            emit = { _, json -> emitted.events.add(Json.parseToJsonElement(json) as JsonObject) },
            loopFactory = { _, sink, gate ->
                AgentLoop(
                    sink = sink,
                    pauseGate = gate, // the provider's cooperative pause — without it requestPause() is a no-op
                    client = client,
                    catalog = ToolCatalog.load(),
                    pipeline = ScriptedPipeline(),
                    observer = { staticSnapshot },
                    preflight = { AhResult.ok(Unit) },
                    finishVerifier = { AgentLoop.FinishResult.Confirmed },
                    budget = RunBudget(AgentManifests.DEFAULT_BUDGETS.toLimits()) { 0 },
                    view = AgentView(1080, 2400),
                )
            },
        ).also { it.setDeathListener { runId -> deathLog.add(runId) } }
    }

    /**
     * Runs the provider's coroutines to completion. advanceUntilIdle() is NOT enough here: the run lives in
     * backgroundScope, and advanceUntilIdle() returns as soon as only background work is left, i.e. at once.
     * Ten virtual seconds is far beyond any scripted run (the 2 s heartbeat is cancelled when the run ends).
     */
    private fun kotlinx.coroutines.test.TestScope.runToCompletion() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    @Test
    fun `given a finishing loop when the run completes then SUCCEEDED with a finished event and heartbeats`() = runTest {
        val emitted = Emitted()
        val deathLog = mutableListOf<String>()
        val provider = provider(
            backgroundScope, emitted,
            // the call suspends 10 ms (virtual): that is when the heartbeat coroutine gets its first turn
            ScriptedClient(
                listOf(ToolCall("c1", "finish", """{"summary":"done"}""", buildJsonObject { put("summary", "done") })),
                thinkMs = 10,
            ),
            deathLog,
        )
        provider.start(spec())
        runToCompletion()
        assertEquals("SUCCEEDED", emitted.status())
        assertNull(emitted.reason())
        assertTrue(emitted.heartbeats() >= 1)
        assertTrue("no death on a normal finish", deathLog.isEmpty())
    }

    @Test
    fun `given a failing loop when the run completes then FAILED with the loop reason`() = runTest {
        val emitted = Emitted()
        val provider = provider(backgroundScope, emitted, ScriptedClient(emptyList()))
        // An EMPTY tool-call script: turn 1 gets the one free nudge, every further empty turn is a failure, and the
        // third consecutive failure ends the run with AGENT_FAILURES (turn 4) — before the stuck detector (5 identical
        // screens) or the 30-turn budget could.
        provider.start(spec())
        runToCompletion()
        assertEquals("FAILED", emitted.status())
        assertTrue(emitted.reason()!!.startsWith("BUDGET_") || emitted.reason() == "AGENT_FAILURES")
    }

    @Test
    fun `given a loop that throws when run then AGENT_INTERNAL_ERROR with the class name only and NO death`() = runTest {
        val emitted = Emitted()
        val deathLog = mutableListOf<String>()
        val provider = AgentRuntimeProvider(
            dispatchers = app.autohost.core.common.AppDispatchers(
                main = kotlinx.coroutines.Dispatchers.Unconfined,
                io = kotlinx.coroutines.Dispatchers.Unconfined,
                default = kotlinx.coroutines.Dispatchers.Unconfined,
            ),
            scope = backgroundScope,
            emit = { _, json -> emitted.events.add(Json.parseToJsonElement(json) as JsonObject) },
            loopFactory = { _, _, _ ->
                throw java.lang.IllegalStateException("the agent start path is wired in P13-13 (AGENT_RUNTIME §4)")
            },
        ).also { it.setDeathListener { runId -> deathLog.add(runId) } }
        // the factory throws INSIDE the coroutine → catch(Throwable) → AGENT_INTERNAL_ERROR
        provider.start(spec())
        advanceUntilIdle()
        assertEquals("FAILED", emitted.status())
        assertEquals("AGENT_INTERNAL_ERROR", emitted.reason())
        assertEquals("IllegalStateException", emitted.message()) // class name only — no content
        assertTrue("internal error is NOT worker death", deathLog.isEmpty())
    }

    @Test
    fun `given requestCancel while the model call is in flight when cancelled then no finished event and cancellation propagates`() = runTest {
        val emitted = Emitted()
        val provider = provider(backgroundScope, emitted, object : AgentLoop.ModelClient {
            override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> {
                awaitCancellation() // the in-flight HTTP call
            }
        })
        val handle = provider.start(spec())
        advanceTimeBy(50)
        runCurrent() // the run is inside the model call now
        handle.requestCancel() // IMMEDIATE: interrupts the suspension point
        runToCompletion()
        assertNull("a cancelled run emits no finished event (the orchestrator owns CANCELLED)", emitted.finished)
        assertTrue(emitted.events.none { (it["type"] as? JsonPrimitive)?.content == "finished" })
    }

    @Test
    fun `given a pause before the first turn when resumed then the run completes (pause is cooperative)`() = runTest {
        val emitted = Emitted()
        val provider = provider(
            backgroundScope, emitted,
            ScriptedClient(listOf(ToolCall("c1", "finish", """{"summary":"done"}""", buildJsonObject { put("summary", "done") }))),
        )
        val handle = provider.start(spec())
        handle.requestPause() // before the first turn
        advanceTimeBy(100)
        runCurrent()
        assertNull("a paused run starts no new turn", emitted.finished)
        handle.requestResume()
        runToCompletion()
        assertEquals("SUCCEEDED", emitted.status())
    }

    @Test
    fun `given a blank goal when started then refused fail-closed`() = runTest {
        val provider = provider(backgroundScope, Emitted(), ScriptedClient(emptyList()))
        try {
            provider.start(spec(goal = "  "))
            fail("a blank goal must refuse to start")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("goal"))
        }
    }

    @Test
    fun `given no packages when started then refused fail-closed (targets are explicit, ≥ 1)`() = runTest {
        val provider = provider(backgroundScope, Emitted(), ScriptedClient(emptyList()))
        val bad = spec().copy(config = buildJsonObject {
            put("agent", buildJsonObject { put("goal", "x"); put("packages", kotlinx.serialization.json.JsonArray(emptyList())) })
        })
        try {
            provider.start(bad)
            fail("empty packages must refuse to start")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("packages"))
        }
    }
}
