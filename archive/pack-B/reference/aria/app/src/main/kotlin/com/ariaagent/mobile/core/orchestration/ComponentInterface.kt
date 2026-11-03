// Ported from donors/orchestration-java/ComponentInterface.java
// Original repo: TITANICBHAI/AI-ASSISTANT-INCOMPLETE
// Changes: idiomatic Kotlin contract, default heartbeat() implementation, suspend lifecycle hooks.

package com.ariaagent.mobile.core.orchestration

/**
 * Contract every component plugged into the [CentralOrchestrator] must satisfy.
 *
 * Implementations should be coroutine-aware: [initialize], [start], and [stop]
 * are suspending so the orchestrator can wire heavy work (model loading, JNI
 * init) onto Dispatchers.IO without blocking the lifecycle.
 *
 * [execute] runs on the caller's dispatcher — it should not block.
 */
interface ComponentInterface {

    val componentId: String

    val componentName: String

    val capabilities: List<String>

    suspend fun initialize()

    suspend fun start()

    suspend fun stop()

    fun captureState(): ComponentStateSnapshot

    fun restoreState(snapshot: ComponentStateSnapshot)

    suspend fun execute(input: Map<String, Any?>): Map<String, Any?>

    fun isHealthy(): Boolean

    fun heartbeat() {
        // Default no-op; HealthMonitor calls recordHeartbeat() on its own.
    }

    fun status(): String
}
