package app.autohost.capabilities.data

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
import kotlinx.serialization.json.putJsonArray

/**
 * P10-07 (ADR-073): `location`, `camera`, `microphone`, `sensors` — the hardware cluster.
 * Same discipline as the personal-data cluster: seams for everything Android, pure caps in
 * [DataPolicy], lazy runtime permissions reported honestly (location's "allow all the time"
 * distinction included), and nothing runs invisibly:
 *
 *  - `location` serves foreground runs; a background-only run gets
 *    NEEDS_SETUP(BACKGROUND_LOCATION) until the user grants "Allow all the time" — never a
 *    silent fallback to a cached fix without saying so (`stale:true` marks last-known fixes).
 *  - `camera.capture_photo` opens the camera for exactly one shot (no preview, no stealth —
 *    the shutter is user-visible) into a run-scoped file.
 *  - `microphone.record` is duration-capped at [DataPolicy.MAX_RECORD_MS] with the recording
 *    indicator on (normal platform behaviour).
 *  - `sensors` needs no permission and reads one-shot samples only (no continuous streams —
 *    that is a trigger-rule job later, not a run resource).
 */
object HardwareSeams {

    data class LocFix(
        val latitude: Double,
        val longitude: Double,
        val accuracyM: Double,
        val altitudeM: Double?,
        val speedMps: Double?,
        val timeMs: Long,
        val provider: String,
    )

    interface LocationSource {
        fun lastKnown(): LocFix?
        fun current(timeoutMs: Long): LocFix?

        /** True when the app holds ACCESS_BACKGROUND_LOCATION (or the fix is foreground). */
        fun backgroundAllowed(): Boolean
    }

    data class Capture(val path: String, val width: Int, val height: Int, val sizeBytes: Long)

    interface Camera {
        fun capture(front: Boolean): Capture?
    }

    data class Recording(val path: String, val durationMs: Int, val sizeBytes: Long)

    interface Mic {
        fun record(durationMs: Int): Recording?
    }

    data class SensorInfo(val name: String, val vendor: String, val type: String)

    interface Sensors {
        fun list(): List<SensorInfo>

        /** Latest one-shot sample per requested name (empty map = sensor not present). */
        fun read(names: List<String>?): Map<String, DoubleArray>
    }
}

class LocationProvider(private val source: HardwareSeams.LocationSource) : LazyPermissionProvider("location") {

    override val capabilityId: String = "location"
    override val providerId: String = "location.source"
    override val tier: String = "T0"

    override fun permissionFor(method: String, args: JsonObject): String? = "location"

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        return when (method) {
        "last_known" -> {
            if (!source.backgroundAllowed()) {
                return Envelope.err(
                    ErrorCode.NEEDS_SETUP,
                    "last-known fixes serve background runs only after 'Allow all the time' is granted",
                    reason = "BACKGROUND_LOCATION",
                    fixAction = "REQUEST_PERMISSIONS",
                )
            }
            val fix = source.lastKnown()
                ?: return Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "no cached location fix (location services off or empty)", reason = "NO_LOCATION_FIX")
            Envelope.ok(fixJson(fix, stale = true))
        }
        "current" -> {
            val timeoutMs = ((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 15_000L).coerceIn(1_000, 60_000)
            val fix = source.current(timeoutMs)
                ?: return Envelope.err(ErrorCode.TIMEOUT, "no location fix within ${timeoutMs}ms (location services off?)")
            Envelope.ok(fixJson(fix, stale = false))
        }
            else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown location method '$method'")
        }
    }

    private fun fixJson(fix: HardwareSeams.LocFix, stale: Boolean) = buildJsonObject {
        put("latitude", fix.latitude)
        put("longitude", fix.longitude)
        put("accuracy_m", fix.accuracyM)
        if (fix.altitudeM != null) put("altitude_m", fix.altitudeM)
        if (fix.speedMps != null) put("speed_mps", fix.speedMps)
        put("time_ms", fix.timeMs)
        put("provider", fix.provider)
        put("stale", stale)
    }
}

class CameraProvider(private val camera: HardwareSeams.Camera) : LazyPermissionProvider("camera") {

    override val capabilityId: String = "camera"
    override val providerId: String = "camera.capture"
    override val tier: String = "T0"

    override fun permissionFor(method: String, args: JsonObject): String? = "camera"

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        return when (method) {
        "capture_photo" -> {
            val front = (args["front"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
            val capture = camera.capture(front)
                ?: return Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "the camera could not capture (permission revoked, camera busy, or no camera)", reason = "CAPTURE_FAILED")
            Envelope.ok(buildJsonObject {
                put("path", capture.path)
                put("width", capture.width)
                put("height", capture.height)
                put("size_bytes", capture.sizeBytes)
            })
        }
            else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown camera method '$method'")
        }
    }
}

class MicProvider(private val mic: HardwareSeams.Mic) : LazyPermissionProvider("microphone") {

    override val capabilityId: String = "microphone"
    override val providerId: String = "mic.record"
    override val tier: String = "T0"

    override fun permissionFor(method: String, args: JsonObject): String? = "microphone"

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        return when (method) {
        "record" -> {
            val durationMs = DataPolicy.recordMs((args["duration_ms"] as? JsonPrimitive)?.content?.toIntOrNull())
            val recording = mic.record(durationMs)
                ?: return Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "recording failed (permission revoked or the microphone is busy)", reason = "RECORD_FAILED")
            Envelope.ok(buildJsonObject {
                put("path", recording.path)
                put("duration_ms", recording.durationMs)
                put("size_bytes", recording.sizeBytes)
            })
        }
            else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown microphone method '$method'")
        }
    }
}

class SensorsProvider(private val sensors: HardwareSeams.Sensors) : CapabilityProvider {

    override val capabilityId: String = "sensors"
    override val providerId: String = "sensors.read"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability = Availability.Available // no runtime permission

    override fun permissionFor(method: String, args: JsonObject): String? = "sensors"

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "list" -> {
            val list = sensors.list()
            Envelope.ok(buildJsonObject {
                putJsonArray("sensors") {
                    list.forEach { sensor ->
                        add(buildJsonObject {
                            put("name", sensor.name)
                            put("vendor", sensor.vendor)
                            put("type", sensor.type)
                        })
                    }
                }
                put("count", list.size)
            })
        }
        "read" -> {
            val names = (args["names"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
            val samples = sensors.read(names)
            Envelope.ok(buildJsonObject {
                putJsonArray("samples") {
                    samples.forEach { (name, values) ->
                        add(buildJsonObject {
                            put("name", name)
                            putJsonArray("values") { values.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                        })
                    }
                }
            })
        }
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown sensors method '$method'")
    }
}
