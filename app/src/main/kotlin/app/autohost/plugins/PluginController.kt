package app.autohost.plugins

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.autohost.core.common.AhLog
import app.autohost.core.common.Clock
import app.autohost.core.broker.CapabilityRegistry
import app.autohost.core.engine.plugins.PluginHost
import app.autohost.plugin.ICapabilityProvider

/**
 * P11-02 (ADR-079): the Android half of the plugin host — discovery, binding and lifecycle.
 *
 * Discovery rides the package manager (the host manifest carries a matching `<queries>` intent
 * entry so API 30+ package visibility sees plugin services); each hit is bound and its
 * `ICapabilityProvider` wrapped into the pure [PluginHost] contract. The pure host decides
 * EVERYTHING (api band, describe() shape, collisions); this controller only moves bytes and
 * manages bindings. Registration is idempotent per plugin id; a disconnect unregisters exactly
 * the providers that plugin contributed.
 */
class PluginController(
    private val context: Context,
    private val registry: CapabilityRegistry,
    private val clock: Clock,
    private val onPermissionFragment: (Map<String, List<String>>) -> Unit,
) {

    private val connections = LinkedHashMap<String, Connection>()

    /** Re-queries the package manager and reconciles bindings (safe to call any time). */
    @Synchronized
    fun refresh() {
        val intent = Intent(PluginHost.DISCOVERY_ACTION)
        val services = try {
            context.packageManager.queryIntentServices(intent, 0)
        } catch (e: Exception) {
            AhLog.w(TAG, "plugin query failed: ${e.message}")
            emptyList()
        }
        val wanted = LinkedHashMap<String, ComponentName>()
        for (info in services) {
            val component = ComponentName(info.serviceInfo.packageName, info.serviceInfo.name)
            val key = component.flattenToShortString()
            // The same component may match twice (multiple user profiles); first wins.
            wanted.putIfAbsent(key, component)
        }
        for ((key, component) in wanted) {
            if (connections.containsKey(key)) continue
            bind(key, component)
        }
        for ((key, connection) in connections.toList()) {
            if (!wanted.containsKey(key)) unbind(key, connection)
        }
        if (wanted.isEmpty() && connections.isEmpty()) {
            AhLog.i(TAG, "no capability plugins installed")
        }
    }

    @Synchronized
    private fun bind(key: String, component: ComponentName) {
        val connection = Connection(key, component)
        val ok = runCatching {
            context.bindService(Intent(PluginHost.DISCOVERY_ACTION).setComponent(component), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!ok) {
            AhLog.w(TAG, "plugin bind refused: $key")
            return
        }
        connections[key] = connection
    }

    @Synchronized
    private fun unbind(key: String, connection: Connection) {
        runCatching { context.unbindService(connection) }
        connection.unregister()
        connections.remove(key)
    }

    @Synchronized
    fun detachAll() {
        for ((key, connection) in connections.toList()) unbind(key, connection)
    }

    private inner class Connection(
        val key: String,
        val component: ComponentName,
    ) : ServiceConnection {

        val providerIds = ArrayList<String>()
        @Volatile private var registered = false

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val stub = ICapabilityProvider.Stub.asInterface(binder) ?: return
            val pluginId = component.packageName
            val adapter = object : PluginHost.PluginBinder {
                override fun apiLevel(): Int = runCatching { stub.apiLevel }.getOrDefault(Int.MAX_VALUE)
                override fun describe(): String = runCatching { stub.describe }.getOrDefault("")
                override fun availability(envJson: String): String = stub.availability(envJson)
                override fun invoke(callJson: String): String = stub.invoke(callJson)
            }
            // Taken ids = everything currently registered that is NOT already from this plugin,
            // so a refresh re-attach does not collide with itself.
            val taken = registry.all().filter { !it.providerId.startsWith("plugin:$pluginId:") }.map { it.capabilityId }.toSet()
            val result = PluginHost.attach(pluginId, adapter, taken)
            val attached = result.attached
            if (attached == null) {
                for (issue in result.issues) {
                    AhLog.w(TAG, "plugin $pluginId rejected: $issue")
                }
                return
            }
            register(attached)
        }

        @Synchronized
        private fun register(attached: PluginHost.Attached) {
            if (registered) return
            for (provider in attached.providers) registry.register(provider)
            providerIds += attached.providers.map { it.providerId }
            registered = true
            onPermissionFragment(attached.permissionFragment)
            AhLog.i(
                TAG,
                "plugin attached: ${attached.pluginId} (${attached.capabilities.size} capabilities)",
                app.autohost.core.common.AhJson.json.buildJsonObject {
                    put("plugin", kotlinx.serialization.json.JsonPrimitive(attached.pluginId))
                    put("capabilities", kotlinx.serialization.json.JsonPrimitive(attached.capabilities.size))
                    put("at_ms", kotlinx.serialization.json.JsonPrimitive(clock.nowMs()))
                },
            )
        }

        @Synchronized
        fun unregister() {
            for (providerId in providerIds) registry.unregister(providerId)
            providerIds.clear()
            registered = false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The host keeps the binding; the providers stay registered — availability() will
            // report honestly through the dead binder (wrapped as Unsupported) until refresh().
            AhLog.w(TAG, "plugin disconnected: $key")
        }
    }

    private companion object {
        const val TAG = "plugins"
    }
}
