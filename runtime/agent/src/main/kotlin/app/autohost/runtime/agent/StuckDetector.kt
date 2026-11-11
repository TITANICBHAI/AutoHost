package app.autohost.runtime.agent

// Adapted from closepaw agent/cognition/policy/LoopDetectionPolicy.kt (Apache-2.0, Copyright 2026
// ClosePaw Authors). Changes: the element-set Jaccard similarity is kept as a helper but the
// observation signature is AutoHost's STATE-AWARE fingerprint (the A2 lesson, ACTION_HARDENING
// §7.4: checked/selected/enabled included, focused excluded — the ChangeDetector rule, not
// upstream's PerceptionElement set); the advisory-only policy became a HARD-STOP detector with
// ADR-091 (12)'s thresholds: warn at 3 same fingerprints, stop at 5, or at 3 identical
// (tool, args, target) actions (docs/REUSE_MATRIX.md RM-C2, P13-10).

/**
 * P13-10 (AGENT_RUNTIME §7.4): stuck detection. The factual warning and the hard stop:
 *
 *  - `warnAfterSameObservation` (3) consecutive observations with the SAME state-aware
 *    fingerprint → a factual warning ("the screen has not changed for N steps") — no advice;
 *    the model decides what to do with the fact (the upstream lesson: advisory-only warnings
 *    poison fewer runs than strategy suggestions).
 *  - `stopAfterSameObservation` (5) → stop the run: `AGENT_STUCK`.
 *  - `stopAfterIdenticalActions` (3) identical (tool, args, target) actions → stop, even when
 *    the screen DOES change (a toggle flicking on/off is still stuck).
 *
 * "Same" observation = exact fingerprint equality OR Jaccard similarity of the fingerprint's
 * line sets ≥ 0.95 (the adapted upstream similarity rule — near-identical screens count).
 */
class StuckDetector(
    private val warnAfterSameObservation: Int = 3,
    private val stopAfterSameObservation: Int = 5,
    private val stopAfterIdenticalActions: Int = 3,
    private val similarityThreshold: Double = 0.95,
) {

    sealed interface Verdict {
        data object None : Verdict

        /** A factual warning — injected into the next request, never a stop. */
        data class Warn(val message: String) : Verdict

        /** The run stops: `AGENT_STUCK`. */
        data class Stop(val message: String) : Verdict
    }

    private val fingerprints = ArrayDeque<String>()
    private val actions = ArrayDeque<Action>()

    private data class Action(val tool: String, val argsJson: String, val target: String?) {
        val identity: String get() = "$tool|$argsJson|$target"
    }

    /** One observation per turn, AFTER the action settled (the state-aware fingerprint). */
    fun observe(fingerprint: String): Verdict {
        fingerprints.addLast(fingerprint)
        while (fingerprints.size > stopAfterSameObservation) fingerprints.removeFirst()
        // The trailing window counts CONSECUTIVE same (by equality or near-similarity).
        var run = 1
        for (i in fingerprints.size - 1 downTo 1) {
            if (sameObservation(fingerprints[i], fingerprints[i - 1])) run++ else break
        }
        return when {
            run >= stopAfterSameObservation -> Verdict.Stop(
                "stuck: the screen has not changed for $run steps",
            )
            run >= warnAfterSameObservation -> Verdict.Warn(
                "the screen has not changed for $run steps",
            )
            else -> sameActionsVerdict()
        }
    }

    /** One executed device action per turn (host tools are not recorded — they change nothing). */
    fun recordAction(tool: String, argsJson: String, target: String?): Verdict {
        actions.addLast(Action(tool, argsJson, target))
        while (actions.size > stopAfterIdenticalActions) actions.removeFirst()
        return sameActionsVerdict()
    }

    private fun sameActionsVerdict(): Verdict {
        if (actions.size < stopAfterIdenticalActions) return Verdict.None
        val identity = actions.last().identity
        return if (actions.all { it.identity == identity }) {
            Verdict.Stop(
                "stuck: the same action (${actions.last().tool}, same args and target) " +
                    "was repeated ${actions.size} times",
            )
        } else {
            Verdict.None
        }
    }

    private fun sameObservation(a: String, b: String): Boolean =
        a == b || jaccard(a.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            b.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()) >= similarityThreshold

    companion object {
        /**
         * The adapted upstream similarity helper: |A∩B| / |A∪B| over two line sets
         * (0.0 when both are empty — two empty screens are not evidence of anything).
         */
        fun jaccard(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() && b.isEmpty()) return 0.0
            val union = a + b
            return a.intersect(b).size.toDouble() / union.size.toDouble()
        }
    }
}
