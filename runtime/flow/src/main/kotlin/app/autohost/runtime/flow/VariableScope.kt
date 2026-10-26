package app.autohost.runtime.flow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Raised when ${secret.NAME} is resolved in an action position but the secret is undeclared. */
class SecretMissingException(val name: String) : Exception("secret '$name' is not declared for this profile")

/** Expression syntax/evaluation error (when.true, repeat.while). */
class ExprError(message: String) : Exception(message)

/**
 * P6-05: variable scope + `${...}` templating (mirrors autohost_ref.flows.Scope).
 *
 * Bare names resolve env -> config -> profile. Namespaced paths (config.x.y, profile.x, env.X,
 * run.attempt) walk nested objects. `${secret.NAME}` resolves only when [allowSecrets] is set —
 * the two action positions (inputText.text, setClipboard.text) — and never inside expressions;
 * a placeholder that resolves nowhere stays literal (fail closed, like scope templating).
 */
class FlowScope(
    val config: JsonObject,
    val profile: JsonObject,
    val env: MutableMap<String, JsonElement>,
    val run: JsonObject,
    val secrets: Map<String, String>,
) {
    /** Returns (found, value); raises [SecretMissingException] for an undeclared secret when allowed. */
    fun lookup(path: String, allowSecrets: Boolean = false): Pair<Boolean, JsonElement?> {
        val trimmed = path.trim()
        if (trimmed.startsWith("secret.")) {
            val name = trimmed.removePrefix("secret.")
            if (!allowSecrets) return false to null
            val value = secrets[name] ?: throw SecretMissingException(name)
            return true to JsonPrimitive(value)
        }
        val namespace = trimmed.substringBefore('.', "")
        val rest = trimmed.substringAfter('.', "")
        if (rest.isNotEmpty() && namespace in setOf("config", "profile", "env", "run")) {
            val table = when (namespace) {
                "config" -> config
                "profile" -> profile
                "env" -> JsonObject(env)
                else -> run
            }
            return walk(table, rest)
        }
        for (table in listOf(JsonObject(env), config, profile)) {
            if (trimmed in table) return true to table[trimmed]
        }
        return false to null
    }

    fun child(envOverlay: Map<String, JsonElement>): FlowScope {
        val merged = env.toMutableMap()
        merged.putAll(envOverlay)
        return FlowScope(config, profile, merged, run, secrets)
    }

    /** Replaces every secret value in [text] with [REDACTED] (same rule as the SDK runtime). */
    fun redact(text: String): String {
        var out = text
        for (value in secrets.values) {
            if (value.isNotEmpty()) out = out.replace(value, "[REDACTED]")
        }
        return out
    }

    /**
     * Resolves a template value: a whole-string single placeholder keeps the value's native
     * type; inside a longer string, booleans interpolate as true/false, null as empty.
     */
    fun resolveString(element: JsonElement, allowSecrets: Boolean = false): JsonElement {
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return element
        val matches = TEMPLATE_RE.findAll(text).toList()
        if (matches.isEmpty()) return element
        if (matches.size == 1 && matches[0].range == text.indices) {
            val (found, value) = lookup(matches[0].groupValues[1], allowSecrets = allowSecrets)
            return value ?: element
        }
        var out = text
        // Replace iteratively (Python's re.sub equivalent, left to right, no recursion).
        val builder = StringBuilder()
        var cursor = 0
        for (match in matches) {
            builder.append(text, cursor, match.range.first)
            val (found, value) = lookup(match.groupValues[1], allowSecrets = allowSecrets)
            if (found && value != null) builder.append(interpolate(value)) else builder.append(match.value)
            cursor = match.range.last + 1
        }
        builder.append(text, cursor, text.length)
        out = builder.toString()
        return JsonPrimitive(out)
    }

    /** Recursively resolves placeholders in strings; numbers/booleans/objects pass through. */
    fun resolveValue(element: JsonElement, allowSecrets: Boolean = false): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, v) -> resolveValue(v, allowSecrets) })
        is JsonArray -> JsonArray(element.map { resolveValue(it, allowSecrets) })
        is JsonPrimitive -> if (element.isString) resolveString(element, allowSecrets) else element
        JsonNull -> JsonNull
    }

    companion object {
        private val TEMPLATE_RE = Regex("\\$\\{([^{}]+)\\}")

        /** A scope with nothing in it (tests, guards, hosts that pre-resolve everything). */
        fun empty(): FlowScope = FlowScope(
            config = JsonObject(emptyMap()),
            profile = JsonObject(emptyMap()),
            env = LinkedHashMap(),
            run = JsonObject(emptyMap()),
            secrets = emptyMap(),
        )

        fun interpolate(value: JsonElement): String = when {
            value is JsonNull -> ""
            value is JsonPrimitive && !value.isString && value.content == "true" -> "true"
            value is JsonPrimitive && !value.isString && value.content == "false" -> "false"
            value is JsonPrimitive -> value.content
            else -> value.toString()
        }

        fun walk(table: JsonObject, path: String): Pair<Boolean, JsonElement?> {
            var current: JsonElement = table
            for (part in path.split('.')) {
                val obj = current as? JsonObject ?: return false to null
                current = obj[part] ?: return false to null
            }
            return true to current
        }
    }
}
