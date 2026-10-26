package app.autohost.capabilities.a11y

import app.autohost.core.common.AhLog
import app.autohost.core.common.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Clicks known system dialogs **only while a host-initiated flow is pending** (plan §5.7). The consent
 * activity marks the flow pending; the accessibility service consults the gate on window events; the
 * [AutoAcceptPolicy] makes every safety decision. Failures are logged for the audit trail and feed the
 * 60 s back-off.
 */
class AutoAcceptGate(
    private val clock: Clock,
    private val policy: AutoAcceptPolicy = AutoAcceptPolicy(),
) {
    @Volatile
    var pendingFlow: AutoAcceptPolicy.Flow = AutoAcceptPolicy.Flow.NONE
        private set

    @Volatile
    private var lastFailureMs = -1L
    private val acceptTimestamps = ArrayDeque<Long>()
    private var attemptsForFlow = 0

    /** A host flow now needs consent (e.g. the user just saw the MediaProjection dialog appear). */
    @Synchronized
    fun markPending(flow: AutoAcceptPolicy.Flow) {
        pendingFlow = flow
        attemptsForFlow = 0
    }

    /** The pending flow is over (consent given, denied or timed out). */
    @Synchronized
    fun clearPending() {
        pendingFlow = AutoAcceptPolicy.Flow.NONE
    }

    /**
     * Consult the policy with the detected dialog match. Returns the decision; the caller performs any
     * click through the service and reports the outcome back via [recordAccepted] / [recordFailure].
     */
    @Synchronized
    fun consult(match: PopupGuardPolicy.KnownDialog?): AutoAcceptPolicy.Decision {
        val decision = policy.evaluate(
            pendingFlow = pendingFlow,
            match = match,
            nowMs = clock.nowMs(),
            lastFailureMs = lastFailureMs,
            acceptTimestamps = acceptTimestamps.toList(),
            attemptsForFlow = attemptsForFlow,
        )
        if (decision is AutoAcceptPolicy.Decision.Accept) attemptsForFlow += 1
        AhLog.d(
            "a11y",
            "auto_accept_consult",
            buildJsonObject {
                put("pending", pendingFlow.name)
                put("known_dialog", match?.name)
                put("decision", decision::class.simpleName ?: "?")
            },
        )
        return decision
    }

    @Synchronized
    fun recordAccepted() {
        acceptTimestamps.addLast(clock.nowMs())
        while (acceptTimestamps.size > 32) acceptTimestamps.removeFirst()
        clearPending()
    }

    @Synchronized
    fun recordFailure() {
        lastFailureMs = clock.nowMs()
    }
}

/**
 * Matches the MediaProjection consent dialog specifically (variants: single-step confirm, spinner/dropdown
 * destination picker, inline radio "whole screen"). Ids are spike-verified (S3, docs/SPIKES.md); label
 * matching is the fallback so an OEM rename does not silently break the gate — it just falls back to
 * OBSCURED_BY_WINDOW semantics until the spike list is extended.
 */
object MediaProjectionAutoAccept {
    fun match(pkg: String?, ids: Set<String>, labels: Set<String>): PopupGuardPolicy.KnownDialog? {
        val p = pkg ?: return null
        return PopupGuardPolicy.KnownDialog.match(p, ids, labels)
            ?.takeIf { it == PopupGuardPolicy.KnownDialog.MEDIA_PROJECTION_CONSENT }
    }
}
