package app.autohost.core.broker

import app.autohost.core.common.AhJson
import app.autohost.core.model.BrokerEvent
import app.autohost.core.model.Envelope
import kotlinx.serialization.json.JsonElement

/**
 * Envelope marshalling for the Binder boundary. The wire format is exactly `schemas/broker.schema.json`:
 * `{"ok":true,"result":…,"meta":…}` or `{"ok":false,"error":{code,message,…}}`.
 */
object EnvelopeCodec {
    fun encode(envelope: Envelope): String = AhJson.json.encodeToString(Envelope.serializer(), envelope)

    fun decodeEnvelope(text: String?): Envelope? = try {
        AhJson.json.decodeFromString(Envelope.serializer(), text ?: return null)
    } catch (e: Exception) {
        null
    }

    fun decodeEvent(text: String?): BrokerEvent? = try {
        AhJson.json.decodeFromString(BrokerEvent.serializer(), text ?: return null)
    } catch (e: Exception) {
        null
    }

    fun decodeArgs(text: String?): JsonObject? = try {
        val element = AhJson.json.parseToJsonElement(text ?: return JsonObject(emptyMap()))
        element as? JsonObject
    } catch (e: Exception) {
        null
    }

    /** describe() payload for autohost.capabilities() introspection. */
    fun describe(capabilities: JsonObject): String = AhJson.stringify(capabilities)

    fun encodeElement(element: JsonElement): String = AhJson.stringify(element)
}
