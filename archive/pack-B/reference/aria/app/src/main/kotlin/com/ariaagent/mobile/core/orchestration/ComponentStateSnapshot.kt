// Ported from donors/orchestration-java/ComponentStateSnapshot.java
// Original repo: TITANICBHAI/AI-ASSISTANT-INCOMPLETE
// Changes: idiomatic Kotlin data class, immutable defensive copy, hash uses Map.hashCode().

package com.ariaagent.mobile.core.orchestration

/**
 * Immutable snapshot of a component's state at a point in time.
 *
 * The [stateHash] is derived from the contents of [state] so two snapshots with
 * the same field values compare equal regardless of identity. This is what the
 * [DiffEngine] uses for its fast-path equality check before computing a real
 * field-by-field diff.
 */
data class ComponentStateSnapshot(
    val componentId: String,
    val version: Int,
    val state: Map<String, Any?>,
    val timestamp: Long = System.currentTimeMillis(),
) {
    /** Stable hash over the state map; equal contents → equal hash. */
    val stateHash: String = state.hashCode().toString()

    /** Defensive accessor mirroring the donor API. */
    fun get(key: String): Any? = state[key]

    override fun toString(): String =
        "ComponentStateSnapshot(componentId=$componentId, version=$version, " +
            "timestamp=$timestamp, hash=$stateHash)"
}
