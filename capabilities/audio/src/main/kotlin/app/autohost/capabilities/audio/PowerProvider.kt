package app.autohost.capabilities.audio

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * P8-09: the `device.power` capability (plan §6.1 W2: "Task-scoped wake lock, battery state,
 * keep-awake"). The wake lock is task-scoped: the controller releases every run's lock on the
 * run's terminal transition (same collector that closes prompts/incidents), so a crashed worker
 * cannot hold the device awake.
 */
class PowerProvider(private val system: System) : CapabilityProvider {

    /** Power framework seam (AndroidPower in production). */
    interface System {
        fun battery(): Battery
        fun acquireWakeLock(runId: String, reason: String?): Boolean
        fun releaseWakeLock(runId: String): Boolean
        fun setKeepAwake(runId: String, on: Boolean): Boolean

        data class Battery(val level: Int, val charging: Boolean, val plugged: String, val health: String)
    }

    override val capabilityId: String = "device.power"
    override val providerId: String = "power.system"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method in METHODS) "device.power" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "battery" -> {
            val b = system.battery()
            Envelope.ok(buildJsonObject {
                put("level", b.level)
                put("charging", b.charging)
                put("plugged", b.plugged)
                put("health", b.health)
            })
        }
        "wake_lock" -> {
            val hold = (args["hold"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true
            val reason = (args["reason"] as? JsonPrimitive)?.content?.take(200)
            val held = if (hold) {
                system.acquireWakeLock(ctx.runId, reason ?: "task ${ctx.taskId}")
            } else {
                system.releaseWakeLock(ctx.runId)
            }
            Envelope.ok(buildJsonObject { put("held", held) })
        }
        "keep_awake" -> {
            val on = (args["on"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true
            Envelope.ok(buildJsonObject { put("on", system.setKeepAwake(ctx.runId, on)) })
        }
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.power method '$method'")
    }

    companion object {
        val METHODS = setOf("battery", "wake_lock", "keep_awake")

        /** P8-09: releases every lock for a finished run (called by the app's run collector). */
        fun releaseAll(system: System, runId: String) {
            system.releaseWakeLock(runId)
            system.setKeepAwake(runId, false)
        }
    }
}

/**
 * Android implementation: PowerManager wake locks tracked per run in [held] so a crashed
 * worker's lock is still releasable by id (the app collector calls [PowerProvider.releaseAll]).
 */
class AndroidPower(private val appContext: android.content.Context) : PowerProvider.System {

    private val held = ConcurrentHashMap<String, android.os.PowerManager.WakeLock>()
    private val keepAwake = ConcurrentHashMap<String, android.os.PowerManager.WakeLock>()

    override fun battery(): PowerProvider.System.Battery {
        val intent = appContext.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val health = intent?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, 0) ?: 0
        val pluggedName = when (plugged) {
            android.os.BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            android.os.BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "none"
        }
        val healthName = when (health) {
            android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            else -> "unknown"
        }
        return PowerProvider.System.Battery(
            level = if (scale > 0) level * 100 / scale else 0,
            charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL,
            plugged = pluggedName,
            health = healthName,
        )
    }

    override fun acquireWakeLock(runId: String, reason: String?): Boolean = try {
        val pm = appContext.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            ?: return false
        val tag = reason ?: "autohost run $runId"
        val lock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "autohost:run/$runId")
        lock.setReferenceCounted(false)
        // 30 min hard cap: a forgotten lock still expires (plan: task-scoped, never indefinite).
        lock.acquire(WAKE_LOCK_CAP_MS)
        held[runId] = lock
        app.autohost.core.common.AhLog.i("power", "wake lock acquired for $runId: $tag")
        true
    } catch (e: Exception) {
        app.autohost.core.common.AhLog.w("power", "wake lock failed: ${e.message}")
        false
    }

    override fun releaseWakeLock(runId: String): Boolean = try {
        held.remove(runId)?.release()
        true
    } catch (e: Exception) {
        false
    }

    override fun setKeepAwake(runId: String, on: Boolean): Boolean = try {
        val pm = appContext.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            ?: return false
        if (on) {
            val lock = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ON_AFTER_RELEASE,
                "autohost:keepawake/$runId",
            )
            lock.setReferenceCounted(false)
            lock.acquire(WAKE_LOCK_CAP_MS)
            keepAwake[runId] = lock
        } else {
            keepAwake.remove(runId)?.release()
        }
        true
    } catch (e: Exception) {
        false
    }

    companion object {
        const val WAKE_LOCK_CAP_MS = 30L * 60 * 1000
    }
}
