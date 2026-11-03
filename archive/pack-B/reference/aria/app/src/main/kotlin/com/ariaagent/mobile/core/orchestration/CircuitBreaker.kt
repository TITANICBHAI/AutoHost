// Ported from donors/orchestration-java/CircuitBreaker.java
// Original repo: TITANICBHAI/AI-ASSISTANT-INCOMPLETE
// Changes: fixed donor package typo (`com.aiassistant.core/orchestration`),
// idiomatic Kotlin with @Volatile state and AtomicInteger/AtomicLong counters.

package com.ariaagent.mobile.core.orchestration

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Classic three-state circuit breaker (CLOSED → OPEN → HALF_OPEN → CLOSED).
 *
 * Wraps every call into a downstream component:
 *   1. CLOSED   — calls flow through; failures increment the counter.
 *   2. OPEN     — calls are short-circuited until [cooldownPeriodMs] elapses.
 *   3. HALF_OPEN — first call after cooldown is allowed; success → CLOSED,
 *                  failure → OPEN again.
 *
 * Thread-safe via atomics and a single @Volatile state. No locking required.
 */
class CircuitBreaker(
    val componentId: String,
    private val failureThreshold: Int,
    private val cooldownPeriodMs: Long,
) {
    private val failureCount = AtomicInteger(0)
    private val lastFailureTime = AtomicLong(0L)
    private val executionCount = AtomicInteger(0)

    @Volatile var state: State = State.CLOSED
        private set

    /** @return true if the call is allowed to proceed. */
    fun allowExecution(): Boolean {
        executionCount.incrementAndGet()
        if (state != State.OPEN) return true

        val timeSinceFailure = System.currentTimeMillis() - lastFailureTime.get()
        if (timeSinceFailure >= cooldownPeriodMs) {
            Log.i(TAG, "Circuit for $componentId entering HALF_OPEN after cooldown")
            state = State.HALF_OPEN
            return true
        }
        Log.w(TAG, "Circuit for $componentId is OPEN — blocking execution")
        return false
    }

    fun recordSuccess() {
        failureCount.set(0)
        if (state == State.HALF_OPEN) {
            Log.i(TAG, "Circuit for $componentId transitioning HALF_OPEN → CLOSED")
            state = State.CLOSED
        }
    }

    fun recordFailure() {
        val failures = failureCount.incrementAndGet()
        lastFailureTime.set(System.currentTimeMillis())
        if (failures >= failureThreshold && state != State.OPEN) {
            Log.w(TAG, "Circuit for $componentId OPEN — threshold reached ($failures/$failureThreshold)")
            state = State.OPEN
        }
    }

    fun reset() {
        failureCount.set(0)
        state = State.CLOSED
        Log.i(TAG, "Circuit for $componentId reset to CLOSED")
    }

    fun getFailureCount(): Int = failureCount.get()
    fun getExecutionCount(): Int = executionCount.get()

    enum class State { CLOSED, OPEN, HALF_OPEN }

    companion object {
        private const val TAG = "CircuitBreaker"
    }
}
