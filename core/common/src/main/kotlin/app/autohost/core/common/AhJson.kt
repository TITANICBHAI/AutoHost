package app.autohost.core.common

import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one shared `Json` configuration (CONVENTIONS.md: kotlinx.serialization only). Wire contracts in
 * `schemas/broker.schema.json` tolerate unknown fields; nulls are omitted on the wire.
 */
object AhJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
        prettyPrint = false
    }

    fun parseObject(text: String): AhResult<JsonObject> {
        return try {
            val element = json.parseToJsonElement(text)
            val obj = element as? JsonObject
                ?: return AhResult.err(ErrorCode.INVALID_ARGS, "expected a JSON object")
            AhResult.ok(obj)
        } catch (e: Exception) {
            AhResult.err(ErrorCode.INVALID_ARGS, "malformed JSON: ${e.message}")
        }
    }

    /** Lenient decode for envelope/args arriving over Binder: blank means "empty object". */
    fun decodeObject(text: String?): JsonObject {
        if (text.isNullOrBlank()) return JsonObject(emptyMap())
        return try {
            json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
    }

    fun parseArray(text: String): AhResult<JsonArray> {
        return try {
            val element = json.parseToJsonElement(text)
            val array = element as? JsonArray
                ?: return AhResult.err(ErrorCode.INVALID_ARGS, "expected a JSON array")
            AhResult.ok(array)
        } catch (e: Exception) {
            AhResult.err(ErrorCode.INVALID_ARGS, "malformed JSON: ${e.message}")
        }
    }

    /** Human-stable compact rendering used in logs and dumps. */
    fun stringify(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), element)

    fun str(value: String?): JsonPrimitive = JsonPrimitive(value)

    fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*pairs))

    /** Structured log payload builder: `AhLog.i(TAG, "msg", AhJson.meta { put("k", "v") })`. */
    fun meta(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        kotlinx.serialization.json.buildJsonObject(block)
}
