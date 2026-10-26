package app.autohost.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P4-02: misfire policy (plan §11.2) — what happens to fires that were missed (device asleep,
 * worker killed, alarm lost). JSON: `"skip"`, `"run_once"`, or `{"catch_up": n}`.
 * Default is [Skip]: honest and quiet — a missed fire is dropped, the schedule continues.
 */
sealed interface MisfirePolicy {
    data object Skip : MisfirePolicy
    data object RunOnce : MisfirePolicy
    data class CatchUp(val max: Int) : MisfirePolicy

    fun validate(): List<String> =
        if (this is CatchUp && max < 1) listOf("misfire catch_up must be >= 1") else emptyList()

    companion object {
        fun fromJsonElement(element: kotlinx.serialization.json.JsonElement?): MisfirePolicy = when {
            element == null -> Skip
            element is JsonPrimitive && element.isString -> when (element.content) {
                "skip" -> Skip
                "run_once" -> RunOnce
                else -> throw IllegalArgumentException("misfire must be skip|run_once|{catch_up}")
            }
            element is JsonObject -> {
                val n = (element["catch_up"] as? JsonPrimitive)?.content?.toIntOrNull()
                if (n == null || n < 1) {
                    throw IllegalArgumentException("misfire catch_up must be a positive integer")
                }
                CatchUp(n)
            }
            else -> throw IllegalArgumentException("misfire must be skip|run_once|{catch_up}")
        }
    }
}

/** Serialises the three documented JSON forms; refuses anything else (fail closed, P4-02). */
object MisfirePolicySerializer : KSerializer<MisfirePolicy> {
    override val descriptor: SerialDescriptor = String.serializer().descriptor

    override fun serialize(encoder: Encoder, value: MisfirePolicy) {
        val jsonEncoder = encoder as? JsonEncoder
        if (jsonEncoder == null) {
            // Non-JSON formats see the flat encoding: string names, int for catch-up.
            when (value) {
                is MisfirePolicy.CatchUp -> encoder.encodeInt(value.max)
                MisfirePolicy.RunOnce -> encoder.encodeString("run_once")
                MisfirePolicy.Skip -> encoder.encodeString("skip")
            }
            return
        }
        val element: kotlinx.serialization.json.JsonElement = when (value) {
            is MisfirePolicy.CatchUp -> buildJsonObject { put("catch_up", value.max) }
            MisfirePolicy.RunOnce -> JsonPrimitive("run_once")
            MisfirePolicy.Skip -> JsonPrimitive("skip")
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): MisfirePolicy {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return MisfirePolicy.CatchUp(decoder.decodeInt())
        return MisfirePolicy.fromJsonElement(jsonDecoder.decodeJsonElement())
    }
}
