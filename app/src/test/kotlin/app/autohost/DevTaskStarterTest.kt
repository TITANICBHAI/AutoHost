package app.autohost

import app.autohost.core.common.AhError
import app.autohost.core.common.AhResult
import app.autohost.core.common.FakeClock
import app.autohost.core.engine.orchestrator.RunRecord
import app.autohost.core.engine.orchestrator.StartRequest
import app.autohost.core.model.RunStatus
import app.autohost.core.model.TaskManifest
import app.autohost.core.model.RuntimeSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X-19 (`run_starts`, ADR-089): the dev-folder start facade. The pinned contract — a UI start of
 * a dev task gets the SAME packs injection the imported-task facade applies (the inline
 * StartRequest in AppNavHost skipped it: the recorded prediction). The red state is the X-19
 * arch-test observation; this test pins the fix.
 */
class DevTaskStarterTest {

    private val manifest = TaskManifest(
        schema = 1,
        id = "com.example.devtask",
        name = "Dev task",
        runtime = RuntimeSpec(type = "python", entry = "main.py"),
        usesPacks = listOf("com.example.pack"),
    )

    private fun starter(injected: Boolean, requests: MutableList<StartRequest>) = DevTaskStarter(
        packInjector = { m, config ->
            if (injected) {
                Json.parseToJsonElement(
                    """{"packs":{"com.example.pack":{"version":"1.2.3","path":"/packs/p1"}}}""",
                ).jsonObject
            } else {
                config
            }
        },
        runsRoot = { "/data/runs" },
        clock = FakeClock(startMs = 1_000_000L),
        start = { request ->
            requests.add(request)
            AhResult.ok(
                RunRecord(
                    runId = "run-1", taskId = request.taskId, taskName = request.taskName,
                    profileId = null, status = RunStatus.QUEUED, startedAtMs = 0L,
                ),
            )
        },
    )

    private val taskConfig = Json.parseToJsonElement("""{"target_package":"com.example.target"}""").jsonObject

    @Test
    fun `given a dev task with uses_packs when started through the facade then config carries the injected packs`() {
        val requests = mutableListOf<StartRequest>()
        val result = starter(injected = true, requests).start(
            manifest.id, manifest.name, manifest, taskConfig, "/dev/com.example.devtask",
        )
        assertTrue(result is AhResult.Ok)
        val config = requests.single().config
        assertTrue(
            "the packs injection MUST ride the run config (ADR-080)",
            config.containsKey("packs"),
        )
    }

    @Test
    fun `given the injector refuses when started then the request still carries the task config (fail honest, not silent)`() {
        val requests = mutableListOf<StartRequest>()
        starter(injected = false, requests).start(
            manifest.id, manifest.name, manifest, taskConfig, "/dev/com.example.devtask",
        )
        val config = requests.single().config
        assertEquals("com.example.target", (config["target_package"] as JsonPrimitive).content)
        assertTrue(!config.containsKey("packs"))
    }

    @Test
    fun `given a dev task when started then the request shape is a manual run under runsRoot`() {
        val requests = mutableListOf<StartRequest>()
        starter(injected = true, requests).start(
            manifest.id, manifest.name, manifest, taskConfig, "/dev/com.example.devtask",
        )
        val request = requests.single()
        assertEquals(manifest.id, request.taskId)
        assertEquals(manifest.name, request.taskName)
        assertEquals("manual", request.triggerType)
        assertTrue(request.runDir.startsWith("/data/runs/"))
        assertEquals("/dev/com.example.devtask", request.taskPath)
        assertEquals(JsonObject(emptyMap()).size, request.profile.size) // no profile values
        assertNullProfile(request)
    }

    private fun assertNullProfile(request: StartRequest) {
        assertTrue(request.profileId == null)
    }

    @Test
    fun `given the orchestrator refuses when started then the typed error surfaces`() {
        val starter = DevTaskStarter(
            packInjector = { m, c -> c },
            runsRoot = { "/data/runs" },
            clock = FakeClock(startMs = 1_000_000L),
            start = { _ -> AhResult.err(app.autohost.core.model.ErrorCode.NOT_FOUND, "task gone") },
        )
        val result = starter.start(manifest.id, manifest.name, manifest, taskConfig, "/dev/x")
        assertTrue(result is AhResult.Err)
        assertEquals("NOT_FOUND", (result as AhResult.Err).error.code)
    }
}
