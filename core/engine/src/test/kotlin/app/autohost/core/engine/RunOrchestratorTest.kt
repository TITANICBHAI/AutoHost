package app.autohost.core.engine

import app.autohost.core.broker.PermissionIndex
import app.autohost.core.broker.RunTokenStore
import app.autohost.core.common.AppDispatchers
import app.autohost.core.common.FakeClock
import app.autohost.core.engine.orchestrator.InMemoryRunStore
import app.autohost.core.engine.orchestrator.RunOrchestrator
import app.autohost.core.engine.orchestrator.RunRecord
import app.autohost.core.engine.orchestrator.StartRequest
import app.autohost.core.engine.tasks.GrantBuilder
import app.autohost.core.model.BrokerEvent
import app.autohost.core.model.CapabilityNeed
import app.autohost.core.model.Needs
import app.autohost.core.model.RunSpec
import app.autohost.core.model.RunStatus
import app.autohost.core.model.RuntimeSpec
import app.autohost.core.model.TaskManifest
import app.autohost.runtime.api.RunHandle
import app.autohost.runtime.api.RuntimeProvider
import app.autohost.runtime.api.RuntimeRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeRunHandle(override val runId: String) : RunHandle {
    var cancelled = false
    var paused = false
    var killed = false
    var released = false

    override fun requestCancel() {
        cancelled = true
    }

    override fun requestPause() {
        paused = true
    }

    override fun requestResume() {
        paused = false
    }

    override fun kill() {
        killed = true
    }

    override fun release() {
        released = true
    }
}

class FakeRuntimeProvider : RuntimeProvider {
    override val type: String = "python"
    val specs = mutableListOf<RunSpec>()
    val handles = mutableListOf<FakeRunHandle>()
    var startError: Throwable? = null
    var recordedDeathListener: ((String) -> Unit)? = null

    override suspend fun start(spec: RunSpec): RunHandle {
        startError?.let { throw it }
        val handle = FakeRunHandle(spec.runId)
        specs.add(spec)
        handles.add(handle)
        return handle
    }

    override fun setDeathListener(listener: ((String) -> Unit)?) {
        recordedDeathListener = listener
    }
}

class RunOrchestratorTest {
    private val index = PermissionIndex.parse(
        """
        {"capabilities":[
          {"id":"device.ui","permissions":[{"id":"device.ui.observe"},{"id":"device.ui.act"}]},
          {"id":"store","permissions":[{"id":"store.profile"},{"id":"store.task"}]}
        ]}
        """.trimIndent(),
    )

    private fun manifest() = TaskManifest(
        id = "app.autohost.sample.press-button",
        name = "Press Button",
        runtime = RuntimeSpec(type = "python", entry = "main.py"),
        needs = Needs(
            capabilities = listOf(
                CapabilityNeed(
                    "device.ui.act",
                    buildJsonObject {
                        put("packages", JsonArray(listOf(JsonPrimitive("\${config.target}"))))
                    },
                ),
            ),
        ),
    )

    private fun request(taskPath: String = "/data/dev/press-button") = StartRequest(
        taskId = "app.autohost.sample.press-button",
        taskName = "Press Button",
        manifest = manifest(),
        config = buildJsonObject { put("target", "app.autohost.fixture") },
        profile = buildJsonObject { },
        profileId = null,
        taskPath = taskPath,
        runDir = "/data/runs/run-dir",
    )

    private class Harness(
        val orchestrator: RunOrchestrator,
        val provider: FakeRuntimeProvider,
        val store: InMemoryRunStore,
        val tokens: RunTokenStore,
        val clock: FakeClock,
    )

    private fun Harness.record(runId: String): RunRecord? = store.get(runId)

    private fun TestScope.harness(): Harness {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = FakeClock()
        val store = InMemoryRunStore()
        val tokens = RunTokenStore()
        val registry = RuntimeRegistry()
        val provider = FakeRuntimeProvider()
        registry.register(provider)
        val orchestrator = RunOrchestrator(
            store = store,
            tokens = tokens,
            runtimes = registry,
            grants = GrantBuilder(index, devMode = false),
            clock = clock,
            dispatchers = AppDispatchers(dispatcher, dispatcher, dispatcher),
            scope = backgroundScope,
        )
        return Harness(orchestrator, provider, store, tokens, clock)
    }

    @Test
    fun `given task when started then starting and spec carries grants and implicit`() = runTest {
        val h = harness()
        val record = h.orchestrator.start(request()).getOrNull()!!
        advanceUntilIdle()

        assertEquals(RunStatus.STARTING, h.record(record.runId)?.status)
        assertEquals(1, h.provider.specs.size)
        val spec = h.provider.specs.first()
        assertEquals("python", spec.runtime)
        assertEquals("main.py", spec.entry)
        assertEquals(record.runId, spec.runId)
        assertEquals("/data/dev/press-button", spec.taskPath)
        assertTrue(spec.grants.contains("device.ui.act"))
        assertTrue(spec.grants.contains("state.checkpoint")) // implicit grant present
        // config passed through to the spec
        assertEquals("app.autohost.fixture", (spec.config["target"] as JsonPrimitive).content)
        assertNotNull(h.tokens.resolve(spec.runToken))
    }

    @Test
    fun `given heartbeat when emitted then run becomes running`() = runTest {
        val h = harness()
        val record = h.orchestrator.start(request()).getOrNull()!!
        advanceUntilIdle()
        val token = h.provider.specs.first().runToken

        h.orchestrator.onEvent(token, BrokerEvent(type = "heartbeat"))
        advanceUntilIdle()

        assertEquals(RunStatus.RUNNING, h.record(record.runId)?.status)
    }

    @Test
    fun `given finished succeeded then terminal and token revoked`() = runTest {
        val h = harness()
        val record = h.orchestrator.start(request()).getOrNull()!!
        advanceUntilIdle()
        val token = h.provider.specs.first().runToken

        h.orchestrator.onEvent(
            token,
            BrokerEvent(
                type = "finished",
                data = buildJsonObject {
                    put("status", "SUCCEEDED")
                    put("result", buildJsonObject { put("code", "OK"); put("data", buildJsonObject { }) })
                },
            ),
        )
        advanceUntilIdle()

        val run = h.record(record.runId)!!
        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertNotNull(run.endedAtMs)
        assertNull(h.tokens.resolve(token))
    }

    @Test
    fun `given failed outcome then status failed with reason and message`() = runTest {
        val h = harness()
        val record = h.orchestrator.start(request()).getOrNull()!!
        advanceUntilIdle()
        val token = h.provider.specs.first().runToken

        h.orchestrator.onEvent(
            token,
            BrokerEvent(
                type = "finished",
                data = buildJsonObject {
                    put("status", "FAILED")
                    put("reason", "EXCEPTION")
                    put("message", "ValueError: boom")
                },
            ),
        )
        advanceUntilIdle()

        val run = h.record(record.runId)!!
        assertEquals(RunStatus.FAILED, run.status)
        assertEquals("EXCEPTION", run.reason)
        assertEquals("ValueError: boom", run.message)
    }

    @Test
    fun `given cancel while running then handle cancelled and killed after deadline`() = runTest {
        val h = harness()
        val record = h.orchestrator.start(request()).getOrNull()!!
        advanceUntilIdle()
        val token = h.provider.specs.first().runToken
        h.orchestrator.onEvent(token, BrokerEvent(type = "heartbeat"))
        advanceUntilIdle()
        val handle = h.provider.handles.first()

        assertTrue(h.orchestrator.cancel(record.runId))
        advanceUntilIdle()
        assertTrue(handle.cancelled)
        assertFalse(handle.killed) // deadline not reached

        advanceTimeBy(RunOrchestrator.CANCEL_KILL_DELAY_MS + 1_000)
        advanceUntilIdle()
        assertTrue(handle.killed)
        assertEquals(RunStatus.CANCELLED, h.record(record.runId)?.status)
        assertNull(h.tokens.resolve(token))
    }

    @Test
    fun `given worker death while running then interrupted and token revoked`() = runTest {
        val h = harness()
        val record = h.orchestrator.start(request()).getOrNull()!!
        advanceUntilIdle()
        val token = h.provider.specs.first().runToken
        h.orchestrator.onEvent(token, BrokerEvent(type = "heartbeat"))
        advanceUntilIdle()

        h.provider.recordedDeathListener?.invoke(record.runId)

        val run = h.record(record.runId)!!
        assertEquals(RunStatus.INTERRUPTED, run.status)
        assertNull(h.tokens.resolve(token))
    }

    @Test
    fun `given heartbeat stops when watchdog ticks with stale clock then interrupted`() = runTest {
        val h = harness()
        val record = h.orchestrator.start(request()).getOrNull()!!
        advanceUntilIdle()
        val token = h.provider.specs.first().runToken
        h.orchestrator.onEvent(token, BrokerEvent(type = "heartbeat"))
        advanceUntilIdle()

        // The watchdog compares the orchestrator's monotonic clock; advance it past the threshold, then let
        // the virtual delay in the watchdog loop fire.
        h.clock.advance(30_000)
        advanceTimeBy(3_000)
        advanceUntilIdle()

        assertEquals(RunStatus.INTERRUPTED, h.record(record.runId)?.status)
    }

    @Test
    fun `given no runtime registered then failed with no runtime`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = InMemoryRunStore()
        val registry = RuntimeRegistry() // deliberately empty
        val orchestrator = RunOrchestrator(
            store, RunTokenStore(), registry, GrantBuilder(index, devMode = false), FakeClock(),
            AppDispatchers(dispatcher, dispatcher, dispatcher), backgroundScope,
        )
        val record = orchestrator.start(request()).getOrNull()!!
        advanceUntilIdle()
        val run = store.get(record.runId)!!
        assertEquals(RunStatus.FAILED, run.status)
        assertEquals("NO_RUNTIME", run.reason)
    }

    @Test
    fun `given runtime start throws then failed with start failed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = InMemoryRunStore()
        val tokens = RunTokenStore()
        val registry = RuntimeRegistry()
        val provider = FakeRuntimeProvider().apply { startError = IllegalStateException("no python today") }
        registry.register(provider)
        val orchestrator = RunOrchestrator(
            store, tokens, registry, GrantBuilder(index, devMode = false), FakeClock(),
            AppDispatchers(dispatcher, dispatcher, dispatcher), backgroundScope,
        )
        orchestrator.start(request())
        advanceUntilIdle()
        val run = store.runs.value.first()
        assertEquals(RunStatus.FAILED, run.status)
        assertEquals("START_FAILED", run.reason)
    }

    @Test
    fun `given progress stage and result events then record reflects them`() = runTest {
        val h = harness()
        val record = h.orchestrator.start(request()).getOrNull()!!
        advanceUntilIdle()
        val token = h.provider.specs.first().runToken

        h.orchestrator.onEvent(token, BrokerEvent(type = "stage_begin", stage = "find_button"))
        h.orchestrator.onEvent(token, BrokerEvent(type = "progress", data = buildJsonObject { put("progress", 0.5) }))
        h.orchestrator.onEvent(token, BrokerEvent(type = "result", data = buildJsonObject { put("code", "OK") }))
        advanceUntilIdle()

        val run = h.record(record.runId)!!
        assertEquals("find_button", run.stage)
        assertEquals(0.5f, run.progress)
        assertEquals("OK", run.result?.get("code")?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `given unknown token when event emitted then dropped without crash`() = runTest {
        val h = harness()
        h.orchestrator.onEvent("bogus-token", BrokerEvent(type = "heartbeat"))
        advanceUntilIdle()
        assertTrue(h.provider.specs.isEmpty())
    }
}
