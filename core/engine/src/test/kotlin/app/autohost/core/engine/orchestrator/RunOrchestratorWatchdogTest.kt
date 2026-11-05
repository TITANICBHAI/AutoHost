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
import app.autohost.core.model.CapabilityNeed
import app.autohost.core.model.Execution
import app.autohost.core.model.Needs
import app.autohost.core.model.RunStatus
import app.autohost.core.model.RuntimeSpec
import app.autohost.core.model.TaskManifest
import app.autohost.runtime.api.RuntimeProvider
import app.autohost.runtime.api.RuntimeRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * X-16 (ADR-098 (5)): the wall-clock watchdog — `timeout_minutes` enforced by the orchestrator
 * for EVERY runtime, driven deterministically through `checkWallClock` with the fake clock (the
 * monitor loop calls the same function). Also pins the §4.1 idle-budget fix: the in-memory
 * request manifest wins over the DB-fallback lambda (AGENT_RUNTIME §4.1).
 */
class RunOrchestratorWatchdogTest {

    private val index = PermissionIndex.parse(
        """
        {"capabilities":[
          {"id":"device.ui","permissions":[{"id":"device.ui.observe"},{"id":"device.ui.act"}]},
          {"id":"store","permissions":[{"id":"store.profile"},{"id":"store.task"}]}
        ]}
        """.trimIndent(),
    )

    private fun manifest(idleSeconds: Int, timeoutMinutes: Int) = TaskManifest(
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
        execution = Execution(uiExclusive = true, timeoutMinutes = timeoutMinutes, idleTimeoutSeconds = idleSeconds),
    )

    private fun request(idleSeconds: Int, timeoutMinutes: Int) = StartRequest(
        taskId = "app.autohost.sample.press-button",
        taskName = "Press Button",
        manifest = manifest(idleSeconds, timeoutMinutes),
        config = buildJsonObject { put("target", "app.autohost.fixture") },
        profile = buildJsonObject { },
        profileId = null,
        taskPath = "/data/dev/press-button",
        runDir = "/data/runs/run-dir",
    )

    private class Harness(
        val orchestrator: RunOrchestrator,
        val provider: FakeRuntimeProvider,
        val store: InMemoryRunStore,
        val clock: FakeClock,
    )

    private fun Harness.record(runId: String): RunRecord? = store.get(runId)

    private fun TestScope.harness(idleSeconds: Int, timeoutMinutes: Int): Harness {
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
        return Harness(orchestrator, provider, store, clock)
    }

    @Test
    fun `given a run past its timeout_minutes when the wall clock is checked then FAILED with TIMEOUT`() = runTest {
        val h = harness(idleSeconds = 300, timeoutMinutes = 10)
        val record = h.orchestrator.start(request(300, 10)).getOrNull()!!
        runCurrent()
        val deadline = h.clock.nowMs() + 10L * 60_000L // what start() recorded
        // one ms before the deadline: untouched
        h.orchestrator.checkWallClock(deadline - 1)
        assertEquals(RunStatus.STARTING, h.record(record.runId)?.status)
        // at the deadline: killed (no handle to kill — the fake provider started one; runCatching covers)
        h.orchestrator.checkWallClock(deadline)
        assertEquals(RunStatus.FAILED, h.record(record.runId)!!.status)
        assertEquals("TIMEOUT", h.record(record.runId)!!.reason)
        // the token is revoked with the run
        val token = h.provider.specs.single().runToken
        assertNull(h.tokens.resolve(token))
    }

    @Test
    fun `given a terminal run when the wall clock passes then nothing is rewritten`() = runTest {
        val h = harness(idleSeconds = 300, timeoutMinutes = 10)
        val record = h.orchestrator.start(request(300, 10)).getOrNull()!!
        h.store.update(record.runId) { it.copy(status = RunStatus.SUCCEEDED) }
        h.orchestrator.checkWallClock(h.clock.nowMs() + 999_999)
        assertEquals("terminal states are never rewritten", RunStatus.SUCCEEDED, h.record(record.runId)!!.status)
    }

    @Test
    fun `given a run inside its budget when checked then untouched`() = runTest {
        val h = harness(idleSeconds = 300, timeoutMinutes = 10)
        val record = h.orchestrator.start(request(300, 10)).getOrNull()!!
        runCurrent()
        h.orchestrator.checkWallClock(h.clock.nowMs() + 60_000)
        assertEquals(RunStatus.STARTING, h.record(record.runId)!!.status)
    }

    @Test
    fun `given the idle budget when read then the in-memory request manifest wins over the fallback`() = runTest {
        val h = harness(idleSeconds = 5, timeoutMinutes = 10)
        var fallback: Long? = 300_000L // what the DB-fallback lambda would answer
        h.orchestrator.idleLimitMs = { fallback }
        val record = h.orchestrator.start(request(5, 10)).getOrNull()!!
        runCurrent()
        // §4.1: the synthesized manifest declares idle 5 s — the request manifest must win over
        // the 300 s DB fallback, otherwise a non-persisted manifest silently gets 300 s.
        assertEquals(5_000L, h.orchestrator.idleLimitMsFor(record.runId))
        fallback = null
        assertEquals(5_000L, h.orchestrator.idleLimitMsFor(record.runId))
    }
}
