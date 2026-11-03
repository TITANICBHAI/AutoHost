// Ported from donors/orchestration-java/OrchestrationEvent.java
// Original repo: TITANICBHAI/AI-ASSISTANT-INCOMPLETE
// Changes: idiomatic Kotlin data class + companion event-name constants.

package com.ariaagent.mobile.core.orchestration

/**
 * The unit of communication on the orchestration [EventRouter]. Distinct from
 * [com.ariaagent.mobile.core.events.AgentEventBus] events: those are
 * UI-facing agent state. These are internal lifecycle / health signals between
 * orchestration components.
 */
data class OrchestrationEvent(
    val eventType: String,
    val source: String,
    val data: Map<String, Any?> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun get(key: String): Any? = data[key]

    /** Canonical event-type names used by the orchestration layer. */
    object Type {
        const val COMPONENT_REGISTERED = "component.registered"
        const val COMPONENT_UNREGISTERED = "component.unregistered"
        const val COMPONENT_STATUS_CHANGED = "component.status.changed"
        const val COMPONENT_DEGRADED = "component.degraded"
        const val COMPONENT_ERROR = "component.error"
        const val STATE_DIFF_DETECTED = "state.diff.detected"
        const val HEALTH_CHECK_FAILED = "health.check.failed"
    }
}
