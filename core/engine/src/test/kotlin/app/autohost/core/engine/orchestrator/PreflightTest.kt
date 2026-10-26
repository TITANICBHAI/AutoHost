package app.autohost.core.engine.orchestrator

import app.autohost.core.engine.schedule.ConditionEnv
import app.autohost.core.model.TaskManifest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P4-09: pre-flight decisions (plan §11.5) — pure outcomes over injected facts.
 */
class PreflightTest {

    private val manifest = TaskManifest(
        id = "t", name = "T",
        runtime = app.autohost.core.model.RuntimeSpec("python", "main.py"),
        execution = app.autohost.core.model.Execution(needsUnlockedScreen = true),
    )

    private fun facts(
        needsUnlock: Boolean = true,
        screenOn: Boolean = true,
        unlocked: Boolean = true,
        secure: Boolean = false,
        uiActing: Boolean = false,
        anotherUi: Boolean = false,
    ) = Preflight.Facts(
        conditions = null,
        conditionEnv = ConditionEnv(nowMs = 1_000),
        needsUnlockedScreen = needsUnlock,
        screenOn = screenOn,
        unlocked = unlocked,
        secureLock = secure,
        uiActing = uiActing,
        anotherUiRunActive = anotherUi,
    )

    @Test fun `all clear proceeds`() {
        assertEquals(Preflight.Outcome.Proceed, Preflight.evaluate(manifest, null, facts()))
    }

    @Test fun `unmet conditions skip with the reason`() {
        val conditions = app.autohost.core.model.ScheduleConditions(charging = true)
        val outcome = Preflight.evaluate(
            manifest, conditions,
            facts().copy(conditionEnv = ConditionEnv(nowMs = 1_000, charging = false)),
        )
        assertEquals(Preflight.Outcome.Skip("CONDITIONS_UNMET:charging"), outcome)
    }

    @Test fun `screen off waits with a retry budget`() {
        val outcome = Preflight.evaluate(manifest, null, facts(screenOn = false))
        assertEquals(Preflight.Outcome.Wait("SCREEN_OFF", Preflight.SCREEN_RETRY_MS), outcome)
    }

    @Test fun `dismissible keyguard waits; secure lock needs the user`() {
        assertEquals(
            Preflight.Outcome.Wait("KEYGUARD_DISMISSIBLE", Preflight.KEYGUARD_RETRY_MS),
            Preflight.evaluate(manifest, null, facts(unlocked = false, secure = false)),
        )
        assertEquals(
            Preflight.Outcome.NeedsUser("unlock"),
            Preflight.evaluate(manifest, null, facts(unlocked = false, secure = true)),
        )
    }

    @Test fun `ui lock held waits instead of starting a second ui run`() {
        val outcome = Preflight.evaluate(manifest, null, facts(uiActing = true, anotherUi = true))
        assertEquals(Preflight.Outcome.Wait("UI_LOCK_HELD", Preflight.UI_LOCK_RETRY_MS), outcome)
    }

    @Test fun `non-ui task ignores the screen policy`() {
        val quiet = manifest.copy(
            execution = app.autohost.core.model.Execution(needsUnlockedScreen = false),
        )
        assertEquals(Preflight.Outcome.Proceed, Preflight.evaluate(quiet, null, facts(screenOn = false, unlocked = false)))
    }
}
