package app.autohost.ui.capsule

import app.autohost.core.designsystem.theme.Tokens

/**
 * P14-03 (S29, ADR-094): the pure state behind the approval sheet and its notification actions.
 * Rules pinned by `ApprovalStateTest` and AGENT_RUNTIME §15:
 *  - exactly three options: **Allow once / Allow for this run / Deny** (no "Always" in Stage A);
 *  - **timeout → deny and stop the run** (deny-by-default is the whole point of the sheet);
 *  - node labels shown are truncated; CAUTIOUS apps show no label at all (AGENT_RUNTIME §9);
 *  - the same options are served to the notification actions and the sheet — one decision path.
 */
class ApprovalState(private val clock: app.autohost.core.common.Clock) {

    enum class Decision { ALLOW_ONCE, ALLOW_FOR_RUN, DENY }

    data class Ask(
        val runId: String,
        val callId: String,
        val capability: String,
        val method: String,
        /** Truncated node label for the sheet; null for CAUTIOUS apps. */
        val label: String?,
        val targetPackage: String,
        val timeoutAtMs: Long,
    )

    @Volatile var pending: Ask? = null
        private set

    companion object {
        const val LABEL_MAX_CHARS = 40
        const val CHOICE_ALLOW_ONCE = "Allow once"
        const val CHOICE_ALLOW_FOR_RUN = "Allow for this run"
        const val CHOICE_DENY = "Deny"

        /** The one options list — the sheet and the notification actions share it. */
        val CHOICES = listOf(CHOICE_ALLOW_ONCE, CHOICE_ALLOW_FOR_RUN, CHOICE_DENY)

        /** Truncation rule for node labels (P14-03): one line, hard cap, ellipsis. */
        fun truncateLabel(raw: String?): String? =
            raw?.replace("\n", " ")
                ?.takeIf { it.isNotBlank() }
                ?.let { if (it.length > LABEL_MAX_CHARS) it.take(LABEL_MAX_CHARS - 1) + "…" else it }
    }

    /** Raises an ask. [rawLabel] is truncated here; CAUTIOUS apps pass null. */
    fun raise(
        runId: String,
        callId: String,
        capability: String,
        method: String,
        rawLabel: String?,
        targetPackage: String,
        cautious: Boolean,
        timeoutMs: Long = Tokens.APPROVAL_TIMEOUT_MS,
    ): Ask {
        val ask = Ask(
            runId = runId,
            callId = callId,
            capability = capability,
            method = method,
            label = if (cautious) null else truncateLabel(rawLabel),
            targetPackage = targetPackage,
            timeoutAtMs = clock.nowMs() + timeoutMs,
        )
        pending = ask
        return ask
    }

    /** Resolves the pending ask. Returns null when nothing was pending or the id does not match. */
    fun resolve(callId: String, decision: Decision): Ask? {
        val ask = pending ?: return null
        if (ask.callId != callId) return null
        pending = null
        return ask
    }

    /** The timeout tick: an expired ask resolves as DENY (deny-and-stop; never left hanging). */
    fun tick(): Decision? {
        val ask = pending ?: return null
        if (clock.nowMs() < ask.timeoutAtMs) return null
        pending = null
        return Decision.DENY
    }

    /** Whether an ask is on screen (drives the sheet and the notification action visibility). */
    val isAwaiting: Boolean get() = pending != null
}
