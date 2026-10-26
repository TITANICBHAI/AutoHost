package app.autohost.core.engine.orchestrator

import app.autohost.core.engine.schedule.ConditionEnv
import app.autohost.core.engine.schedule.ScheduleEngine
import app.autohost.core.model.ScheduleConditions
import app.autohost.core.model.TaskManifest

/**
 * P4-09: pre-flight at fire time (plan §11.5). Pure decisions over injected facts; the Android
 * layer (controller/service) supplies the [Facts] snapshot and executes the outcome.
 *
 * Outcomes (§11.5): proceed · wait (bounded) · WAITING_USER(reason) · SKIPPED(reason).
 */
object Preflight {

    sealed interface Outcome {
        /** All clear — start the run. */
        data object Proceed : Outcome

        /** A bounded wait, e.g. screen off but wakeable; the caller retries after [retryAfterMs]. */
        data class Wait(val reason: String, val retryAfterMs: Long) : Outcome

        /** The user must act (secure lock, permission lost) — raise WAITING_USER(reason). */
        data class NeedsUser(val reason: String) : Outcome

        /** Conditions unmet / resource unreachable — record SKIPPED(reason) and move on. */
        data class Skip(val reason: String) : Outcome
    }

    /** Everything the decision needs, gathered by the caller (no Android calls in here). */
    data class Facts(
        val conditions: ScheduleConditions?,
        val conditionEnv: ConditionEnv,
        val needsUnlockedScreen: Boolean,
        val screenOn: Boolean,
        val unlocked: Boolean,
        val secureLock: Boolean,
        val uiActing: Boolean,
        val anotherUiRunActive: Boolean,
        /** Bounded wait budget for the unlock policy (§11.6); beyond it → WAITING_USER/SKIPPED. */
        val unlockWaitBudgetMs: Long = DEFAULT_UNLOCK_WAIT_MS,
    )

    fun evaluate(manifest: TaskManifest, ruleConditions: ScheduleConditions?, facts: Facts): Outcome {
        // 1. conditions (schedule-level, §11.2) — never satisfied by waiting, they are state checks
        val effective = ruleConditions ?: facts.conditions
        if (effective != null) {
            val outcome = ENGINE.evaluateConditions(effective, facts.conditionEnv)
            if (!outcome.ok) return Outcome.Skip("CONDITIONS_UNMET:${outcome.failedReason}")
        }
        // 2. UI exclusivity (§11.4): never two UI runs at once — queued upstream, honest skip here
        if (facts.uiActing && facts.anotherUiRunActive) {
            return Outcome.Wait("UI_LOCK_HELD", retryAfterMs = UI_LOCK_RETRY_MS)
        }
        // 3. screen/unlock policy (§11.6)
        if (manifest.execution.needsUnlockedScreen || facts.uiActing) {
            if (!facts.screenOn) return Outcome.Wait("SCREEN_OFF", retryAfterMs = SCREEN_RETRY_MS)
            if (!facts.unlocked) {
                return if (facts.secureLock) {
                    // secure keyguard cannot be dismissed by the app (§11.6)
                    Outcome.NeedsUser("unlock")
                } else {
                    Outcome.Wait("KEYGUARD_DISMISSIBLE", retryAfterMs = KEYGUARD_RETRY_MS)
                }
            }
        }
        return Outcome.Proceed
    }

    /** Conditions evaluation is pure (env.nowMs is an input); one shared engine instance is safe. */
    private val ENGINE = ScheduleEngine(app.autohost.core.common.SystemClockImpl())

    const val DEFAULT_UNLOCK_WAIT_MS = 60_000L
    const val SCREEN_RETRY_MS = 5_000L
    const val KEYGUARD_RETRY_MS = 2_000L
    const val UI_LOCK_RETRY_MS = 10_000L
}
