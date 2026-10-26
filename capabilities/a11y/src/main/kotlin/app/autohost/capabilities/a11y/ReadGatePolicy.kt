package app.autohost.capabilities.a11y

/**
 * Pure policy for [ReadGate] (plan §5.7): decides, before a read starts, whether to proceed, degrade to the
 * last good snapshot, or reject. Takes plain inputs and returns a sealed decision so every branch is
 * exhaustively unit-tested.
 */
class ReadGatePolicy(
    private val maxConcurrentReads: Int = 2,
    private val budgetMs: Long = 2_500,
) {
    enum class Reason { BUDGET_EXHAUSTED, POOL_SATURATED }

    sealed class Decision {
        /** Start (or join, via single-flight) a read. */
        object Proceed : Decision() {
            override fun toString(): String = "Proceed"
        }

        /** Skip the read: serve the last good snapshot, marked degraded. */
        data class Degrade(val reason: Reason, val detail: String) : Decision()
    }

    fun decide(activeReads: Int, elapsedMs: Long): Decision = when {
        elapsedMs >= budgetMs -> Decision.Degrade(Reason.BUDGET_EXHAUSTED, "read exceeded its ${budgetMs}ms budget")
        activeReads >= maxConcurrentReads ->
            Decision.Degrade(Reason.POOL_SATURATED, "read pool is saturated ($activeReads/$maxConcurrentReads)")
        else -> Decision.Proceed
    }

    /** Degrade reason recorded on snapshots served after a wedged read. */
    fun degradedFields(reason: Reason, snapshotAgeMs: Long, detail: String): Map<String, Any> = mapOf(
        "degraded" to true,
        "degraded_reason" to reason.name,
        "snapshot_age_ms" to snapshotAgeMs,
        "detail" to detail,
    )
}
