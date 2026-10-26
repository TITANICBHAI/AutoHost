package app.autohost.runtime.api

import java.util.concurrent.ConcurrentHashMap

/** Registry of runtimes by type. The engine resolves `RunSpec.runtime` through here. */
class RuntimeRegistry {
    private val providers = ConcurrentHashMap<String, RuntimeProvider>()

    fun register(provider: RuntimeProvider) {
        providers[provider.type] = provider
    }

    fun get(type: String): RuntimeProvider? = providers[type]

    fun all(): List<RuntimeProvider> = providers.values.toList()

    fun clear() = providers.clear()
}
