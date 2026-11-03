// Ported from donors/orchestration-java/HealthMonitor.java
// Original repo: TITANICBHAI/AI-ASSISTANT-INCOMPLETE
// Changes: idiomatic Kotlin, ConcurrentHashMap kept, ComponentHealth as a
// data-bag class with @Volatile fields, configurable thresholds.

package com.ariaagent.mobile.core.orchestration

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks per-component health: heartbeat freshness, success/error counters,
 * consecutive-failure streaks, restart counts. Owns a [CircuitBreaker] per
 * component which the orchestrator's scheduler consults before invoking work.
 *
 * Thresholds are configurable: [errorRateDegradeThreshold] (0..1),
 * [consecutiveFailureDegradeThreshold], [heartbeatTimeoutMs],
 * [circuitBreakerFailureThreshold], [circuitBreakerCooldownMs].
 */
class HealthMonitor(
    private val componentRegistry: ComponentRegistry,
    private val errorRateDegradeThreshold: Float = 0.5f,
    private val consecutiveFailureDegradeThreshold: Int = 3,
    private val heartbeatTimeoutMs: Long = 30_000L,
    private val circuitBreakerFailureThreshold: Int = 5,
    private val circuitBreakerCooldownMs: Long = 60_000L,
) {
    private val healthRecords = ConcurrentHashMap<String, ComponentHealth>()
    private val circuitBreakers = ConcurrentHashMap<String, CircuitBreaker>()
    private val running = AtomicBoolean(false)

    @Volatile private var eventRouter: EventRouter? = null

    fun setEventRouter(router: EventRouter) {
        this.eventRouter = router
    }

    fun start() {
        if (running.compareAndSet(false, true)) {
            Log.i(TAG, "Health Monitor started")
        }
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            Log.i(TAG, "Health Monitor stopped")
        }
    }

    fun isRunning(): Boolean = running.get()

    fun recordHeartbeat(componentId: String) {
        val health = healthRecords.computeIfAbsent(componentId, ::ComponentHealth)
        health.lastHeartbeat = System.currentTimeMillis()
        health.consecutiveFailures = 0
        componentRegistry.updateHeartbeat(componentId)
    }

    fun recordError(componentId: String, errorType: String) {
        val health = healthRecords.computeIfAbsent(componentId, ::ComponentHealth)
        health.errorCount += 1
        health.consecutiveFailures += 1
        health.lastError = System.currentTimeMillis()
        Log.w(TAG, "Error recorded for $componentId — type=$errorType consecutive=${health.consecutiveFailures}")

        if (health.consecutiveFailures >= consecutiveFailureDegradeThreshold) {
            degradeComponent(componentId)
        }
        getOrCreateCircuitBreaker(componentId).recordFailure()
    }

    fun recordSuccess(componentId: String) {
        val health = healthRecords.computeIfAbsent(componentId, ::ComponentHealth)
        health.successCount += 1
        health.consecutiveFailures = 0
        circuitBreakers[componentId]?.recordSuccess()
    }

    fun isolateComponent(componentId: String) {
        componentRegistry.updateComponentStatus(componentId, ComponentRegistry.ComponentStatus.ISOLATED)
        Log.w(TAG, "Component $componentId isolated")
    }

    fun attemptWarmRestart(componentId: String) {
        Log.i(TAG, "Attempting warm restart for $componentId")
        healthRecords[componentId]?.let { it.restartCount += 1 }
        componentRegistry.updateComponentStatus(componentId, ComponentRegistry.ComponentStatus.INITIALIZING)
    }

    fun performHealthCheck() {
        val now = System.currentTimeMillis()
        for (component in componentRegistry.getAllComponents()) {
            val health = healthRecords[component.componentId] ?: continue
            val heartbeatAge = now - health.lastHeartbeat

            if (heartbeatAge > heartbeatTimeoutMs &&
                component.status == ComponentRegistry.ComponentStatus.ACTIVE
            ) {
                Log.w(TAG, "Health check failed for ${component.componentId} (heartbeat age ${heartbeatAge}ms)")
                eventRouter?.publish(
                    OrchestrationEvent(
                        eventType = OrchestrationEvent.Type.HEALTH_CHECK_FAILED,
                        source = component.componentId,
                        data = mapOf("heartbeat_age" to heartbeatAge),
                    ),
                )
            }
            if (calculateErrorRate(health) > errorRateDegradeThreshold) {
                degradeComponent(component.componentId)
            }
        }
    }

    fun getCircuitBreaker(componentId: String): CircuitBreaker? = circuitBreakers[componentId]
    fun getComponentHealth(componentId: String): ComponentHealth? = healthRecords[componentId]

    private fun degradeComponent(componentId: String) {
        componentRegistry.updateComponentStatus(componentId, ComponentRegistry.ComponentStatus.DEGRADED)
        eventRouter?.publish(
            OrchestrationEvent(
                eventType = OrchestrationEvent.Type.COMPONENT_DEGRADED,
                source = componentId,
            ),
        )
        Log.w(TAG, "Component $componentId marked as degraded")
    }

    private fun calculateErrorRate(health: ComponentHealth): Float {
        val total = health.successCount + health.errorCount
        if (total == 0) return 0f
        return health.errorCount.toFloat() / total
    }

    private fun getOrCreateCircuitBreaker(componentId: String): CircuitBreaker =
        circuitBreakers.computeIfAbsent(componentId) {
            CircuitBreaker(it, circuitBreakerFailureThreshold, circuitBreakerCooldownMs)
        }

    /** Mutable per-component health record. Field updates are racy on purpose
     *  — they are statistical signals; precise counts are not required. */
    class ComponentHealth(val componentId: String) {
        @Volatile var lastHeartbeat: Long = System.currentTimeMillis()
        @Volatile var lastError: Long = 0L
        @Volatile var errorCount: Int = 0
        @Volatile var successCount: Int = 0
        @Volatile var consecutiveFailures: Int = 0
        @Volatile var restartCount: Int = 0
    }

    companion object {
        private const val TAG = "HealthMonitor"
    }
}
