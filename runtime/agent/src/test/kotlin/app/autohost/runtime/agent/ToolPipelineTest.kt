package app.autohost.runtime.agent

import app.autohost.core.llm.ToolCall
import app.autohost.core.model.Bounds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        override suspend fun revalidate(tool: ToolCatalog.Tool, target: ToolPipeline.TargetInfo?): ToolPipeline.Revalidator.Revalidation {
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
        // INT-A4: the phone service is given the screen item's own handle, never the model's [n] number
        assertEquals("ref-1", (brokerArgs["ref"] as JsonPrimitive).content)
        assertFalse(brokerArgs.containsKey("id"))
        assertEquals("shown", (brokerArgs["expect"] as JsonPrimitive).content)
        assertTrue(!brokerArgs.containsKey("nonexistent"))
    }

    // --- INT-A4: the model's [n] number reaches the phone service as the screen item's own handle ----------

    @Test
    fun `given a click by number when executed then the phone service gets the item's ref and no id`() = runBlocking {
        val transport = FakeTransport()
        val result = pipeline(transport).execute(call("ui_click", """{"id":"[1]"}"""), ctx)
        assertNull(errOf(result).first)
        val sent = transport.calls.single()
        assertEquals("device.ui", sent.first)
        assertEquals("click", sent.second)
        assertEquals("ref-1", (sent.third["ref"] as JsonPrimitive).content)
        assertFalse(sent.third.containsKey("id"))
    }

    @Test
    fun `given a long press by number when executed then the phone service gets the item's ref and no id`() = runBlocking {
        val transport = FakeTransport()
        val result = pipeline(transport).execute(call("ui_long_click", """{"id":"[1]"}"""), ctx)
        assertNull(errOf(result).first)
        val sent = transport.calls.single()
        assertEquals("long_click", sent.second)
        assertEquals("ref-1", (sent.third["ref"] as JsonPrimitive).content)
        assertFalse(sent.third.containsKey("id"))
    }

    @Test
    fun `given typing by number when executed then the text goes along with the item's ref`() = runBlocking {
        val transport = FakeTransport()
        val result = pipeline(transport).execute(call("ui_set_text", """{"id":"[1]","text":"hello"}"""), ctx)
        assertNull(errOf(result).first)
        val sent = transport.calls.single()
        assertEquals("set_text", sent.second)
        assertEquals("ref-1", (sent.third["ref"] as JsonPrimitive).content)
        assertEquals("hello", (sent.third["text"] as JsonPrimitive).content)
        assertFalse(sent.third.containsKey("id"))
    }

    @Test
    fun `given the number is written without brackets when executed then it still reaches the phone service as the ref`() = runBlocking {
        val transport = FakeTransport()
        pipeline(transport).execute(call("ui_click", """{"id":"1"}"""), ctx)
        assertEquals("ref-1", (transport.calls.single().third["ref"] as JsonPrimitive).content)
    }

    @Test
    fun `given a selector and no number when executed then no ref is made up`() = runBlocking {
        val transport = FakeTransport()
        pipeline(transport).execute(call("ui_click", """{"selector":"Next"}"""), ctx)
        assertFalse(transport.calls.single().third.containsKey("ref"))
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
        assertEquals("STALE_NODE", errOf(result).first) // STALE_NODE is an error code; it carries no reason
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

    // --- INT-E1: run_task (a saved task the person allowed for this run) -------------------------

    private val allowedTasks = setOf("daily.upload", "make.video")
    private val taskNames = mapOf("daily.upload" to "Daily upload", "make.video" to "Make the video")

    /** INT-E4: only the upload task takes values; the video task takes none. */
    private val declaredInputs = mapOf("daily.upload" to setOf("title", "description"))

    private fun taskPipeline(
        transport: FakeTransport = FakeTransport(),
        revalidator: FakeRevalidator = FakeRevalidator(),
        foreground: String? = "com.normal.test",
        tasks: Set<String> = allowedTasks,
        taskInputs: Map<String, Set<String>> = declaredInputs,
    ) = ToolPipeline(
        catalog = catalog,
        transport = transport,
        budget = budget(),
        packages = packages,
        sensitivity = sensitivity,
        foregroundSource = { foreground },
        revalidator = revalidator,
        tasks = tasks,
        taskNames = taskNames,
        taskInputs = taskInputs,
    )

    private fun answer(transport: FakeTransport, button: String) {
        transport.responses["ui.prompt.ask"] = buildJsonObject { put("answered", true); put("button", button) }
    }

    private fun taskCalls(transport: FakeTransport) = transport.calls.filter { it.first == "tasks.run" }

    @Test
    fun `given a task the person did not allow when run_task is executed then CAPABILITY_NOT_GRANTED with no ask and no call`() = runBlocking {
        val transport = FakeTransport()
        val result = taskPipeline(transport).execute(call("run_task", """{"task":"other.task"}"""), ctx)
        assertEquals("CAPABILITY_NOT_GRANTED", errOf(result).second)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `given a run with no allowed tasks when run_task is executed then nothing is ever asked or started`() = runBlocking {
        val transport = FakeTransport()
        val result = taskPipeline(transport, tasks = emptySet()).execute(call("run_task", """{"task":"daily.upload"}"""), ctx)
        assertEquals("CAPABILITY_NOT_GRANTED", errOf(result).second)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `given the model passes its own args object when run_task is executed then INVALID_ARGS (values go through inputs, never a raw args object)`() = runBlocking {
        val transport = FakeTransport()
        val result = taskPipeline(transport).execute(call("run_task", """{"task":"daily.upload","args":{"x":1}}"""), ctx)
        assertEquals("INVALID_ARGS", errOf(result).first)
        assertEquals(0, transport.calls.size)
    }

    // --- INT-E4: values the agent hands a saved task ----------------------------------------------

    @Test
    fun `given values the task declares when run_task is executed then the person sees them and the broker gets them as args`() = runBlocking {
        val transport = FakeTransport()
        answer(transport, "Allow once")
        transport.responses["tasks.run.run"] = buildJsonObject {
            put("run_id", "r1")
            put("status", "SUCCEEDED")
        }
        val result = taskPipeline(transport).execute(
            call("run_task", """{"task":"daily.upload","inputs":["title=My cat video","description=Two\nlines here"]}"""),
            ctx,
        )
        assertEquals("SUCCEEDED", (result["status"] as JsonPrimitive).content)
        val askMessage = (transport.calls[0].third["message"] as JsonPrimitive).content
        assertTrue(askMessage, askMessage.contains("title: My cat video"))
        assertTrue(askMessage, askMessage.contains("description: Two lines here"))
        val started = taskCalls(transport).single().third
        assertFalse("the model's own name for them never reaches the broker", started.containsKey("inputs"))
        val given = started["args"] as JsonObject
        assertEquals("My cat video", (given["title"] as JsonPrimitive).content)
        assertEquals("Two\nlines here", (given["description"] as JsonPrimitive).content)
        assertEquals("daily.upload", (started["task"] as JsonPrimitive).content)
    }

    @Test
    fun `given a value name the task does not declare when run_task is executed then INVALID_ARGS before anyone is asked`() = runBlocking {
        val transport = FakeTransport()
        val result = taskPipeline(transport).execute(
            call("run_task", """{"task":"daily.upload","inputs":["title=x","password=hunter2"]}"""),
            ctx,
        )
        assertEquals("INVALID_ARGS", errOf(result).first)
        val message = ((result["error"] as JsonObject)["message"] as JsonPrimitive).content
        assertTrue(message, message.contains("title") && message.contains("description") && message.contains("password"))
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `given a task that takes no values when it is given some then INVALID_ARGS and nothing is asked`() = runBlocking {
        val transport = FakeTransport()
        val result = taskPipeline(transport).execute(call("run_task", """{"task":"make.video","inputs":["title=x"]}"""), ctx)
        assertEquals("INVALID_ARGS", errOf(result).first)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `given allow for this run on a task when a later call carries values then the person is asked again`() = runBlocking {
        val transport = FakeTransport()
        answer(transport, "Allow for this run")
        val p = taskPipeline(transport)
        p.execute(call("run_task", """{"task":"daily.upload"}"""), ctx)
        p.execute(call("run_task", """{"task":"daily.upload"}"""), ctx)
        assertEquals("without values the task is not asked about twice", 1, transport.askCalls())
        p.execute(call("run_task", """{"task":"daily.upload","inputs":["title=First"]}"""), ctx)
        p.execute(call("run_task", """{"task":"daily.upload","inputs":["title=Second"]}"""), ctx)
        assertEquals("every call with values asks", 3, transport.askCalls())
    }

    @Test
    fun `given values on a call when the person is asked then Allow for this run is not on offer`() = runBlocking {
        val transport = FakeTransport()
        answer(transport, "Allow once")
        val p = taskPipeline(transport)
        p.execute(call("run_task", """{"task":"daily.upload","inputs":["title=x"]}"""), ctx)
        p.execute(call("run_task", """{"task":"daily.upload"}"""), ctx)
        val asks = transport.calls.filter { it.first == "ui.prompt" }
        val withValues = (asks[0].third["choices"] as JsonArray).map { (it as JsonPrimitive).content }
        val without = (asks[1].third["choices"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("Allow once", "Deny"), withValues)
        assertTrue(without.contains("Allow for this run"))
    }

    @Test
    fun `given more values than fit in the question when the person is asked then three are shown and the rest counted`() = runBlocking {
        val transport = FakeTransport()
        answer(transport, "Allow once")
        val p = taskPipeline(transport, taskInputs = mapOf("daily.upload" to setOf("a", "b", "c", "d", "e")))
        p.execute(call("run_task", """{"task":"daily.upload","inputs":["a=1","b=2","c=3","d=4","e=5"]}"""), ctx)
        val message = (transport.calls[0].third["message"] as JsonPrimitive).content
        assertTrue(message, message.contains("a: 1") && message.contains("c: 3"))
        assertFalse(message, message.contains("d: 4"))
        assertTrue(message, message.contains("2 more"))
    }

    @Test
    fun `given a very long value when the person is asked then it is cut to one short line`() = runBlocking {
        val transport = FakeTransport()
        answer(transport, "Allow once")
        val long = "word ".repeat(200)
        taskPipeline(transport).execute(call("run_task", """{"task":"daily.upload","inputs":["description=$long"]}"""), ctx)
        val message = (transport.calls[0].third["message"] as JsonPrimitive).content
        assertTrue(message, message.length < 200)
        assertEquals("one line per value", 3, message.lines().size)
    }

    @Test
    fun `given an allowed task approved once when run_task is executed then the broker starts exactly that task with a bounded wait`() = runBlocking {
        val transport = FakeTransport()
        answer(transport, "Allow once")
        transport.responses["tasks.run.run"] = buildJsonObject {
            put("run_id", "r1")
            put("status", "SUCCEEDED")
        }
        // the person is somewhere outside the target apps: a saved task can still be started from there
        val revalidator = FakeRevalidator(ToolPipeline.Revalidator.Revalidation("com.elsewhere.test", null, true))
        val result = taskPipeline(transport, revalidator = revalidator)
            .execute(call("run_task", """{"task":"daily.upload"}"""), ctx)
        assertEquals("SUCCEEDED", (result["status"] as JsonPrimitive).content)
        assertEquals(1, transport.askCalls())
        val askMessage = (transport.calls[0].third["message"] as JsonPrimitive).content
        assertTrue(askMessage, askMessage.contains("Daily upload"))
        assertTrue(askMessage, !askMessage.contains("run_task"))
        val started = taskCalls(transport)
        assertEquals(1, started.size)
        assertEquals("run", started[0].second)
        assertEquals("daily.upload", (started[0].third["task"] as JsonPrimitive).content)
        assertEquals(ToolPipeline.DEFAULT_TASK_WAIT_MS, (started[0].third["timeout_ms"] as JsonPrimitive).content.toLong())
    }

    @Test
    fun `given a wait chosen by the model when run_task is executed then it is used up to 120 s and refused above`() = runBlocking {
        val transport = FakeTransport()
        answer(transport, "Allow once")
        val p = taskPipeline(transport)
        p.execute(call("run_task", """{"task":"daily.upload","timeout_ms":30000}"""), ctx)
        assertEquals(30_000L, (taskCalls(transport)[0].third["timeout_ms"] as JsonPrimitive).content.toLong())
        val tooLong = p.execute(call("run_task", """{"task":"make.video","timeout_ms":600000}"""), ctx)
        assertEquals("INVALID_ARGS", errOf(tooLong).first)
        assertEquals(1, taskCalls(transport).size)
    }

    @Test
    fun `given the person denies or does not answer when run_task is executed then nothing starts`() = runBlocking {
        val denied = FakeTransport()
        answer(denied, "Deny")
        val deniedResult = taskPipeline(denied).execute(call("run_task", """{"task":"daily.upload"}"""), ctx)
        assertEquals("APPROVAL_DENIED", errOf(deniedResult).second)
        assertEquals(0, taskCalls(denied).size)

        val silent = FakeTransport()
        silent.responses["ui.prompt.ask"] = buildJsonObject { put("answered", false); put("reason", "timeout") }
        val silentResult = taskPipeline(silent).execute(call("run_task", """{"task":"daily.upload"}"""), ctx)
        assertEquals("APPROVAL_TIMEOUT", errOf(silentResult).second)
        assertEquals(0, taskCalls(silent).size)
    }

    @Test
    fun `given a blocked app on screen when run_task is executed then denied before the person is asked`() = runBlocking {
        val transport = FakeTransport()
        val result = taskPipeline(transport, foreground = "com.bank.test")
            .execute(call("run_task", """{"task":"daily.upload"}"""), ctx)
        assertEquals("OBSERVATION_BLOCKED", errOf(result).second)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `given allow for this run on a task when later calls run then only that task skips the ask and no app is loosened`() = runBlocking {
        val transport = FakeTransport()
        answer(transport, "Allow for this run")
        // an unknown (cautious) app is on screen while the task starts — the old rule would have trusted that app
        val p = taskPipeline(transport, foreground = "com.unknown.test")
        p.execute(call("run_task", """{"task":"daily.upload"}"""), ctx)
        p.execute(call("run_task", """{"task":"daily.upload"}"""), ctx)
        assertEquals("the same task is not asked about twice", 1, transport.askCalls())
        assertEquals(2, taskCalls(transport).size)

        p.execute(call("run_task", """{"task":"make.video"}"""), ctx)
        assertEquals("a different task asks again", 2, transport.askCalls())

        val unknownCtx = AgentLoop.TurnContext(
            1,
            AgentView.ViewIndex(1, mapOf(1 to AgentView.Entry("ref-1", "com.unknown.test", setOf("clickable"), Bounds(0, 0, 100, 50), "Go"))),
            null,
        )
        p.execute(call("ui_click", """{"id":"[1]"}"""), unknownCtx)
        assertEquals("approving a task does not approve clicks in the app on screen", 3, transport.askCalls())
    }

    @Test
    fun `given a task still RUNNING after the wait when it is asked for again then refused without asking, and a finished task may run again`() = runBlocking {
        val transport = FakeTransport()
        answer(transport, "Allow once")
        transport.responses["tasks.run.run"] = buildJsonObject {
            put("run_id", "r1")
            put("status", "RUNNING")
            put("await", "timeout")
        }
        val p = taskPipeline(transport)
        p.execute(call("run_task", """{"task":"daily.upload"}"""), ctx)
        val again = p.execute(call("run_task", """{"task":"daily.upload"}"""), ctx)
        assertEquals("CAPABILITY_UNAVAILABLE", errOf(again).first)
        assertTrue(again.toString(), again.toString().contains("do not start it again"))
        assertEquals("the second request did not ask the person", 1, transport.askCalls())
        assertEquals("and never started a second copy", 1, taskCalls(transport).size)

        // another task is unaffected, and a task that FINISHED may be started again
        transport.responses["tasks.run.run"] = buildJsonObject {
            put("run_id", "r2")
            put("status", "SUCCEEDED")
        }
        p.execute(call("run_task", """{"task":"make.video"}"""), ctx)
        p.execute(call("run_task", """{"task":"make.video"}"""), ctx)
        assertEquals(3, taskCalls(transport).size)
    }
}
