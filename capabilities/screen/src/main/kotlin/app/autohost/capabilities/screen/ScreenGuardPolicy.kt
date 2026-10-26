package app.autohost.capabilities.screen

/**
 * P4-10: the pure ScreenGuard decision (plan §11.6). Inputs are the device snapshot + the guard
 * state; the service applies the verdict. Never Android imports here — the Gate 4-g behaviour is
 * proven by JVM tests.
 *
 * Verdicts:
 * - [Verdict.Noop]: nothing to do (disabled, or screen on and unlocked).
 * - [Verdict.WakeOnly]: screen off or non-secure keyguard — wake (wake lock) and, when the
 *   keyguard is non-secure, request dismissal.
 * - [Verdict.Recover]: wake alone has not produced an unlocked screen within the throttle window —
 *   start the transparent recovery activity (generation token + timeout guard it).
 * - [Verdict.Degraded]: secure lock — cannot proceed; surface WAITING_USER(unlock) honestly.
 */
object ScreenGuardPolicy {

    const val THROTTLE_MS: Long = 60_000L

    sealed interface Verdict {
        data object Noop : Verdict
        data object WakeOnly : Verdict
        data object Recover : Verdict
        data class Degraded(val reason: String) : Verdict
    }

    data class Input(
        val enabled: Boolean,
        val interactive: Boolean, // screen on
        val deviceLocked: Boolean,
        val secureLock: Boolean,
        val lastAttemptAtMs: Long?,
        val nowMs: Long,
        val lastVerdict: Verdict? = null,
    )

    fun decide(input: Input): Verdict {
        if (!input.enabled) return Verdict.Noop
        if (input.interactive && !input.deviceLocked) return Verdict.Noop
        if (input.secureLock && input.deviceLocked) {
            // §11.6: secure lock cannot be dismissed by the app — degrade, never silently retry
            return Verdict.Degraded("SECURE_LOCK")
        }
        val last = input.lastAttemptAtMs
        val throttled = last != null && input.nowMs - last < THROTTLE_MS
        // Recover only when a plain wake has been tried recently and did not clear the lock
        val wakeTriedAndStuck = last != null && input.deviceLocked && input.lastVerdict == Verdict.WakeOnly
        return when {
            !throttled && wakeTriedAndStuck -> Verdict.Recover
            throttled -> Verdict.WakeOnly // keep the wake lock steady; wait out the throttle
            else -> Verdict.WakeOnly
        }
    }
}
