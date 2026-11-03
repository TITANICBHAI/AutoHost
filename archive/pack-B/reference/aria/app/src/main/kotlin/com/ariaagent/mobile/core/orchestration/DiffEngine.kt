// Ported from donors/orchestration-java/DiffEngine.java
// Original repo: TITANICBHAI/AI-ASSISTANT-INCOMPLETE
// Changes: idiomatic Kotlin, ConcurrentHashMap kept, throttle interval now a
// constructor parameter, FieldDiff.DiffType enum replaces stringly-typed donor.

package com.ariaagent.mobile.core.orchestration

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Compares snapshots of component state against an "expected" baseline so the
 * orchestrator can detect drift (a component that quietly entered an
 * inconsistent state). Periodic check runs through every component with a
 * snapshot; ad-hoc check is throttled per component to avoid hammering.
 *
 * Severity is heuristic: any field whose name contains "error", "critical",
 * "health", or "status" is considered critical; > 3 field deltas → WARNING;
 * otherwise INFO.
 */
class DiffEngine(
    private val throttleIntervalMs: Long = DEFAULT_THROTTLE_INTERVAL_MS,
) {
    private val latestSnapshots = ConcurrentHashMap<String, ComponentStateSnapshot>()
    private val expectedSnapshots = ConcurrentHashMap<String, ComponentStateSnapshot>()
    private val lastDiffCheck = ConcurrentHashMap<String, Long>()

    @Volatile private var eventRouter: EventRouter? = null

    fun setEventRouter(router: EventRouter) {
        this.eventRouter = router
    }

    fun captureSnapshot(snapshot: ComponentStateSnapshot) {
        latestSnapshots[snapshot.componentId] = snapshot
        Log.d(TAG, "Captured snapshot for ${snapshot.componentId} v${snapshot.version}")
    }

    fun setExpectedState(snapshot: ComponentStateSnapshot) {
        expectedSnapshots[snapshot.componentId] = snapshot
        Log.d(TAG, "Set expected state for ${snapshot.componentId} v${snapshot.version}")
    }

    fun checkDiff(componentId: String): StateDiff? {
        val now = System.currentTimeMillis()
        val lastCheck = lastDiffCheck[componentId]
        if (lastCheck != null && (now - lastCheck) < throttleIntervalMs) {
            Log.d(TAG, "Throttling diff check for $componentId")
            return null
        }
        val expected = expectedSnapshots[componentId] ?: return null
        val actual = latestSnapshots[componentId] ?: return null

        lastDiffCheck[componentId] = now
        if (expected.stateHash == actual.stateHash) return null

        val diff = computeDiff(expected, actual) ?: return null

        eventRouter?.publish(
            OrchestrationEvent(
                eventType = OrchestrationEvent.Type.STATE_DIFF_DETECTED,
                source = componentId,
                data = mapOf(
                    "diff" to diff,
                    "severity" to diff.severity.name,
                ),
            ),
        )
        return diff
    }

    fun performPeriodicDiffCheck() {
        Log.d(TAG, "Performing periodic diff check")
        for (componentId in latestSnapshots.keys) {
            checkDiff(componentId)
        }
    }

    fun getLatestSnapshot(componentId: String): ComponentStateSnapshot? = latestSnapshots[componentId]
    fun getExpectedSnapshot(componentId: String): ComponentStateSnapshot? = expectedSnapshots[componentId]

    private fun computeDiff(
        expected: ComponentStateSnapshot,
        actual: ComponentStateSnapshot,
    ): StateDiff? {
        val expectedState = expected.state
        val actualState = actual.state
        val fieldDiffs = mutableListOf<StateDiff.FieldDiff>()

        for ((field, expectedValue) in expectedState) {
            val actualValue = actualState[field]
            if (expectedValue == null && actualValue == null) continue
            if (expectedValue != actualValue) {
                val type = if (!actualState.containsKey(field)) {
                    StateDiff.FieldDiff.DiffType.MISSING_FIELD
                } else {
                    StateDiff.FieldDiff.DiffType.VALUE_MISMATCH
                }
                fieldDiffs += StateDiff.FieldDiff(field, expectedValue, actualValue, type)
            }
        }
        for (key in actualState.keys) {
            if (!expectedState.containsKey(key)) {
                fieldDiffs += StateDiff.FieldDiff(
                    fieldName = key,
                    expectedValue = null,
                    actualValue = actualState[key],
                    diffType = StateDiff.FieldDiff.DiffType.UNEXPECTED_FIELD,
                )
            }
        }
        if (fieldDiffs.isEmpty()) return null

        return StateDiff(
            componentId = expected.componentId,
            severity = determineSeverity(fieldDiffs),
            description = "State mismatch detected: ${fieldDiffs.size} field(s) differ",
            fieldDiffs = fieldDiffs,
            expectedState = expected,
            actualState = actual,
        )
    }

    private fun determineSeverity(diffs: List<StateDiff.FieldDiff>): StateDiff.Severity {
        val criticalFields = diffs.count { isCriticalField(it.fieldName) }
        return when {
            criticalFields > 0 -> StateDiff.Severity.CRITICAL
            diffs.size > 3 -> StateDiff.Severity.WARNING
            else -> StateDiff.Severity.INFO
        }
    }

    private fun isCriticalField(fieldName: String): Boolean {
        val n = fieldName.lowercase()
        return "error" in n || "critical" in n || "health" in n || "status" in n
    }

    companion object {
        private const val TAG = "DiffEngine"
        private const val DEFAULT_THROTTLE_INTERVAL_MS = 5_000L
    }
}
