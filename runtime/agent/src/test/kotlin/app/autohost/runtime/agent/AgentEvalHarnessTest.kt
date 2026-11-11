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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * P13-16 (AGENT_RUNTIME §14a, RM-G1 adapted clean-room): the JVM eval runner. Every scenario in
 * `testing/agent-evals/scenarios/` runs through the REAL [AgentLoop] with a scripted FakeLlm;
 * the `expect` clauses are evaluated against the run, and the numbers are compared against
 * `testing/agent-evals/baselines.json` — movement-only (a drift fails with a re-record hint).
 * The fixture-app half of G13-e (a real model over the stub server) is NOT this test.
 */
class AgentEvalHarnessTest {

    // -- repo-file lookup (the same walk-up the golden-vector tests use) -------------------------

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("repo file not found from ${System.getProperty("user.dir")}: $relative")
    }

    private fun scenarios(): List<JsonObject> =
        repoFile("testing/agent-evals/scenarios").listFiles { f -> f.name.endsWith(".json") }!!
            .sortedBy { it.name }
            .map { Json.parseToJsonElement(it.readText()).jsonObject }

    private fun baselines(): JsonObject =
        (Json.parseToJsonElement(repoFile("testing/agent-evals/baselines.json").readText()).jsonObject)["scenarios"]!!.jsonObject

    // -- the scripted fakes, built from the scenario JSON ----------------------------------------

    private class ScriptedLlm(private val script: JsonArray) : AgentLoop.ModelClient {
        var index = 0
        private var turnCalls = 0
        override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> {
            turnCalls++
            if (index >= script.size) {
                error("the script ran dry after ${script.size} turns (turn $turnCalls)")
            }
            val entry = script[index].jsonObject
            index++
            val text = (entry["text"] as? JsonPrimitive)?.content
            val calls = (entry["tool_calls"] as? JsonArray)?.mapIndexed { i, raw ->
                val c = raw.jsonObject
                val argsJson = c["arguments"]!!.jsonPrimitive.content
                ToolCall(
                    id = "c${index}-$i",
                    name = c["name"]!!.jsonPrimitive.content,
                    argumentsJson = argsJson,
                    arguments = runCatching { Json.parseToJsonElement(argsJson) as JsonObject }.getOrNull(),
                )
            } ?: emptyList()
            return LlmResults.ok(
                ChatResponse(
                    text = text,
                    toolCalls = calls,
                    finishReason = if (calls.isEmpty()) FinishReason.STOP else FinishReason.TOOL_CALLS,
                    usage = Usage(20, 10, 30, estimated = false),
                    latencyMs = 1,
                    attempts = 1,
                ),
            )
        }
    }

    private class ScriptedPipeline(private val canned: Map<String, JsonObject>) : AgentLoop.ToolExecutor {
        val executed = mutableListOf<String>()
        val results = mutableListOf<JsonObject>()
        override suspend fun execute(call: ToolCall, ctx: AgentLoop.TurnContext): JsonObject {
            executed.add(call.name)
            val result = canned[call.name] ?: buildJsonObject { put("ok", true) }
            results.add(result)
            return result
        }
    }

    /** One scenario execution's observable outcome. */
    private data class RunResult(
        val outcome: AgentLoop.Outcome,
        val executedTools: List<String>,
        val resultTexts: List<String>,
        val renderedObservation: String,
    )

    private fun runScenario(scenario: JsonObject): RunResult {
        val observation = scenario["observation"]!!.jsonObject
        val canned = (scenario["tool_results"] as? JsonObject)?.let { map ->
            map.entries.associate { it.key to (it.value as JsonObject) }
        } ?: emptyMap()
        val pipeline = ScriptedPipeline(canned)
        val view = AgentView(screenWidth = 1080, screenHeight = 2400)
        val rendered = view.render(observation, generation = 1)
        val loop = AgentLoop(
            client = ScriptedLlm(scenario["script"]!!.jsonArray),
            catalog = ToolCatalog.load(),
            pipeline = pipeline,
            observer = { observation },
            preflight = { AhResult.ok(Unit) },
            finishVerifier = { AgentLoop.FinishResult.Confirmed }, // supervised confirm passes
            budget = RunBudget(AgentManifests.DEFAULT_BUDGETS.toLimits()) { 0 },
            view = view,
        )
        val outcome = runBlocking { loop.run(scenario["goal"]!!.jsonPrimitive.content) }
        return RunResult(
            outcome = outcome,
            // `finish` never reaches the pipeline (the completion protocol handles it) but it IS
            // an executed tool from the eval's point of view
            executedTools = pipeline.executed.toList() +
                if (outcome is AgentLoop.Outcome.Succeeded) listOf("finish") else emptyList(),
            resultTexts = pipeline.results.map { it.toString() },
            renderedObservation = rendered.text,
        )
    }

    // -- the expect-clause evaluation ------------------------------------------------------------

    private fun checkExpect(expect: JsonObject, run: RunResult, name: String) {
        val failed = run.outcome is AgentLoop.Outcome.Failed
        if (expect["finished"]?.jsonPrimitive?.content?.toBooleanStrict() == true && failed) {
            fail("$name: expected a confirmed finish, got ${run.outcome}")
        }
        val executed = run.executedTools
        (expect["tools"] as? JsonArray)?.map { it.jsonPrimitive.content }?.let { expected ->
            assertEquals("$name: the executed tool set", expected, executed)
        }
        (expect["not_tools"] as? JsonArray)?.map { it.jsonPrimitive.content }?.forEach { banned ->
            assertTrue("$name: '$banned' must never be executed", banned !in executed)
        }
        (expect["max_steps"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { max ->
            assertTrue("$name: used ${executed.size + if (failed) 1 else 0} steps > max $max", executed.size <= max)
        }
        val screenTruth = (run.renderedObservation + "\n" + run.resultTexts.joinToString("\n"))
        (expect["contains"] as? JsonArray)?.map { it.jsonPrimitive.content }?.forEach { needle ->
            assertTrue("$name: the screen truth must contain '$needle'\n---\n$screenTruth", needle in screenTruth)
        }
        (expect["not_contains"] as? JsonArray)?.map { it.jsonPrimitive.content }?.forEach { needle ->
            assertTrue("$name: the screen truth must NOT contain '$needle'", needle !in screenTruth)
        }
    }

    // -- the two gates: expect clauses, then the baseline ratchet ---------------------------------

    @Test
    fun `every scenario passes its expect clauses against the real loop`() {
        val scenarioFiles = scenarios()
        assertTrue("expected the scenario set to exist", scenarioFiles.isNotEmpty())
        for (scenario in scenarioFiles) {
            val name = scenario["name"]!!.jsonPrimitive.content
            val run = runScenario(scenario)
            checkExpect(scenario["expect"]!!.jsonObject, run, name)
        }
    }

    @Test
    fun `recorded baselines match — movement is reported, never silent`() {
        val baselines = baselines()
        for (scenario in scenarios()) {
            val name = scenario["name"]!!.jsonPrimitive.content
            val recorded = baselines[name]?.jsonObject
                ?: throw AssertionError("scenario '$name' has no baseline — record it in testing/agent-evals/baselines.json")
            val run = runScenario(scenario)
            val executed = run.executedTools
            val modelCalls = (scenario["script"]!!.jsonArray.size)
            val drift = mutableListOf<String>()
            val recordedSteps = recorded["steps"]!!.jsonPrimitive.content.toInt()
            if (recordedSteps != executed.size) {
                drift.add("steps ${recordedSteps} -> ${executed.size}")
            }
            val recordedCalls = recorded["model_calls"]!!.jsonPrimitive.content.toInt()
            if (recordedCalls != modelCalls) {
                drift.add("model_calls ${recordedCalls} -> $modelCalls")
            }
            val recordedTools = recorded["executed_tools"]!!.jsonArray.map { it.jsonPrimitive.content }
            if (recordedTools != executed) {
                drift.add("executed_tools $recordedTools -> $executed")
            }
            assertTrue(
                "BASELINE MOVEMENT for '$name': ${drift.joinToString("; ")} — " +
                    "re-record testing/agent-evals/baselines.json deliberately in the same change",
                drift.isEmpty(),
            )
        }
    }
}
