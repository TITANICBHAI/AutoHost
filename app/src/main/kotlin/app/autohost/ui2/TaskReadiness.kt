package app.autohost.ui2

/**
 * Whether one task can run on this phone right now, judged from the capabilities its manifest requires.
 * Pure: the same [Readiness] rules the Setup tab uses, so the two screens can never disagree.
 *
 * Only the verdicts that block a run count as "needs setup" ([Verdict.NEEDS_SETUP], [Verdict.NEEDS_SWITCH],
 * [Verdict.NEEDS_ADVANCED]). [Verdict.ASKS_ON_USE] is a normal Android prompt during the run, so it does not warn.
 * A capability id the catalog does not know is ignored rather than flagged (a false alarm is worse than no chip).
 */
object TaskReadiness {

    data class Blocker(val title: String, val verdict: Verdict)

    data class Result(val blockers: List<Blocker>) {
        val ready: Boolean get() = blockers.isEmpty()

        /** One plain sentence for the card; empty when nothing blocks the run. */
        fun line(): String = when {
            blockers.isEmpty() -> ""
            blockers.size == 1 -> "Needs: " + blockers.first().title
            else -> "Needs: " + blockers.first().title + " and ${blockers.size - 1} more"
        }
    }

    private val blocking = setOf(Verdict.NEEDS_SETUP, Verdict.NEEDS_SWITCH, Verdict.NEEDS_ADVANCED)

    fun of(requiredCapabilityIds: Collection<String>, live: LiveAccess): Result {
        val blockers = requiredCapabilityIds
            .distinct()
            .mapNotNull { FeatureCatalog.need(it) }
            .mapNotNull { cap ->
                val verdict = Readiness.of(cap, live)
                if (verdict in blocking) Blocker(cap.title, verdict) else null
            }
        return Result(blockers)
    }
}
