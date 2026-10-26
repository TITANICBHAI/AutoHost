package app.autohost.runtime.api

import java.util.concurrent.ConcurrentHashMap

/** Registry of runtimes by type. The engine resolves `RunSpec.runtime` through here. */
class RuntimeRegistry {
    private val providers = ConcurrentHashMap<String, RuntimeProvider>()
    @Volatile private var deathListener: ((String) -> Unit)? = null

    fun register(provider: RuntimeProvider) {
        providers[provider.type] = provider
        provider.setDeathListener(deathListener)
    }

    fun get(type: String): RuntimeProvider? = providers[type]

    fun all(): List<RuntimeProvider> = providers.values.toList()

    fun setDeathListener(listener: ((String) -> Unit)?) {
        deathListener = listener
        providers.values.forEach { it.setDeathListener(listener) }
    }

    fun clear() {
        providers.values.forEach { it.setDeathListener(null) }
        providers.clear()
    }
}
