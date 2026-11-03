// Ported from donors/orchestration-java/ProblemSolvingBroker.java
// Original repo: TITANICBHAI/AI-ASSISTANT-INCOMPLETE
// Changes: cloud Groq dependency replaced with a pluggable ProblemSolver
// interface — the spine wires its on-device LlamaEngine implementation later.
// ExecutorService + Semaphore replaced with a CoroutineScope + Semaphore.

package com.ariaagent.mobile.core.orchestration

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Routes [ProblemTicket]s to a [ProblemSolver] implementation that returns a
 * solution string. Donor used Groq cloud API; the on-device aria-prime spine
 * stays cloud-free, so the actual solver is injected — typically the local
 * LlamaEngine wrapped in an [ProblemSolver] adapter.
 *
 * Concurrency is bounded by [maxConcurrentRequests] so a flood of failures
 * doesn't oversubscribe the LLM.
 */
class ProblemSolvingBroker(
    private val solver: ProblemSolver,
    private val maxConcurrentRequests: Int = 3,
) {
    /** Pluggable LLM-backed problem-solver. Implementations may be on-device
     *  (LlamaEngine) or cloud-hosted; the broker doesn't care. */
    fun interface ProblemSolver {
        /**
         * @return the LLM's solution text, or throws to signal failure.
         */
        suspend fun solve(prompt: String): String
    }

    private val tickets = ConcurrentHashMap<String, ProblemTicket>()
    private val rateLimiter = Semaphore(maxConcurrentRequests)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun submitProblem(ticket: ProblemTicket) {
        tickets[ticket.ticketId] = ticket
        ticket.markInProgress()
        Log.i(TAG, "Problem ticket submitted: ${ticket.ticketId} for ${ticket.componentId}")

        scope.launch { solveProblem(ticket) }
    }

    private suspend fun solveProblem(ticket: ProblemTicket) {
        rateLimiter.withPermit {
            val prompt = buildProblemPrompt(ticket)
            try {
                val solution = solver.solve(prompt)
                handleSolution(ticket, solution)
            } catch (t: Throwable) {
                handleError(ticket, t.message ?: t::class.java.simpleName)
            }
        }
    }

    private fun buildProblemPrompt(ticket: ProblemTicket): String = buildString {
        appendLine("You are an expert AI system troubleshooter.")
        appendLine("Analyze this problem and provide a concise, actionable solution.")
        appendLine()
        appendLine("Component: ${ticket.componentId}")
        appendLine("Problem Type: ${ticket.problemType}")
        appendLine("Description: ${ticket.description}")
        appendLine()
        if (ticket.attemptedRemedies.isNotEmpty()) {
            appendLine("Attempted Remedies:")
            ticket.attemptedRemedies.forEach { appendLine("- $it") }
            appendLine()
        }
        if (ticket.context.isNotEmpty()) {
            appendLine("Context: ${ticket.context}")
            appendLine()
        }
        appendLine("Provide:")
        appendLine("1. Root cause analysis")
        appendLine("2. Recommended solution")
        appendLine("3. Preventive measures")
        appendLine()
        append("Keep your response concise and actionable.")
    }

    private fun handleSolution(ticket: ProblemTicket, solution: String) {
        Log.i(TAG, "Solution received for ticket ${ticket.ticketId}")
        Log.d(TAG, "Solution: $solution")
        ticket.resolve(solution)
        translateAndExecute(ticket, solution)
    }

    private fun handleError(ticket: ProblemTicket, error: String) {
        Log.e(TAG, "Error solving ticket ${ticket.ticketId}: $error")
        ticket.fail()
        ticket.escalate()
    }

    private fun translateAndExecute(ticket: ProblemTicket, solution: String) {
        Log.i(TAG, "Translating solution to actionable commands for ${ticket.componentId}")
        val lower = solution.lowercase()
        when {
            "restart" in lower -> Log.i(TAG, "Hint: Restart component ${ticket.componentId}")
            "reset" in lower -> Log.i(TAG, "Hint: Reset component ${ticket.componentId}")
            "increase" in lower && "timeout" in lower ->
                Log.i(TAG, "Hint: Increase timeout for ${ticket.componentId}")
        }
        Log.d(TAG, "Full solution stored for component learning: $solution")
    }

    fun getTicket(ticketId: String): ProblemTicket? = tickets[ticketId]

    fun getTicketsByComponent(componentId: String): List<ProblemTicket> =
        tickets.values.filter { it.componentId == componentId }

    fun getOpenTickets(): List<ProblemTicket> =
        tickets.values.filter {
            it.status == ProblemTicket.TicketStatus.OPEN ||
                it.status == ProblemTicket.TicketStatus.IN_PROGRESS
        }

    fun shutdown() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "ProblemSolvingBroker"
    }
}
