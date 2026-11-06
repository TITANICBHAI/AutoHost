package app.autohost.capabilities.a11y

// Adapted from closepaw tool/action/PointActionExecutorCore.kt + ClickExecutor.kt (Apache-2.0,
// Copyright 2026 ClosePaw Authors). Changes: the refinement is a PURE table over bounds/flags
// (no AccessibilityNodeInfo); `ActionOutcome` replaced by a sealed Result; the ambiguity guard
// fails closed (docs/REUSE_MATRIX.md RM-A3, P12-09).

import app.autohost.core.model.Bounds

/**
 * P12-09 (A5, RM-A3): hotspot promotion. Inside a large clickable container the exact centre can
 * be a dead zone while the real hotspot is an actionable child (a button laid out off-centre).
 *
 * Rules (fail closed):
 *  - candidates: actionable nodes STRICTLY inside the target bounds whose bounds contain the tap
 *    point (or the target's centre when the tap point is unknown);
 *  - promote to the candidate with the SMALLEST area;
 *  - two or more candidates tie on the smallest area → `Ambiguous` (never a guessed click).
 */
object HotspotRefiner {

    data class Candidate(val id: String, val bounds: Bounds) {
        val area: Int get() = (bounds.r - bounds.l) * (bounds.b - bounds.t)
        fun contains(x: Int, y: Int): Boolean =
            x in bounds.l..bounds.r && y in bounds.t..bounds.b
    }

    sealed class Result {
        /** The promoted hotspot: click THIS node instead of the container centre. */
        data class Promoted(val candidate: Candidate) : Result()

        /** No actionable child improves on the container — click the target itself. */
        data object Keep : Result()

        /** Two or more minimal-area candidates: the guard refuses a guessed click. */
        data class Ambiguous(val candidates: List<Candidate>) : Result()
    }

    fun refine(
        targetBounds: Bounds,
        nodes: List<Candidate>,
        tapX: Int? = null,
        tapY: Int? = null,
    ): Result {
        // R3 fix (the CI red pair): the tap-point filter applies ONLY when the tap point is
        // EXPLICITLY given — the default (no tap) promotes the smallest fully-contained child
        // (the A5 dead-zone story: the coordinate click at the centre, the child off-centre).
        // The old "STRICTLY inside" disjunct let a child STICKING OUT of the container be
        // promoted — full containment is the rule (the overlapping-edge test).
        val hasTap = tapX != null && tapY != null
        val children = nodes.filter { c ->
            c.bounds.l >= targetBounds.l && c.bounds.r <= targetBounds.r &&
                c.bounds.t >= targetBounds.t && c.bounds.b <= targetBounds.b &&
                (!hasTap || c.contains(tapX!!, tapY!!))
        }
        if (children.isEmpty()) return Result.Keep
        val minArea = children.minOf { it.area }
        val smallest = children.filter { it.area == minArea }
        return when {
            smallest.size > 1 -> Result.Ambiguous(smallest)
            else -> Result.Promoted(smallest.single())
        }
    }
}
