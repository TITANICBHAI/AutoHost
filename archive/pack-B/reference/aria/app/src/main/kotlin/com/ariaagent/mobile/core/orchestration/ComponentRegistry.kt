// Ported from donors/orchestration-java/ComponentRegistry.java
// Original repo: TITANICBHAI/AI-ASSISTANT-INCOMPLETE
// Changes: idiomatic Kotlin, ConcurrentHashMap kept for thread safety, builder-free
// registration, optional EventRouter wiring, capability lookup returns immutable list.

package com.ariaagent.mobile.core.orchestration

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe registry of components participating in orchestration.
 *
 * Tracks each component's identity, capabilities, status, last heartbeat, and
 * arbitrary metadata. Forwards lifecycle events to an optional [EventRouter]
 * so other subsystems (the [HealthMonitor], policy components, the agent
 * loop) can react when components come and go.
 */
class ComponentRegistry {

    private val components = ConcurrentHashMap<String, RegisteredComponent>()
    private val capabilityMap = ConcurrentHashMap<String, MutableList<String>>()

    @Volatile private var eventRouter: EventRouter? = null

    fun setEventRouter(router: EventRouter) {
        this.eventRouter = router
    }

    fun registerComponent(
        componentId: String,
        componentName: String,
        capabilities: List<String>,
    ) {
        val component = RegisteredComponent(
            componentId = componentId,
            componentName = componentName,
            capabilities = capabilities.toList(),
            registrationTime = System.currentTimeMillis(),
        )
        components[componentId] = component

        for (capability in capabilities) {
            capabilityMap.computeIfAbsent(capability) { mutableListOf() }.add(componentId)
        }

        Log.i(TAG, "Registered component: $componentName ($componentId) with ${capabilities.size} capabilities")

        eventRouter?.publish(
            OrchestrationEvent(
                eventType = OrchestrationEvent.Type.COMPONENT_REGISTERED,
                source = componentId,
                data = mapOf(
                    "component_name" to componentName,
                    "capabilities" to capabilities,
                ),
            ),
        )
    }

    fun unregisterComponent(componentId: String) {
        val component = components.remove(componentId) ?: return

        for (capability in component.capabilities) {
            capabilityMap[capability]?.remove(componentId)
        }
        Log.i(TAG, "Unregistered component: $componentId")

        eventRouter?.publish(
            OrchestrationEvent(
                eventType = OrchestrationEvent.Type.COMPONENT_UNREGISTERED,
                source = componentId,
            ),
        )
    }

    fun updateComponentStatus(componentId: String, status: ComponentStatus) {
        val component = components[componentId] ?: return
        component.status = status
        component.lastStatusUpdate = System.currentTimeMillis()
        Log.d(TAG, "Component $componentId status updated to $status")

        eventRouter?.publish(
            OrchestrationEvent(
                eventType = OrchestrationEvent.Type.COMPONENT_STATUS_CHANGED,
                source = componentId,
                data = mapOf("status" to status.name),
            ),
        )
    }

    fun updateHeartbeat(componentId: String) {
        components[componentId]?.lastHeartbeat = System.currentTimeMillis()
    }

    fun getComponent(componentId: String): RegisteredComponent? = components[componentId]

    fun getComponentsByCapability(capability: String): List<String> =
        capabilityMap[capability]?.toList().orEmpty()

    fun getAllComponents(): List<RegisteredComponent> = components.values.toList()

    fun getComponentsByStatus(status: ComponentStatus): List<RegisteredComponent> =
        components.values.filter { it.status == status }

    fun isComponentHealthy(componentId: String): Boolean {
        val component = components[componentId] ?: return false
        val heartbeatAge = System.currentTimeMillis() - component.lastHeartbeat
        return component.status == ComponentStatus.ACTIVE && heartbeatAge < HEARTBEAT_TIMEOUT_MS
    }

    enum class ComponentStatus {
        INACTIVE, INITIALIZING, ACTIVE, DEGRADED, ERROR, ISOLATED
    }

    /**
     * Mutable (atomically updated) record about a registered component. Kept
     * as a regular class with @Volatile fields rather than a data class so
     * we can mutate the status fields from any thread cheaply.
     */
    class RegisteredComponent(
        val componentId: String,
        val componentName: String,
        val capabilities: List<String>,
        val registrationTime: Long,
    ) {
        @Volatile var status: ComponentStatus = ComponentStatus.INACTIVE
        @Volatile var lastHeartbeat: Long = System.currentTimeMillis()
        @Volatile var lastStatusUpdate: Long = System.currentTimeMillis()

        private val metadata = ConcurrentHashMap<String, Any?>()
        fun setMetadata(key: String, value: Any?) {
            if (value == null) metadata.remove(key) else metadata[key] = value
        }
        fun getMetadata(key: String): Any? = metadata[key]
        fun metadataSnapshot(): Map<String, Any?> = metadata.toMap()
    }

    companion object {
        private const val TAG = "ComponentRegistry"
        private const val HEARTBEAT_TIMEOUT_MS = 30_000L
    }
}
