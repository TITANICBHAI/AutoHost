package app.autohost.capabilities.screen

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P4-10: the ScreenGuard decision table (plan §11.6) — pure JVM, Gate 4-g inputs.
 */
class ScreenGuardPolicyTest {

    private fun input(
        enabled: Boolean = true,
        interactive: Boolean = false,
        deviceLocked: Boolean = false,
        secureLock: Boolean = false,
        lastAttemptAtMs: Long? = null,
        nowMs: Long = 1_000_000,
        lastVerdict: ScreenGuardPolicy.Verdict? = null,
    ) = ScreenGuardPolicy.Input(enabled, interactive, deviceLocked, secureLock, lastAttemptAtMs, nowMs, lastVerdict)

    @Test fun `disabled guard never acts`() {
        assertEquals(ScreenGuardPolicy.Verdict.Noop, ScreenGuardPolicy.decide(input(enabled = false)))
    }

    @Test fun `clear screen is a noop`() {
        assertEquals(
            ScreenGuardPolicy.Verdict.Noop,
            ScreenGuardPolicy.decide(input(interactive = true, deviceLocked = false)),
        )
    }

    @Test fun `screen off wakes first`() {
        assertEquals(ScreenGuardPolicy.Verdict.WakeOnly, ScreenGuardPolicy.decide(input(interactive = false)))
    }

    @Test fun `non-secure keyguard wakes and the recovery activity dismisses`() {
        assertEquals(
            ScreenGuardPolicy.Verdict.WakeOnly,
            ScreenGuardPolicy.decide(input(interactive = true, deviceLocked = true, secureLock = false)),
        )
    }

    @Test fun `stuck non-secure lock recovers through the activity after the wake attempt`() {
        val afterWake = ScreenGuardPolicy.decide(
            input(
                interactive = false, deviceLocked = true, secureLock = false,
                lastAttemptAtMs = 1_000_000 - 120_000, lastVerdict = ScreenGuardPolicy.Verdict.WakeOnly,
            ),
        )
        assertEquals(ScreenGuardPolicy.Verdict.Recover, afterWake)
    }

    @Test fun `throttle keeps waking inside the 60s window`() {
        assertEquals(
            ScreenGuardPolicy.Verdict.WakeOnly,
            ScreenGuardPolicy.decide(
                input(
                    interactive = false, deviceLocked = true, secureLock = false,
                    lastAttemptAtMs = 1_000_000 - 30_000, lastVerdict = ScreenGuardPolicy.Verdict.WakeOnly,
                ),
            ),
        )
    }

    @Test fun `secure lock degrades and never silently retries`() {
        val verdict = ScreenGuardPolicy.decide(
            input(interactive = true, deviceLocked = true, secureLock = true, lastAttemptAtMs = 1_000_000 - 120_000),
        )
        assertEquals(ScreenGuardPolicy.Verdict.Degraded("SECURE_LOCK"), verdict)
    }
}
