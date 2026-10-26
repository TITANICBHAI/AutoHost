package app.autohost.core.broker

import app.autohost.core.common.AhJson
import app.autohost.core.model.BrokerEvent
import app.autohost.core.model.Envelope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Envelope marshalling for the Binder boundary. The wire format is exactly `schemas/broker.schema.json`:
 * `{"ok":true,"result":…,"meta":…}` or `{"ok":false,"error":{code,message,…}}`.
 */
object EnvelopeCodec {
    fun encode(envelope: Envelope): String = AhJson.json.encodeToString(Envelope.serializer(), envelope)

    fun decodeEnvelope(text: String?): Envelope? {
        if (text == null) return null
        return try {
            AhJson.json.decodeFromString(Envelope.serializer(), text)
        } catch (e: Exception) {
            null
        }
    }

    fun decodeEvent(text: String?): BrokerEvent? {
        if (text == null) return null
        return try {
            AhJson.json.decodeFromString(BrokerEvent.serializer(), text)
        } catch (e: Exception) {
            null
        }
    }

    fun decodeArgs(text: String?): JsonObject? {
        if (text == null) return JsonObject(emptyMap())
        return try {
            val element = AhJson.json.parseToJsonElement(text)
            element as? JsonObject
        } catch (e: Exception) {
            null
        }
    }

    /** describe() payload for autohost.capabilities() introspection. */
    fun describe(capabilities: JsonObject): String = AhJson.stringify(capabilities)

    fun encodeElement(element: JsonElement): String = AhJson.stringify(element)
}
