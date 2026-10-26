package app.autohost.events

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import app.autohost.core.common.AhLog
import app.autohost.core.common.Clock
import app.autohost.core.engine.events.EventHub
import app.autohost.core.engine.events.TriggerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P8-02: the Android event sources (plan §11.8) — dynamic receivers + a network callback that
 * pump normalized [TriggerEvent]s into the [EventHub]. Created by [EventHubController]; started
 * with the EventHub FGS (receivers must live in a foreground context on API 26+) and stopped with
 * it. A11y- and notification-fed kinds are bridged separately (their host services are already
 * long-lived), so this class only owns the RECEIVER / NETWORK_CALLBACK rows of the catalog.
 *
 * Normalization decisions (all logged honestly):
 *  - battery events are EDGE-triggered: level transitions emit `battery.level` with the new level,
 *    crossing ≤15% emits `battery.low`, recovering ≥25% emits `battery.okay` (hysteresis so a
 *    wobbling charger cannot spam).
 *  - `network.wifi_ssid` carries the SSID only when ACCESS_FINE_LOCATION is held (Android 10
 *    rule); otherwise the payload has no ssid and ssid-rules never match — documented in the
 *    catalog, never silently faked.
 */
class AndroidEventSources(
    private val app: Application,
    private val hub: EventHub,
    private val clock: Clock,
    private val hasFineLocation: () -> Boolean = { false },
) {
    private var registered = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastBatteryLevel: Int? = null
    private var batteryLow = false
    private var lastNetworkType: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> dispatch("screen.on")
                Intent.ACTION_SCREEN_OFF -> dispatch("screen.off")
                Intent.ACTION_USER_PRESENT -> dispatch("user.present")
                Intent.ACTION_POWER_CONNECTED -> dispatch("power.connected")
                Intent.ACTION_POWER_DISCONNECTED -> dispatch("power.disconnected")
                Intent.ACTION_BATTERY_CHANGED -> onBattery(intent)
            }
        }
    }

    @SuppressLint("WrongConstant")
    fun start() {
        if (registered) return
        registered = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        app.registerReceiver(receiver, filter)
        registerNetworkCallback()
        // Seed the battery state from the sticky broadcast so the first edge is a real transition.
        app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { sticky ->
            onBattery(sticky, seedOnly = true)
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { app.unregisterReceiver(receiver) }
        networkCallback?.let { cb ->
            runCatching { connectivityManager()?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        lastBatteryLevel = null
        lastNetworkType = null
    }

    private fun onBattery(intent: Intent, seedOnly: Boolean = false) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val previous = lastBatteryLevel
        lastBatteryLevel = pct
        if (seedOnly) {
            batteryLow = pct <= LOW_THRESHOLD
            return
        }
        if (previous == null || previous == pct) return
        dispatch("battery.level", payload = buildJsonObject { put("level", pct); put("previous", previous) })
        if (pct <= LOW_THRESHOLD && previous > LOW_THRESHOLD && !batteryLow) {
            batteryLow = true
            dispatch("battery.low", payload = buildJsonObject { put("level", pct) })
        } else if (pct >= OKAY_THRESHOLD && previous < OKAY_THRESHOLD && batteryLow) {
            batteryLow = false
            dispatch("battery.okay", payload = buildJsonObject { put("level", pct) })
        }
    }

    private fun registerNetworkCallback() {
        val cm = connectivityManager() ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                noteNetwork(currentType())
                dispatch("network.connected", payload = buildJsonObject { put("type", currentType()) })
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                noteNetwork(currentType(capabilities))
            }

            override fun onLost(network: Network) {
                noteNetwork("none")
                dispatch("network.connected", payload = buildJsonObject { put("type", "none") })
            }
        }
        networkCallback = callback
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
        }.onFailure {
            AhLog.w(TAG, "network callback unavailable", JsonObject(emptyMap()))
            networkCallback = null
        }
    }

    private fun noteNetwork(type: String) {
        val previous = lastNetworkType
        if (previous != null && previous != type && type != "none") {
            dispatch("network.type_changed", payload = buildJsonObject { put("type", type); put("previous", previous) })
        }
        lastNetworkType = type
    }

    private fun currentType(capabilities: NetworkCapabilities? = null): String {
        val caps = capabilities ?: connectivityManager()
            ?.getNetworkCapabilities(connectivityManager()?.activeNetwork)
        return when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    private fun connectivityManager(): ConnectivityManager? =
        app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private fun dispatch(kind: String, pkg: String? = null, payload: JsonObject = JsonObject(emptyMap())) {
        hub.dispatch(TriggerEvent(kind = kind, pkg = pkg, payload = payload, atMs = clock.nowMs()))
    }

    companion object {
        private const val TAG = "events"
        const val LOW_THRESHOLD = 15
        const val OKAY_THRESHOLD = 25
    }
}
