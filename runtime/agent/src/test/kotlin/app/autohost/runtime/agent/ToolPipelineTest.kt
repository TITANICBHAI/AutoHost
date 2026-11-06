package app.autohost.runtime.agent

import app.autohost.core.llm.ToolCall
import app.autohost.core.model.Bounds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P13-11 (AGENT_RUNTIME §10): the pipeline chain — validate → policy → approval → TOCTOU →
 * budget reserve → BrokerTransport.call → cap. Every stage is scripted; the assertions check
 * ORDER (a stage that ran when it must not fails the test) and the typed results.
 */
class ToolPipelineTest {

    private val catalog = ToolCatalog.load()
    private val sensitivity = AppSensitivity(
        userAdded = mapOf("com.normal.test" to AppTier.NORMAL, "com.bank.test" to AppTier.BLOCKED),
    )
    private val packages = setOf("com.normal.test", "com.bank.test", "com.unknown.test")

    private class FakeTransport : BrokerTransport {
        val calls = mutableListOf<Triple<String, String, JsonObject>>()
        var responses = mutableMapOf<String, JsonObject>() // keyed by "permission.method"

        override suspend fun call(permission: String, method: String, args: JsonObject): JsonObject {
            calls.add(Triple(permission, method, args))
            return responses["$permission.$method"] ?: buildJsonObject { put("ok", true) }
        }

        fun brokerCalls() = calls.count { it.first != "ui.prompt" }
        fun askCalls() = calls.count { it.first == "ui.prompt" && it.second == "ask" }
    }

    private class FakeRevalidator(
        private val re: ToolPipeline.Revalidator.Revalidation =
            ToolPipeline.Revalidator.Revalidation("com.normal.test", "com.normal.test", true),
    ) : ToolPipeline.Revalidator {
        val asked = mutableListOf<String>()
        override suspend fun revalidate(tool: ToolCatalog.Tool, target: ToolPipeline.TargetInfo?): Revalidation {
            asked.add(tool.name)
            return re
        }
    }

    private fun budget(maxActions: Int = 10) = RunBudget(
        RunBudget.Limits(50, 600_000, 100, 10_000_000, maxActions, maxOutputTokens = 256),
    ) { 0 }

    private fun pipeline(
        transport: FakeTransport = FakeTransport(),
        budget: RunBudget = budget(),
        revalidator: FakeRevalidator = FakeRevalidator(),
        foreground: String? = "com.normal.test",
    ) = ToolPipeline(
        catalog = catalog,
        transport = transport,
        budget = budget,
        packages = packages,
        sensitivity = sensitivity,
        foregroundSource = { foreground },
        revalidator = revalidator,
    )

    private fun call(name: String, argsJson: String = "{}", id: String = "c1") =
        ToolCall(id, name, argsJson, Json.parseToJsonElement(argsJson) as? JsonObject)

    /** A ViewIndex with one entry: [1] = a clickable button "OK" in com.normal.test (no hint label). */
    private fun viewIndex(pkg: String = "com.normal.test", label: String = "OK") =
        AgentView.ViewIndex(
            1,
            mapOf(1 to AgentView.Entry("ref-1", pkg, setOf("clickable"), Bounds(0, 0, 100, 50), label)),
        )

    private val ctx = AgentLoop.TurnContext(1, viewIndex(), null)

    /** Returns (code, reason) of the result's error envelope. */
    private fun errOf(result: JsonObject): Pair<String?, String?> =
        (result["error"] as? JsonObject)?.let {
            (it["code"] as? JsonPrimitive)?.content to (it["reason"] as? JsonPrimitive)?.content
        } ?: (null to null)

    // --- validation ------------------------------------------------------------------------------

    @Test
    fun `given an unknown argument when executed then INVALID_ARGS and the broker is never called`() = runBlocking {
        val transport = FakeTransport()
        val result = pipeline(transport).execute(call("ui_click", """{"ref":"[1]"}"""), ctx)
        assertEquals("INVALID_ARGS", errOf(result).first)
        assertEquals(0, transport.brokerCalls())
    }

    @Test
    fun `given a package outside the run scope when app_launch is executed then CAPABILITY_NOT_GRANTED`() = runBlocking {
        val result = pipeline().execute(call("app_launch", """{"package":"com.other.app"}"""), ctx)
        assertEquals("CAPABILITY_NOT_GRANTED", errOf(result).second)
    }

    @Test
    fun `given a missing required argument when executed then INVALID_ARGS`() = runBlocking {
        val result = pipeline().execute(call("ui_set_text", """{"id":"[1]"}"""), ctx)
        assertEquals("INVALID_ARGS", errOf(result).first)
    }

    // --- policy denies (no broker, no ask) -------------------------------------------------------

    @Test
    fun `given a blocked target when executed then denied and nothing reaches the device`() = runBlocking {
        val transport = FakeTransport()
        val blockedCtx = AgentLoop.TurnContext(1, viewIndex(pkg = "com.bank.test"), null)
        val result = pipeline(transport).execute(call("ui_click", """{"id":"[1]"}"""), blockedCtx)
        assertEquals("OBSERVATION_BLOCKED", errOf(result).second)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `given set_text on a password entry when executed then PASSWORD_FIELD and no broker call`() = runBlocking {
        val transport = FakeTransport()
        val pwCtx = AgentLoop.TurnContext(
            1,
            AgentView.ViewIndex(1, mapOf(1 to AgentView.Entry("ref-1", "com.normal.test", setOf("password", "editable"), Bounds(0, 0, 100, 50), ""))),
            null,
        )
        val result = pipeline(transport).execute(call("ui_set_text", """{"id":"[1]","text":"x"}"""), pwCtx)
        assertEquals("PASSWORD_FIELD", errOf(result).second)
        assertEquals(0, transport.brokerCalls())
    }

    @Test
    fun `given a stale id when executed then STALE_OBSERVATION from the ViewIndex resolution`() = runBlocking {
        val otherGen = AgentView.ViewIndex(2, mapOf(9 to AgentView.Entry("ref-9", "com.normal.test", setOf("clickable"), Bounds(0, 0, 10, 10))))
        val staleCtx = AgentLoop.TurnContext(2, otherGen, AgentLoop.TurnContext(1, viewIndex(), null).view)
        val result = pipeline().execute(call("ui_click", """{"id":"[1]"}"""), staleCtx)
        assertEquals("STALE_OBSERVATION", errOf(result).second)
    }

    // --- approvals -------------------------------------------------------------------------------

    @Test
    fun `given a cautious app when the approval times out then APPROVAL_TIMEOUT (deny and stop the run)`() = runBlocking {
        val transport = FakeTransport()
        transport.responses["ui.prompt.ask"] = buildJsonObject { put("answered", false); put("reason", "timeout") }
        val unknownCtx = AgentLoop.TurnContext(
            1,
            AgentView.ViewIndex(1, mapOf(1 to AgentView.Entry("ref-1", "com.unknown.test", setOf("clickable"), Bounds(0, 0, 100, 50), "Go"))),
            null,
        )
        val result = pipeline(transport).execute(
            call("ui_click", """{"id":"[1]"}"""),
            unknownCtx,
        )
        assertEquals("APPROVAL_TIMEOUT", errOf(result).second)
        assertEquals(1, transport.askCalls())
        assertEquals(0, transport.brokerCalls()) // the device was never touched
        // the ask was a proper choice prompt with the documented choices
        val askArgs = transport.calls[0].third
        assertEquals("choice", (askArgs["kind"] as JsonPrimitive).content)
        assertEquals(60_000L, (askArgs["timeout_ms"] as JsonPrimitive).content.toLong())
    }

    @Test
    fun `given the user denies when executed then APPROVAL_DENIED and the model may continue`() = runBlocking {
        val transport = FakeTransport()
        transport.responses["ui.prompt.ask"] = buildJsonObject { put("answered", true); put("button", "Deny") }
        val unknownCtx = AgentLoop.TurnContext(
            1,
            AgentView.ViewIndex(1, mapOf(1 to AgentView.Entry("ref-1", "com.unknown.test", setOf("clickable"), Bounds(0, 0, 100, 50), "Go"))),
            null,
        )
        val result = pipeline(transport).execute(call("ui_click", """{"id":"[1]"}"""), unknownCtx)
        assertEquals("APPROVAL_DENIED", errOf(result).second)
        assertEquals(0, transport.brokerCalls())
    }

    @Test
    fun `given allow once when executed then the TOCTOU passes and the broker gets the sanitized args`() = runBlocking {
        val transport = FakeTransport()
        transport.responses["ui.prompt.ask"] = buildJsonObject { put("answered", true); put("button", "Allow once") }
        val revalidator = FakeRevalidator()
        val unknownCtx = AgentLoop.TurnContext(
            1,
            AgentView.ViewIndex(1, mapOf(1 to AgentView.Entry("ref-1", "com.unknown.test", setOf("clickable"), Bounds(0, 0, 100, 50), "Go"))),
            null,
        )
        val result = pipeline(transport, revalidator = revalidator).execute(
            call("ui_click", """{"id":"[1]","expect":"shown"}"""),
            unknownCtx,
        )
        assertNull(errOf(result).first)
        assertEquals(1, transport.brokerCalls())
        assertEquals(1, revalidator.asked.size) // the TOCTOU ran
        val brokerArgs = transport.calls[1].third
        assertEquals("[1]", (brokerArgs["id"] as JsonPrimitive).content)
        assertEquals("shown", (brokerArgs["expect"] as JsonPrimitive).content)
        assertTrue(!brokerArgs.containsKey("nonexistent"))
    }

    @Test
    fun `given allow for this run when executed then the next same-package action does not ask again`() = runBlocking {
        val transport = FakeTransport()
        transport.responses["ui.prompt.ask"] = buildJsonObject { put("answered", true); put("button", "Allow for this run") }
        val p = pipeline(transport)
        val unknownCtx = AgentLoop.TurnContext(
            1,
            AgentView.ViewIndex(1, mapOf(1 to AgentView.Entry("ref-1", "com.unknown.test", setOf("clickable"), Bounds(0, 0, 100, 50), "Go"))),
            null,
        )
        p.execute(call("ui_click", """{"id":"[1]"}"""), unknownCtx)
        p.execute(call("ui_click", """{"id":"[1]"}"""), unknownCtx)
        assertEquals(1, transport.askCalls()) // the SECOND call skipped the ask
        assertEquals(2, transport.brokerCalls())
    }

    @Test
    fun `given the screen moved to a blocked app during the ask when revalidated then OBSERVATION_BLOCKED`() = runBlocking {
        val transport = FakeTransport()
        transport.responses["ui.prompt.ask"] = buildJsonObject { put("answered", true); put("button", "Allow once") }
        val revalidator = FakeRevalidator(
            ToolPipeline.Revalidator.Revalidation("com.bank.test", "com.normal.test", true),
        )
        val unknownCtx = AgentLoop.TurnContext(
            1,
            AgentView.ViewIndex(1, mapOf(1 to AgentView.Entry("ref-1", "com.unknown.test", setOf("clickable"), Bounds(0, 0, 100, 50), "Go"))),
            null,
        )
        val result = pipeline(transport, revalidator = revalidator).execute(call("ui_click", """{"id":"[1]"}"""), unknownCtx)
        assertEquals("OBSERVATION_BLOCKED", errOf(result).second)
        assertEquals(0, transport.brokerCalls())
    }

    @Test
    fun `given the approved target gone when revalidated then STALE_NODE`() = runBlocking {
        val transport = FakeTransport()
        transport.responses["ui.prompt.ask"] = buildJsonObject { put("answered", true); put("button", "Allow once") }
        val revalidator = FakeRevalidator(
            ToolPipeline.Revalidator.Revalidation("com.normal.test", null, false),
        )
        val unknownCtx = AgentLoop.TurnContext(
            1,
            AgentView.ViewIndex(1, mapOf(1 to AgentView.Entry("ref-1", "com.unknown.test", setOf("clickable"), Bounds(0, 0, 100, 50), "Go"))),
            null,
        )
        val result = pipeline(transport, revalidator = revalidator).execute(call("ui_click", """{"id":"[1]"}"""), unknownCtx)
        assertEquals("STALE_NODE", errOf(result).second)
    }

    // --- budget and caps -------------------------------------------------------------------------

    @Test
    fun `given the action budget used up when executed then BUDGET_ACTIONS (the reserve lives in the pipeline)`() = runBlocking {
        val transport = FakeTransport()
        val b = budget(maxActions = 1)
        val p = pipeline(transport, budget = b)
        p.execute(call("ui_click", """{"id":"[1]"}"""), ctx) // reserves the one action
        val result = p.execute(call("ui_click", """{"id":"[1]"}"""), ctx)
        assertEquals("BUDGET_ACTIONS", errOf(result).second)
        assertEquals(1, transport.brokerCalls())
    }

    @Test
    fun `given host tools when executed then ok with host=true and no budget or broker`() = runBlocking {
        val transport = FakeTransport()
        val b = budget(maxActions = 1)
        val p = pipeline(transport, budget = b)
        val wait = p.execute(call("wait", """{"seconds":3}"""), ctx)
        assertEquals("true", (wait["ok"] as JsonPrimitive).content)
        assertEquals("true", (wait["host"] as JsonPrimitive).content)
        assertEquals(0, transport.calls.size)
        // the host tool did not consume the action budget
        p.execute(call("ui_click", """{"id":"[1]"}"""), ctx)
        assertEquals(1, transport.brokerCalls())
    }

    @Test
    fun `given a huge broker result when executed then the model sees the 12k-capped preview`() = runBlocking {
        val transport = FakeTransport()
        transport.responses["device.ui.click"] = buildJsonObject {
            put("ok", true)
            put("text", "y".repeat(20_000))
        }
        val result = pipeline(transport).execute(call("ui_click", """{"id":"[1]"}"""), ctx)
        assertEquals("true", (result["truncated"] as JsonPrimitive).content)
        assertTrue((result["preview"] as JsonPrimitive).content.length <= 12_000)
    }

    @Test
    fun `given a normal app when executed then no ask and one broker call (the happy path order)`() = runBlocking {
        val transport = FakeTransport()
        val result = pipeline(transport).execute(call("ui_click", """{"id":"[1]"}"""), ctx)
        assertEquals("true", (result["ok"] as JsonPrimitive).content)
        assertEquals(0, transport.askCalls())
        assertEquals(1, transport.brokerCalls())
        assertEquals("device.ui" to "click", transport.calls[0].first to transport.calls[0].second)
    }
}
