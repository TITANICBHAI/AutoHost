package app.autohost.runtime.flow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON scalar inspection for the flow runtime. Flow documents arrive as parsed JSON; the
 * interpreter must distinguish ints from floats from booleans from quoted strings exactly like
 * the Python reference does (Python `int`/`float`/`bool` semantics drive templating and the
 * expression language), so the checks look at the literal kind, not just the content.
 */
internal object JsonValues {
    private val INT_RE = Regex("^-?\\d+$")

    fun primitive(element: JsonElement?): JsonPrimitive? = element as? JsonPrimitive

    fun isBool(element: JsonElement): Boolean =
        element is JsonPrimitive && !element.isString && element.content in setOf("true", "false")

    fun bool(element: JsonElement): Boolean? =
        if (isBool(element)) element.content == "true" else null

    fun isInt(element: JsonElement): Boolean =
        element is JsonPrimitive && !element.isString && INT_RE.matches(element.content)

    fun int(element: JsonElement): Int = (element as JsonPrimitive).content.toInt()

    fun long(element: JsonElement): Long = (element as JsonPrimitive).content.toLong()

    fun isNum(element: JsonElement): Boolean =
        element is JsonPrimitive && !element.isString &&
            (INT_RE.matches(element.content) || element.content.toDoubleOrNull() != null)

    fun double(element: JsonElement): Double = (element as JsonPrimitive).content.toDouble()

    fun isStr(element: JsonElement): Boolean = element is JsonPrimitive && element.isString

    fun str(element: JsonElement): String = (element as JsonPrimitive).content

    fun isScalar(element: JsonElement): Boolean =
        isStr(element) || isBool(element) || isNum(element)

    /** Copy with ${...}-free deep equality in mind: numbers keep their literal text. */
    fun deepCopy(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, v) -> deepCopy(v) })
        is JsonArray -> JsonArray(element.map { deepCopy(it) })
        JsonNull -> JsonNull
        else -> element
    }
}
