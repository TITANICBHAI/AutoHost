package app.autohost.ai

import app.autohost.core.common.AhError
import app.autohost.core.common.AhResult
import app.autohost.core.engine.orchestrator.RunRecord
import app.autohost.core.llm.ProviderProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P14-07 (S33): the Ask sheet state — goal clamp, explicit targets, tighten-only budgets, refusals. */
class AskSheetStateTest {

    private class FakeStarter : AskSheetState.Starter {
        var result: AhResult<RunRecord> = AhResult.err(app.autohost.core.model.ErrorCode.NEEDS_SETUP, "no")
        val calls = mutableListOf<Triple<String, List<String>, String?>>()
        override suspend fun start(
            goal: String,
            packages: List<String>,
            profileId: String?,
            agentOverride: kotlinx.serialization.json.JsonObject,
        ): AhResult<RunRecord> {
            calls.add(Triple(goal, packages, profileId))
            return result
        }
    }

    private val profile = ProviderProfile(id = "p1", label = "P1", baseUrl = "https://api.example.com/v1", model = "m1")

    private fun state(starter: FakeStarter = FakeStarter(), aiEnabled: Boolean = true) =
        AskSheetState(starter).apply {
            hydrate(aiEnabled = aiEnabled, profiles = listOf(profile))
            setGoal("Open Settings and turn on DND")
            togglePackage("com.android.settings")
            selectProfile("p1")
        }

    @Test
    fun `given a filled sheet when checked then canStart is true and the local refusal is null`() {
        val s = state()
        assertTrue(s.canStart)
        assertNull(s.localRefusal())
    }

    @Test
    fun `given the goal over 2000 chars when set then it is clamped (§4)`() {
        val s = state()
        s.setGoal("x".repeat(2_500))
        assertEquals(2_000, s.model.goal.length)
    }

    @Test
    fun `given no target apps when starting then the sheet refuses BEFORE the starter (targets are explicit, ≥ 1)`() = runBlocking {
        val starter = FakeStarter()
        val s = state(starter)
        s.togglePackage("com.android.settings") // off again
        s.start()
        assertEquals(0, starter.calls.size)
        assertTrue(s.model.refusal!!.contains("at least one target app"))
    }

    @Test
    fun `given AI disabled when starting then the refusal names the master switch`() = runBlocking {
        val starter = FakeStarter()
        val s = state(starter, aiEnabled = false)
        s.start()
        assertEquals(0, starter.calls.size)
        assertTrue(s.model.refusal!!.contains("AI features are off"))
    }

    @Test
    fun `given budget overrides over the ceilings when set then they CLAMP to the host ceilings (tighten-only)`() {
        val s = state()
        s.setMaxTurns(500)
        s.setMaxWallClockS(100_000)
        assertEquals(AskSheetState.HOST_MAX_TURNS, s.model.maxTurnsOverride)
        assertEquals(AskSheetState.HOST_MAX_WALL_CLOCK_S, s.model.maxWallClockSOverride)
        s.setMaxTurns(5)
        s.setMaxWallClockS(120)
        assertEquals(5, s.model.maxTurnsOverride)
        assertEquals(120L, s.model.maxWallClockSOverride)
    }

    @Test
    fun `given a valid sheet when started then the starter carries the goal, targets and provider`() = runBlocking {
        val starter = FakeStarter()
        val s = state(starter)
        s.start()
        assertEquals(1, starter.calls.size)
        assertEquals("Open Settings and turn on DND", starter.calls[0].first)
        assertEquals(listOf("com.android.settings"), starter.calls[0].second)
        assertEquals("p1", starter.calls[0].third)
    }

    @Test
    fun `given the starter succeeds when started then the run id surfaces; a typed refusal surfaces on error`() = runBlocking {
        val starter = FakeStarter()
        val s = state(starter)
        starter.result = AhResult.err(
            app.autohost.core.model.ErrorCode.PERMISSION_DENIED,
            "the target app is outside this run's packages",
            reason = "AI_GRANTS_TOO_BROAD",
        )
        s.start()
        assertTrue(s.model.startedRunId == null)
        assertTrue(s.model.refusal!!.contains("AI_GRANTS_TOO_BROAD"))
        assertTrue(s.model.refusal!!.contains("outside this run's packages"))

        starter.result = AhResult.ok(
            RunRecord(
                runId = "run-9", taskId = "builtin.agent.adhoc", taskName = "Ask",
                profileId = "p1", status = app.autohost.core.model.RunStatus.QUEUED, startedAtMs = 0L,
            ),
        )
        s.start()
        assertEquals("run-9", s.model.startedRunId)
        assertNull(s.model.refusal)
    }

    @Test
    fun `given the picker list when hydrated and filtered then the query filters label and package`() {
        val s = state()
        s.hydrateApps(
            listOf(
                AskSheetState.AppEntry("com.android.settings", "Settings"),
                AskSheetState.AppEntry("com.example.calc", "Calculator"),
            ),
        )
        assertEquals(2, s.visibleApps().size)
        s.setAppQuery("calc")
        assertEquals(listOf("com.example.calc"), s.visibleApps().map { it.pkg })
        s.setAppQuery("settings")
        assertEquals(listOf("com.android.settings"), s.visibleApps().map { it.pkg })
        s.setAppQuery("")
        // sorting is label-based, case-insensitive
        assertEquals(listOf("com.example.calc", "com.android.settings"), s.visibleApps().map { it.pkg })
    }

    @Test
    fun `given a picker entry when toggled and started then it reaches the starter like a typed one`() = runBlocking {
        val starter = FakeStarter()
        val s = state(starter)
        s.hydrateApps(listOf(AskSheetState.AppEntry("com.example.calc", "Calculator")))
        s.togglePackage("com.example.calc")
        assertTrue(s.model.selectedPackages.contains("com.example.calc"))
        s.start()
        assertEquals(listOf("com.example.calc"), starter.calls[0].second)
    }

    @Test
    fun `given toggling a package twice when toggled then it deselects (the picker is a toggle)`() {
        val s = state()
        s.togglePackage("com.android.settings")
        assertTrue(s.model.selectedPackages.isEmpty())
        s.togglePackage("com.android.settings")
        assertTrue(s.model.selectedPackages.contains("com.android.settings"))
    }
}
