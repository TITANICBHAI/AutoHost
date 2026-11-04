package app.autohost.core.broker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * P12-01 (ADR-089): tiny pure parser for the `returns` strings in `schemas/capability-specs.w1.json`,
 * e.g. `{method:'NODE_ACTION|ANCESTOR_ACTION|COORDINATE', verified:bool}`. The provider↔spec
 * conformance test uses it to prove a provider never returns a shape the frozen spec does not
 * promise (finding A6 was found by reading five files; this test finds the next one).
 *
 * Grammar (per key, comma-separated): `'A|B|C'` enum set · `bool` · `int` · `string` · `object` ·
 * `array` · a trailing `?` marks the key optional (absent-or-typed). The broker resource copy of
 * the spec is the input the tests read — never hand-edit either copy.
 */
object ReturnShape {

    enum class Kind { ENUM, BOOL, INT, STRING, OBJECT, ARRAY, ANY }

    data class FieldSpec(
        val name: String,
        val kind: Kind,
        val optional: Boolean,
        val enumValues: Set<String>,
    )

    private val KINDS = mapOf(
        "bool" to Kind.BOOL,
        "int" to Kind.INT,
        "string" to Kind.STRING,
        "object" to Kind.OBJECT,
        "array" to Kind.ARRAY,
    )

    fun parse(spec: String): Map<String, FieldSpec> {
        val trimmed = spec.trim()
        require(trimmed.startsWith("{") && trimmed.endsWith("}")) { "returns spec must be {k:type,...}: $spec" }
        val body = trimmed.substring(1, trimmed.length - 1)
        val fields = LinkedHashMap<String, FieldSpec>()
        if (body.isBlank()) return fields
        for (token in body.split(",")) {
            val part = token.trim()
            if (part.isEmpty()) continue
            val sep = part.indexOf(':')
            require(sep > 0) { "returns key needs a ':' separator: $part" }
            val name = part.substring(0, sep).trim()
            var type = part.substring(sep + 1).trim()
            val optional = type.endsWith("?")
            if (optional) type = type.dropLast(1)
            val field = when {
                type.startsWith("'") && type.endsWith("'") ->
                    FieldSpec(name, Kind.ENUM, optional, type.trim('\'').split("|").map { it.trim() }.toSet())
                type.startsWith("[") -> FieldSpec(name, Kind.ARRAY, optional, emptySet()) // [T]: element types unchecked
                else -> {
                    // Named types (UiSnapshot, NodeSummary, TextInputOutcome, ...) are wire-opaque
                    // here: they carry no inline shape, so conformance accepts any value for them.
                    val kind = KINDS[type] ?: Kind.ANY
                    FieldSpec(name, kind, optional, emptySet())
                }
            }
            fields[name] = field
        }
        return fields
    }

    /**
     * Validates a provider result against the declared shape. Returns the violations (empty = conformant).
     * `additiveAllowed` lists keys the spec does not declare yet but later phases add (P12-02's
     * `effect`/`warnings` ride this until the regeneration lands).
     */
    fun validate(
        result: JsonObject,
        shape: Map<String, FieldSpec>,
        additiveAllowed: Set<String> = emptySet(),
    ): List<String> {
        val violations = ArrayList<String>()
        for (key in result.keys) {
            if (key !in shape && key !in additiveAllowed) violations.add("unexpected key \"$key\"")
        }
        for ((name, field) in shape) {
            val value = result[name]
            if (value == null || value is JsonNull) {
                if (!field.optional) violations.add("missing key \"$name\"")
                continue
            }
            violations.addAll(validateValue(name, field, value))
        }
        return violations
    }

    private fun validateValue(name: String, field: FieldSpec, value: kotlinx.serialization.json.JsonElement): List<String> {
        val violations = ArrayList<String>()
        when (field.kind) {
            Kind.ENUM -> {
                val text = (value as? JsonPrimitive)?.content
                    ?: return listOf("key \"$name\" must be a string enum")
                if (text !in field.enumValues) {
                    violations.add("key \"$name\" value \"$text\" not in {${field.enumValues.joinToString("|")}}")
                }
            }
            Kind.BOOL -> if (value !is JsonPrimitive || value.content != "true" && value.content != "false") {
                violations.add("key \"$name\" must be a bool")
            }
            Kind.INT -> if (value !is JsonPrimitive || value.content.toIntOrNull() == null) {
                violations.add("key \"$name\" must be an int")
            }
            Kind.STRING -> if (value !is JsonPrimitive) violations.add("key \"$name\" must be a string")
            Kind.OBJECT -> if (value !is JsonObject) violations.add("key \"$name\" must be an object")
            Kind.ARRAY -> if (value !is JsonArray) violations.add("key \"$name\" must be an array")
            Kind.ANY -> Unit // named wire types (UiSnapshot, TextInputOutcome...): any value accepted
        }
        return violations
    }
}
