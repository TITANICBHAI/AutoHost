package app.autohost.capabilities.a11y

/**
 * Pure policy for the [AutoAcceptGate] (plan §5.7): a rate-limited, cooldown-protected helper that may
 * click known system dialogs **only while a host-initiated flow is pending** (MediaProjection consent,
 * later the package-installer confirmation, P9).
 *
 * Safety properties, all decided here and all unit-tested:
 * - no pending host flow ⇒ never click anything;
 * - only [PopupGuardPolicy.KnownDialog] matches are clickable, and only their affirmative button;
 * - failures back off for [failureCooldownMs];
 * - at most [maxAcceptsPerMinute] clicks per minute (rate limit);
 * - a dialog that keeps re-appearing after [giveUpAfterAttempts] attempts is left alone.
 */
class AutoAcceptPolicy(
    private val failureCooldownMs: Long = 60_000,
    private val maxAcceptsPerMinute: Int = 4,
    private val giveUpAfterAttempts: Int = 3,
) {
    enum class Flow { NONE, MEDIA_PROJECTION, INSTALL }

    /** Which known dialogs a flow may accept — a projection flow must never tap an installer. */
    fun accepts(flow: Flow, dialog: PopupGuardPolicy.KnownDialog): Boolean = when (flow) {
        Flow.MEDIA_PROJECTION -> dialog == PopupGuardPolicy.KnownDialog.MEDIA_PROJECTION_CONSENT
        Flow.INSTALL -> dialog in INSTALL_DIALOGS
        Flow.NONE -> false
    }

    private val INSTALL_DIALOGS = setOf(
        PopupGuardPolicy.KnownDialog.PACKAGE_INSTALLER_CONFIRM,
        PopupGuardPolicy.KnownDialog.RUNTIME_PERMISSION,
    )

    sealed class Decision {
        /** Click the affirmative control of this known dialog. */
        data class Accept(val dialog: PopupGuardPolicy.KnownDialog, val windowId: Int) : Decision()

        /** Do nothing; [detail] says why (for the audit log). */
        data class StandDown(val detail: String) : Decision()
    }

    /**
     * @param pendingFlow      the host-initiated flow awaiting consent (NONE = nothing pending)
     * @param match            the known dialog detected in the occluding window (null = unknown dialog)
     * @param nowMs            wall clock
     * @param lastFailureMs    when the last failed accept happened (-1 = never)
     * @param acceptTimestamps recent successful accept times (minute window)
     * @param attemptsForFlow  how many times we already tried to accept the current pending flow
     */
    fun evaluate(
        pendingFlow: Flow,
        match: PopupGuardPolicy.KnownDialog?,
        nowMs: Long,
        lastFailureMs: Long,
        acceptTimestamps: List<Long>,
        attemptsForFlow: Int,
    ): Decision {
        if (pendingFlow == Flow.NONE) return Decision.StandDown("no host-initiated flow is pending")
        if (match == null) return Decision.StandDown("the occluding window is not a known dialog")
        if (!accepts(pendingFlow, match)) {
            return Decision.StandDown("the ${match.name} dialog does not belong to the pending $pendingFlow flow")
        }
        if (attemptsForFlow >= giveUpAfterAttempts) {
            return Decision.StandDown("gave up after $attemptsForFlow attempts for the pending flow")
        }
        if (lastFailureMs > 0 && nowMs - lastFailureMs < failureCooldownMs) {
            return Decision.StandDown("cooling down after a failed accept (${nowMs - lastFailureMs}ms < ${failureCooldownMs}ms)")
        }
        val recent = acceptTimestamps.count { nowMs - it < 60_000 }
        if (recent >= maxAcceptsPerMinute) {
            return Decision.StandDown("rate limit: $recent accepts in the last minute")
        }
        return Decision.Accept(match, windowId = -1)
    }
}
