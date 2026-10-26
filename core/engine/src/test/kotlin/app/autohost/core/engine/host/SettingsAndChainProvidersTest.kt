package app.autohost.core.engine.host

import app.autohost.core.broker.CallContext
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P8-11: device.settings reads + tasks.run chain policy and limits. */
class SettingsAndChainProvidersTest {

    // ---- device.settings ----------------------------------------------------------------------

    @Test fun `settings read returns the value or an honest absence`() = runBlocking {
        val provider = SystemSettingsProvider { table, key -> if (table == "secure" && key == "k") "v" else null }
        val hit = provider.invoke(ctx(), "read", buildJsonObject { put("table", "secure"); put("key", "k") })
        assertEquals("v", hit.result!!.jsonObject["value"]!!.jsonPrimitive.content)
        val miss = provider.invoke(ctx(), "read", buildJsonObject { put("table", "system"); put("key", "nope") })
        assertEquals(false, miss.result!!.jsonObject["exists"]!!.jsonPrimitive.content.toBooleanStrict())
        assertTrue(miss.ok)
    }

    @Test fun `settings read_many returns only existing keys`() = runBlocking {
        val provider = SystemSettingsProvider { _, key -> if (key == "a") "1" else null }
        val result = provider.invoke(ctx(), "read_many", buildJsonObject {
            put("table", "global")
            put("keys", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
        })
        val values = result.result!!.jsonObject["values"]!!.jsonObject
        assertEquals("1", values["a"]!!.jsonPrimitive.content)
        assertTrue(!values.containsKey("b"))
    }

    @Test fun `settings table and key are validated`() = runBlocking {
        val provider = SystemSettingsProvider { _, _ -> null }
        assertEquals(ErrorCode.INVALID_ARGS.name, provider.invoke(ctx(), "read", buildJsonObject { put("table", "x"); put("key", "k") }).error?.code)
        assertEquals(ErrorCode.INVALID_ARGS.name, provider.invoke(ctx(), "read", buildJsonObject { put("table", "system"); put("key", "") }).error?.code)
    }

    // ---- tasks.run ----------------------------------------------------------------------------

    private class FakeRunner : TasksRunProvider.ChainRunner {
        var outcome: TasksRunProvider.ChainRunner.Outcome = TasksRunProvider.ChainRunner.Outcome.Terminal("SUCCEEDED", "ok")
        val starts = mutableListOf<Triple<String, String?, Int>>()
        var refuse = false
        override fun start(taskId: String, profileId: String?, args: JsonObject?, depth: Int): String? {
            if (refuse) return null
            starts.add(Triple(taskId, profileId, depth))
            return "child-${starts.size}"
        }
        override fun await(runId: String, timeoutMs: Long) = outcome
    }

    private fun grantsWith(task: String, depth: Int = 0) = mapOf(
        "tasks.run" to buildJsonObject {
            put("tasks", JsonArray(listOf(JsonPrimitive(task))))
            put("__chain_depth", JsonPrimitive(depth.toString()))
        },
    )

    @Test fun `chain start waits and reports the terminal status`() = runBlocking {
        val runner = FakeRunner()
        val provider = TasksRunProvider(runner)
        val result = provider.invoke(
            CallContext("r", "t", null, grantsWith("task-b"), 0),
            "run", buildJsonObject { put("task", "task-b"); put("profile", "p1") },
        )
        val json = result.result!!.jsonObject
        assertEquals("SUCCEEDED", json["status"]!!.jsonPrimitive.content)
        assertEquals("task-b", runner.starts.single().first)
        assertEquals(1, runner.starts.single().third) // depth incremented for the child
    }

    @Test fun `target outside the scope is a scope violation`() = runBlocking {
        val provider = TasksRunProvider(FakeRunner())
        val result = provider.invoke(
            CallContext("r", "t", null, grantsWith("task-b"), 0),
            "run", buildJsonObject { put("task", "task-other") },
        )
        assertEquals(ErrorCode.SCOPE_VIOLATION.name, result.error?.code)
    }

    @Test fun `no grant at all is permission denied (fail closed)`() = runBlocking {
        val provider = TasksRunProvider(FakeRunner())
        val result = provider.invoke(ctx(), "run", buildJsonObject { put("task", "task-b") })
        assertEquals(ErrorCode.PERMISSION_DENIED.name, result.error?.code)
    }

    @Test fun `chain depth limit terminates cycles`() = runBlocking {
        val runner = FakeRunner()
        val provider = TasksRunProvider(runner)
        val result = provider.invoke(
            CallContext("r", "t", null, grantsWith("task-a", depth = TasksRunProvider.MAX_CHAIN_DEPTH), 0),
            "run", buildJsonObject { put("task", "task-a") },
        )
        assertEquals(ErrorCode.INTERNAL.name, result.error?.code)
        assertEquals("CHAIN_DEPTH", result.error?.reason)
        assertTrue(runner.starts.isEmpty())
    }

    @Test fun `unknown task is not found and timeout reports RUNNING honestly`() = runBlocking {
        val runner = FakeRunner()
        runner.refuse = true
        val provider = TasksRunProvider(runner)
        assertEquals(ErrorCode.NOT_FOUND.name, provider.invoke(
            CallContext("r", "t", null, grantsWith("task-b"), 0),
            "run", buildJsonObject { put("task", "task-b") },
        ).error?.code)

        runner.refuse = false
        runner.outcome = TasksRunProvider.ChainRunner.Outcome.Timeout
        val stillRunning = provider.invoke(
            CallContext("r", "t", null, grantsWith("task-b"), 0),
            "run", buildJsonObject { put("task", "task-b") },
        )
        assertEquals("RUNNING", stillRunning.result!!.jsonObject["status"]!!.jsonPrimitive.content)
    }

    private fun ctx() = CallContext("r", "t", null, emptyMap(), 0)
}
