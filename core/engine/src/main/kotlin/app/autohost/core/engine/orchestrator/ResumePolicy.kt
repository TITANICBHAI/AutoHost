package app.autohost.core.engine.orchestrator

import app.autohost.core.model.Execution

/**
 * P5-05 (plan §12.3): the auto-resume decision for an INTERRUPTED run. A resume is a new attempt of
 * the same run id with the last checkpoint restored; it is bounded by the manifest (`auto_resume`,
 * `max_attempts`) so a crash loop cannot spin forever. Pure JVM — callers supply the numbers.
 */
object ResumePolicy {

    sealed interface Decision {
        /** Start attempt [attempt] with the restored checkpoint. */
        data class Resume(val attempt: Int) : Decision

        /** Leave the run for the user (History → Resume / Take over / Abort). */
        data class Stop(val reason: String) : Decision
    }

    fun decide(execution: Execution, finishedAttempt: Int): Decision {
        if (!execution.autoResume) return Decision.Stop("AUTO_RESUME_DISABLED")
        val maxAttempts = execution.maxAttempts.coerceAtLeast(1)
        if (finishedAttempt >= maxAttempts) return Decision.Stop("ATTEMPTS_EXHAUSTED")
        return Decision.Resume(finishedAttempt + 1)
    }
}
