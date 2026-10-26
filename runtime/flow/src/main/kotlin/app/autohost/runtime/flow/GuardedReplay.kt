package app.autohost.runtime.flow

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P6-08: guarded replay (plan §7.9). Wraps the interpreter's [FlowExec.stepGuard] hook: before a
 * recorded step executes, poll the live screen (`device.ui.snapshot`) until it matches the
 * recorded pre-state (weighted Jaccard, [StateMatcher]) or `state_timeout` elapses; then apply
 * the sidecar's `on_mismatch` policy:
 * - `stop` (default): typed [FlowFailure] `PRE_STATE_MISMATCH` — the evidence event the
 *   interpreter emits carries the step, the score and the threshold (Gate 6-c's "stops with
 *   evidence"); the host attaches the compact dump + foreground app as usual.
 * - `ask_user`: [FlowWaitUserException] — the run parks as WAITING_USER with the mismatch shown.
 * - `continue`: log a warning and run the step anyway (unverified).
 *
 * Steps without a sidecar entry are not guarded (hand-edited flows replay unguarded by design —
 * the guard exists to catch drift between what was recorded and what is on screen now).
 */
class GuardedReplay(
    private val sidecar: RecordingSidecar,
    private val threshold: Double = StateMatcher.DEFAULT_THRESHOLD,
) {

    /** The [FlowExec.stepGuard] implementation; pass as `stepGuard = guard::guardStep`. */
    suspend fun guardStep(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec) {
        val meta = sidecar.byLabel(step.reportLabel) ?: return // unrecorded step: not guarded
        val pollBudgetMs = sidecar.stateTimeoutMs
        val started = System.nanoTime()
        var verdict: StateMatcher.Verdict? = null
        var live: StateMatcher.LiveState? = null
        while (true) {
            live = currentLiveState(ex, step.reportLabel)
            if (live != null) {
                val v = StateMatcher.compare(meta.snapshot, live, threshold)
                if (v.matched) return
                verdict = v
            }
            if ((System.nanoTime() - started) / 1_000_000 >= pollBudgetMs) break
            ex.sleep(POLL_INTERVAL_MS) // cancellable, pause-aware
        }
        val expected = meta.snapshot
        val evidence = StateMatcher.mismatchEvidence(step.reportLabel, expected, verdict ?: StateMatcher.Verdict(0.0, threshold))
        when (sidecar.onMismatch) {
            "continue" -> ex.emit(buildJsonObject {
                put("type", "log")
                put("level", "warn")
                put("message", scope.redact(
                    "step '${step.reportLabel}': screen changed since recording " +
                        "(score ${(verdict?.score ?: 0.0)}); on_mismatch=continue, running anyway"))
                put("data", evidence)
            })
            "ask_user" -> throw FlowWaitUserException(
                "step '${step.reportLabel}': the screen changed since recording " +
                    "(similarity ${(verdict?.score ?: 0.0)} < $threshold); answer to continue anyway",
            )
            else -> throw FlowFailure(
                code = "PRE_STATE_MISMATCH",
                message = "step '${step.reportLabel}': the screen changed since recording " +
                    "(similarity ${(verdict?.score ?: 0.0)} < $threshold)",
                retryable = false,
                selector = null,
                label = step.reportLabel,
            )
        }
    }

    private suspend fun currentLiveState(ex: FlowExec, label: String): StateMatcher.LiveState? = try {
        val snapshot = ex.broker.call("device.ui", "snapshot", buildJsonObject { })
        StateMatcher.fromSnapshot(snapshot)
    } catch (e: Exception) {
        // A snapshot failure during the guard is not the step's failure yet: it counts as
        // "cannot confirm" and keeps polling; when the budget expires the outcome is a guard
        // mismatch (score 0). Never mask it as success, never crash the interpreter here.
        null
    }

    companion object {
        const val POLL_INTERVAL_MS = 250L
    }
}
