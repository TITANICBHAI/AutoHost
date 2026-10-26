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
import app.autohost.core.model.Execution
import app.autohost.core.model.Needs
import app.autohost.core.model.RunStatus
import app.autohost.core.model.RuntimeSpec
import app.autohost.core.model.TaskManifest
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-03/P5-05/P5-06/P5-07: pause status flip, WAITING_USER/INTERRUPTED resume as a new attempt,
 * and the idle watchdog wiring (plan §12.3, §12.5).
 */
class RunLifecycleP5Test {
    private val index = PermissionIndex.parse(
        """
        {"capabilities":[
          {"id":"device.ui","permissions":[{"id":"device.ui.observe"},{"id":"device.ui.act"}]},
          {"id":"store","permissions":[{"id":"store.profile"},{"id":"store.task"}]}
        ]}
        """.trimIndent(),
    )

    private fun manifest(idleSeconds: Int = 300) = TaskManifest(
        id = "app.autohost.sample.press-button",
        name = "Press Button",
        runtime = RuntimeSpec(type = "python", entry = "main.py"),
        needs = Needs(
            capabilities = listOf(
                CapabilityNeed(
                    "device.ui.act",
                    buildJsonObject { put("packages", JsonArray(listOf(JsonPrimitive("app.fixture")))) },
                ),
            ),
        ),
        execution = Execution(idleTimeoutSeconds = idleSeconds),
    )

    private fun request(idleSeconds: Int = 300, checkpoint: kotlinx.serialization.json.JsonObject? = null) = StartRequest(
        taskId = "app.autohost.sample.press-button",
        taskName = "Press Button",
        manifest = manifest(idleSeconds),
        config = buildJsonObject { put("target", "app.fixture") },
        profile = buildJsonObject { },
        profileId = null,
        taskPath = "/data/dev/press-button",
        runDir = "/data/runs/run-dir",
        checkpoint = checkpoint,
    )

    private class Harness(
        val orchestrator: RunOrchestrator,
        val provider: FakeRuntimeProvider,
        val store: InMemoryRunStore,
        val tokens: RunTokenStore,
        val clock: FakeClock,
    )

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

    private fun Harness.record(runId: String): RunRecord? = store.get(runId)

    @Test
    fun `pause flips the record to PAUSED and resume back to RUNNING`() = runTest {
        val h = harness()
        val record = h.orchestrator.start(request()).getOrNull()!!
        advanceUntilIdle()
        val token = h.provider.specs.first().runToken
        h.orchestrator.onEvent(token, BrokerEvent(type = "heartbeat"))
        advanceUntilIdle()

        assertTrue(h.orchestrator.requestPause(record.runId))
        assertEquals(RunStatus.PAUSED, h.record(record.runId)?.status)
        assertTrue(h.orchestrator.requestResume(record.runId))
        assertEquals(RunStatus.RUNNING, h.record(record.runId)?.status)
    }

    @Test
    fun `WAITING_USER run resumes as a new attempt with the checkpoint restored`() = runTest {
        val h = harness()
        val checkpoint = buildJsonObject { put("stage:one", "done") }
        val record = h.orchestrator.start(request(checkpoint = checkpoint)).getOrNull()!!
        advanceUntilIdle()
        val token = h.provider.specs.first().runToken

        h.orchestrator.onEvent(
            token,
            BrokerEvent(type = "finished", data = buildJsonObject { put("status", "WAITING_USER"); put("reason", "CAPTCHA") }),
        )
        advanceUntilIdle()
        assertEquals(RunStatus.WAITING_USER, h.record(record.runId)?.status)

        val resumed = h.orchestrator.resume(record.runId, checkpoint)
        advanceUntilIdle()
        val updated = resumed.getOrNull()!!
        assertEquals(RunStatus.STARTING, updated.status)
        val spec = h.provider.specs.last()
        assertEquals(2, spec.attempt)
        assertEquals(checkpoint, spec.checkpoint)
        assertNotNull(h.tokens.resolve(spec.runToken)) // a fresh token for the new attempt
    }

    @Test
    fun `INTERRUPTED run can be resumed but a FAILED run cannot`() = runTest {
        val h = harness()
        val record = h.orchestrator.start(request()).getOrNull()!!
        advanceUntilIdle()
        val token = h.provider.specs.first().runToken

        h.orchestrator.onWorkerDeath(record.runId)
        advanceUntilIdle()
        assertEquals(RunStatus.INTERRUPTED, h.record(record.runId)?.status)
        assertTrue(h.orchestrator.resume(record.runId, null).isOk)

        val second = h.orchestrator.start(request()).getOrNull()!!
        advanceUntilIdle()
        h.orchestrator.onEvent(
            h.provider.specs.last().runToken,
            BrokerEvent(type = "finished", data = buildJsonObject { put("status", "FAILED"); put("reason", "EXCEPTION") }),
        )
        advanceUntilIdle()
        assertEquals(RunStatus.FAILED, h.record(second.runId)?.status)
        assertTrue(h.orchestrator.resume(second.runId, null).errorOrNull() != null)
    }

    @Test
    fun `wedged run fails with IDLE_TIMEOUT while heartbeats keep it alive`() = runTest {
        val h = harness()
        h.orchestrator.idleLimitMs = { 100_000L }
        val record = h.orchestrator.start(request(idleSeconds = 100)).getOrNull()!!
        advanceUntilIdle()
        val token = h.provider.specs.first().runToken
        h.orchestrator.onEvent(token, BrokerEvent(type = "heartbeat")) // baseline activity
        advanceUntilIdle()

        // the worker heartbeat thread keeps emitting; the script never makes a broker call again.
        // The idle monitor polls every 2 s (virtual delay) against the fake clock (advance).
        repeat(52) {
            h.clock.advance(2_000)
            h.orchestrator.onEvent(token, BrokerEvent(type = "heartbeat")) // not activity (P5-07)
            advanceTimeBy(2_000)
        }
        advanceUntilIdle()

        val run = h.record(record.runId)!!
        assertEquals(RunStatus.FAILED, run.status)
        assertEquals("IDLE_TIMEOUT", run.reason)
        // the last event before the terminal state was the warn, not an assumed success
        val events = h.store.snapshot(record.runId)
        assertTrue(events.any { it.level == "warn" && it.message.orEmpty().contains("idle") })
    }

    @Test
    fun `a busy run is never expired by the idle watchdog`() = runTest {
        val h = harness()
        h.orchestrator.idleLimitMs = { 100_000L }
        val record = h.orchestrator.start(request(idleSeconds = 100)).getOrNull()!!
        advanceUntilIdle()
        val token = h.provider.specs.first().runToken

        repeat(10) {
            h.orchestrator.onEvent(token, BrokerEvent(type = "log", message = "tick"))
            h.clock.advance(10_000)
            advanceTimeBy(2_000)
        }
        advanceUntilIdle()

        assertEquals(RunStatus.RUNNING, h.record(record.runId)?.status)
    }
}
