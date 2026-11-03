// Ported from donors/orchestration-java/StateDiff.java
// Original repo: TITANICBHAI/AI-ASSISTANT-INCOMPLETE
// Changes: data class + nested data class for FieldDiff, enum class for Severity.

package com.ariaagent.mobile.core.orchestration

/**
 * The result of [DiffEngine.checkDiff] — describes the per-field deltas
 * between an expected and actual [ComponentStateSnapshot] plus a severity
 * tier the orchestrator can route on.
 */
data class StateDiff(
    val componentId: String,
    val severity: Severity,
    val description: String,
    val fieldDiffs: List<FieldDiff>,
    val expectedState: ComponentStateSnapshot,
    val actualState: ComponentStateSnapshot,
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class Severity { INFO, WARNING, CRITICAL }

    data class FieldDiff(
        val fieldName: String,
        val expectedValue: Any?,
        val actualValue: Any?,
        val diffType: DiffType,
    ) {
        enum class DiffType { VALUE_MISMATCH, UNEXPECTED_FIELD, MISSING_FIELD }
    }

    override fun toString(): String =
        "StateDiff(component=$componentId, severity=$severity, " +
            "description='$description', fields=${fieldDiffs.size})"
}
