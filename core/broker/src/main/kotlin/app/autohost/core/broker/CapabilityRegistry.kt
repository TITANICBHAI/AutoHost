package app.autohost.core.broker

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Registry of capability providers. Several providers may implement one capability id (fallback chains, e.g.
 * screenshot: accessibility API 30+ → MediaProjection → shell); order of registration is the fallback order.
 */
class CapabilityRegistry {
    private val providers = CopyOnWriteArrayList<CapabilityProvider>()
    private val byCapability = ConcurrentHashMap<String, MutableList<CapabilityProvider>>()

    fun register(provider: CapabilityProvider) {
        providers.addIfAbsent(provider)
        byCapability.getOrPut(provider.capabilityId) { CopyOnWriteArrayList() }.add(provider)
    }

    fun unregister(providerId: String) {
        val removed = providers.filter { it.providerId == providerId }
        providers.removeAll(removed.toSet())
        byCapability.values.forEach { list -> list.removeAll(removed.toSet()) }
    }

    fun providers(capabilityId: String): List<CapabilityProvider> =
        byCapability[capabilityId]?.toList() ?: emptyList()

    fun all(): List<CapabilityProvider> = providers.toList()

    fun capabilityIds(): Set<String> = byCapability.keys.toSet()

    fun clear() {
        providers.clear()
        byCapability.clear()
    }
}
