package app.autohost.core.engine.host

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.engine.events.EventHub
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P8-02: the `device.events` capability — read-side access to the EventHub ring buffer
 * (plan §11.8). Live delivery to scripts is the trigger rules' job (§11.9); a task that wants to
 * *react* declares an event trigger, while `recent` lets a running task inspect what happened
 * (e.g. "was the screen turned off during my last stage?"). The buffer keeps the last 200 events,
 * privacy-scoped at the source, so this stays an honest read-only window.
 */
class EventsProvider(private val hub: () -> EventHub?) : CapabilityProvider {

    override val capabilityId: String = "device.events"
    override val providerId: String = "host.events"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method == "recent") "device.events" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        if (method != "recent") return Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.events method '$method'")
        val theHub = hub() ?: return Envelope.ok(buildJsonObject {
            put("events", JsonArray(emptyList()))
            put("note", "event hub not wired")
        })
        val kinds = (args["kinds"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.toSet()
        val limit = (args["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 50
        val events = theHub.recent(kinds = kinds, limit = limit)
        return Envelope.ok(buildJsonObject {
            put("events", JsonArray(events.map { event ->
                buildJsonObject {
                    put("kind", event.kind)
                    event.pkg?.let { put("package", it) }
                    put("payload", event.payload)
                    put("at_ms", event.atMs)
                }
            }))
        })
    }
}
