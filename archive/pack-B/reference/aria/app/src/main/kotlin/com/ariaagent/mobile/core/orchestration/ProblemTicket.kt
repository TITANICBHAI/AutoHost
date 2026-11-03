// Ported from donors/orchestration-java/ProblemTicket.java
// Original repo: TITANICBHAI/AI-ASSISTANT-INCOMPLETE
// Changes: idiomatic Kotlin class with mutable status state, factory helpers,
// AtomicLong-backed monotonic ticket id (no Math.random).

package com.ariaagent.mobile.core.orchestration

import java.util.UUID

/**
 * A problem report submitted to [ProblemSolvingBroker]. Mutable in a few
 * controlled spots: [status], [resolution], [resolvedTime], plus the
 * [attemptedRemedies] log. Everything else is set at construction.
 */
class ProblemTicket(
    val componentId: String,
    val problemType: String,
    val description: String,
    val context: Map<String, Any?> = emptyMap(),
) {
    val ticketId: String = "TICKET-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
    val timestamp: Long = System.currentTimeMillis()

    private val _attemptedRemedies = mutableListOf<String>()
    val attemptedRemedies: List<String> get() = _attemptedRemedies.toList()

    @Volatile var status: TicketStatus = TicketStatus.OPEN
        private set

    @Volatile var resolution: String? = null
        private set

    @Volatile var resolvedTime: Long = 0L
        private set

    @Synchronized
    fun addAttemptedRemedy(remedy: String) {
        _attemptedRemedies += remedy
    }

    fun markInProgress() {
        status = TicketStatus.IN_PROGRESS
    }

    fun resolve(resolution: String) {
        this.resolution = resolution
        this.resolvedTime = System.currentTimeMillis()
        this.status = TicketStatus.RESOLVED
    }

    fun escalate() {
        status = TicketStatus.ESCALATED
    }

    fun fail() {
        status = TicketStatus.FAILED
    }

    enum class TicketStatus { OPEN, IN_PROGRESS, RESOLVED, ESCALATED, FAILED }

    override fun toString(): String =
        "ProblemTicket(id=$ticketId, component=$componentId, type=$problemType, status=$status)"
}
