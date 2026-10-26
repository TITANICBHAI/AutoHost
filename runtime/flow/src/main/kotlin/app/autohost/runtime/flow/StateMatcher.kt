package app.autohost.runtime.flow

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P6-08: state similarity for guarded replay (plan §7.9). Node-set Jaccard weighted 0.85 plus
 * package/activity match weighted 0.15, threshold 0.85. Pure function, data-only — the caller
 * fetches the live snapshot through the broker (`device.ui.snapshot`) and reduces it to
 * [LiveState] the same way the recorder did.
 */
object StateMatcher {

    const val NODE_WEIGHT = 0.85
    const val CONTEXT_WEIGHT = 0.15
    const val DEFAULT_THRESHOLD = 0.85

    /** The live screen reduced to the same shape the recorder snapshotted. */
    data class LiveState(val packageName: String, val activity: String, val keys: List<String>)

    data class Verdict(val score: Double, val threshold: Double) {
        val matched: Boolean get() = score >= threshold
    }

    /** Builds a [LiveState] from a `device.ui.snapshot` broker result (nodes + package + activity). */
    fun fromSnapshot(snapshot: JsonObject): LiveState {
        val pkg = (snapshot["package"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        val activity = (snapshot["activity"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        val keys = (snapshot["nodes"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.map { node ->
                fun str(key: String): String = (node[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                StepSnapshot.nodeKey(str("cls"), str("id"), str("text"), str("desc"))
            }
            ?: emptyList()
        return LiveState(pkg, activity, keys)
    }

    fun compare(expected: StepSnapshot, actual: LiveState, threshold: Double = DEFAULT_THRESHOLD): Verdict {
        val expectedSet = expected.keys.toSet()
        val actualSet = actual.keys.toSet()
        val union = expectedSet.size + actualSet.size - intersection(expectedSet, actualSet)
        val jaccard = if (union == 0) 1.0 else intersection(expectedSet, actualSet).toDouble() / union
        val pkgScore = if (expected.packageName == actual.packageName) 0.5 else 0.0
        val actScore = if (expected.activity == actual.activity) 0.5 else 0.0
        val score = NODE_WEIGHT * jaccard + CONTEXT_WEIGHT * (pkgScore + actScore)
        return Verdict(score, threshold)
    }

    private fun intersection(a: Set<String>, b: Set<String>): Int = a.count { it in b }

    /** Evidence payload attached when the guard stops a replay (handoff: selector + dump + evidence). */
    fun mismatchEvidence(label: String, expected: StepSnapshot, verdict: Verdict): JsonObject =
        buildJsonObject {
            put("step", label)
            put("code", "PRE_STATE_MISMATCH")
            put("expected_package", expected.packageName)
            put("expected_activity", expected.activity)
            put("expected_keys", expected.keys.size)
            put("score", verdict.score)
            put("threshold", verdict.threshold)
        }
}
