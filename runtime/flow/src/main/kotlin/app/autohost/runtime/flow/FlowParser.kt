package app.autohost.runtime.flow

import app.autohost.core.model.FlowDocument
import app.autohost.core.model.FlowStep
import app.autohost.core.model.FlowWhen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The flow document is invalid. Message mirrors the Python reference (`autohost_ref.flows.FlowError`). */
class FlowParseException(message: String) : Exception(message)

/**
 * P6-01b: parses a flow document (JSON text; YAML support lands with a licensed parser — ADR-052)
 * into [FlowDocument]. Fail closed: every structural problem raises [FlowParseException] with the
 * same message the Python reference produces, so the parse_error golden cases are comparable.
 */
object FlowParser {
    val COMMANDS: Set<String> = setOf(
        "launchApp", "stopApp", "openLink", "tapOn", "longPressOn", "inputText", "eraseText",
        "pressKey", "scroll", "scrollUntilVisible", "swipe", "hideKeyboard", "waitFor", "waitUntil",
        "waitForAnimationToEnd", "assertVisible", "assertNotVisible", "copyTextFrom", "setClipboard",
        "takeScreenshot", "notify", "runFlow", "repeat", "retry", "log", "setResult", "nextRun",
        "ask", "defineVariables",
    )
    val SELECTOR_KEYS: Set<String> = setOf(
        "text", "id", "desc", "class", "package", "index", "enabled", "checked", "selected",
        "focused", "clickable", "visible", "bounds",
        "below", "above", "leftOf", "rightOf", "childOf", "containsChild", "containsDescendants",
    )
    private val LABEL_KEYS = listOf("text", "id", "desc", "class", "package")
    private val STATE_KEYS = listOf("enabled", "checked", "selected", "focused", "clickable", "visible")
    private val RELATION_KEYS = linkedMapOf(
        "below" to "below", "above" to "above", "leftOf" to "left_of", "rightOf" to "right_of",
        "childOf" to "child_of", "containsChild" to "contains_child",
    )
    private val MATCH_MODES = listOf("eq", "contains", "starts_with", "ends_with", "regex")
    private val MODIFIER_KEYS = setOf("optional", "label", "timeout", "when")
    val PRESS_KEYS: Set<String> = setOf(
        "back", "home", "recents", "enter", "notifications", "quick_settings", "lock", "power_dialog",
    )
    private val DIRECTIONS = setOf("up", "down", "left", "right")
    private val LOG_LEVELS = setOf("debug", "info", "warn", "error")
    private val ASK_KINDS = setOf("confirm", "text", "choice")
    private val TOP_LEVEL_KEYS = setOf("schema", "name", "tags", "env", "onFlowStart", "onFlowComplete", "steps")

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): FlowDocument {
        val element = try {
            lenient.parseToJsonElement(text)
        } catch (e: Exception) {
            throw FlowParseException("flow invalid: malformed JSON: ${e.message}")
        }
        if (element !is JsonObject) throw FlowParseException("flow invalid: document must be an object")
        return fromJson(element)
    }

    fun fromJson(doc: JsonObject): FlowDocument {
        val unknown = doc.keys - TOP_LEVEL_KEYS
        if (unknown.isNotEmpty()) die("unknown top-level key(s) ${renderSorted(unknown)}")
        val schema = doc["schema"]
        if (schema == null || JsonValues.isBool(schema) || !JsonValues.isInt(schema) || JsonValues.int(schema) != 1) {
            die("schema must be the number 1")
        }
        val name = when (val value = doc["name"]) {
            null, is JsonNull -> null
            else -> {
                if (!JsonValues.isStr(value) || JsonValues.str(value).isBlank()) die("name must be a non-empty string")
                JsonValues.str(value)
            }
        }
        val tags = when (val value = doc["tags"]) {
            null, is JsonNull -> emptyList()
            else -> {
                val arr = value as? JsonArray ?: die("tags must be a list of non-empty strings")
                arr.map { if (!JsonValues.isStr(it) || JsonValues.str(it).isEmpty()) die("tags must be a list of non-empty strings") else JsonValues.str(it) }
            }
        }
        val env = when (val value = doc["env"]) {
            null, is JsonNull -> JsonObject(emptyMap())
            else -> checkEnvMap(value, "env")
        }
        val start = parseStepsOrEmpty(doc, "onFlowStart")
        val complete = parseStepsOrEmpty(doc, "onFlowComplete")
        val stepsRaw = doc["steps"]
        if (stepsRaw !is JsonArray || stepsRaw.isEmpty()) die("steps must be a non-empty list")
        return FlowDocument(
            schema = 1, name = name, tags = tags, env = env,
            onFlowStart = start, onFlowComplete = complete,
            steps = parseSteps(stepsRaw, "steps"),
        )
    }

    private fun parseStepsOrEmpty(doc: JsonObject, key: String): List<FlowStep> {
        val value = doc[key] ?: return emptyList()
        if (value is JsonNull) return emptyList()
        return parseSteps(value as? JsonArray ?: die("$key must be a non-empty list"), key)
    }

    private fun checkEnvMap(raw: JsonElement, where: String): JsonObject {
        val obj = raw as? JsonObject ?: die("$where must be an object")
        for ((key, value) in obj) {
            if (key.isEmpty()) die("$where keys must be non-empty strings")
            if (!JsonValues.isScalar(value)) die("$where.$key must be a string, number or boolean")
        }
        return obj
    }

    internal fun parseSteps(raw: JsonArray, where: String): List<FlowStep> {
        if (raw.isEmpty()) die("$where must be a non-empty list")
        return raw.mapIndexed { i, item -> parseStep(item, "$where[$i]") }
    }

    internal fun parseStep(raw: JsonElement, where: String): FlowStep {
        if (raw !is JsonObject) die("$where: step must be an object")
        if (raw.size != 1) die("$where: a step must have exactly one command key, got ${renderSorted(raw.keys)}")
        val (command, bodyRaw) = raw.entries.first()
        if (command !in COMMANDS) die("$where: unknown command '$command'")
        if (bodyRaw !is JsonObject) die("$where: $command body must be an object")

        val optional = when (val v = bodyRaw["optional"]) {
            null -> false
            else -> JsonValues.bool(v) ?: die("$where: optional must be a boolean")
        }
        val label = when (val v = bodyRaw["label"]) {
            null, is JsonNull -> null
            else -> {
                if (!JsonValues.isStr(v) || JsonValues.str(v).isEmpty()) die("$where: label must be a non-empty string")
                JsonValues.str(v)
            }
        }
        val timeoutMs = when (val v = bodyRaw["timeout"]) {
            null, is JsonNull -> null
            else -> {
                if (JsonValues.isBool(v) || !JsonValues.isNum(v) || JsonValues.double(v) < 0) {
                    die("$where: timeout must be a non-negative number of seconds")
                }
                Math.round(JsonValues.double(v) * 1000.0)
            }
        }
        val whenCondition = when (val v = bodyRaw["when"]) {
            null, is JsonNull -> null
            else -> parseWhen(v, "$where: $command")
        }
        val extras = validate(command, bodyRaw, where)
        return FlowStep(
            command = command, body = bodyRaw, optional = optional, label = label,
            timeoutMs = timeoutMs, whenCondition = whenCondition,
            steps = extras.nestedSteps, times = extras.times,
            whileExpr = extras.whileExpr, backoffMs = extras.backoffMs,
        )
    }

    internal fun parseWhen(raw: JsonElement, where: String): FlowWhen {
        val obj = raw as? JsonObject ?: die("$where: when must be an object with at least one key")
        if (obj.isEmpty()) die("$where: when must be an object with at least one key")
        val unknown = obj.keys - setOf("visible", "notVisible", "true", "minApi")
        if (unknown.isNotEmpty()) die("$where: unknown when key(s) ${renderSorted(unknown)}")
        fun selector(key: String): JsonObject =
            toWireSelector(obj.getValue(key), "$where.$key")
        val trueExpr = when (val v = obj["true"]) {
            null -> null
            else -> {
                if (!JsonValues.isStr(v) || JsonValues.str(v).isBlank()) die("$where.true must be a non-empty expression")
                JsonValues.str(v)
            }
        }
        val minApi = when (val v = obj["minApi"]) {
            null -> null
            else -> {
                if (JsonValues.isBool(v) || !JsonValues.isInt(v) || JsonValues.int(v) < 1) {
                    die("$where.minApi must be a positive integer")
                }
                JsonValues.int(v)
            }
        }
        return FlowWhen(
            visible = if ("visible" in obj) selector("visible") else null,
            notVisible = if ("notVisible" in obj) selector("notVisible") else null,
            trueExpr = trueExpr, minApi = minApi,
        )
    }

    // --- per-command body validation (mirrors _VALIDATORS in autohost_ref/flows.py) ----------

    private class Extras(val nestedSteps: List<FlowStep> = emptyList(), val times: Int? = null,
                         val whileExpr: String? = null, val backoffMs: Long = 500L)

    private fun validate(command: String, body: JsonObject, where: String): Extras = when (command) {
        "launchApp" -> {
            requireStr(body, "package", where)
            if (body["clearState"] != null && JsonValues.bool(body.getValue("clearState")) == null) {
                die("$where: clearState must be a boolean")
            }
            reject(body, setOf("package", "clearState") + MODIFIER_KEYS, where)
            Extras()
        }
        "stopApp" -> {
            requireStr(body, "package", where)
            reject(body, setOf("package") + MODIFIER_KEYS, where)
            Extras()
        }
        "openLink" -> {
            requireStr(body, "url", where)
            if (body["package"] != null) requireStr(body, "package", where)
            reject(body, setOf("url", "package") + MODIFIER_KEYS, where)
            Extras()
        }
        "tapOn" -> {
            requireSelector(body, where)
            reject(body, SELECTOR_KEYS + setOf("waitToSettle", "retryTapIfNoChange") + MODIFIER_KEYS, where)
            Extras()
        }
        "longPressOn" -> {
            requireSelector(body, where)
            reject(body, SELECTOR_KEYS + setOf("waitToSettle") + MODIFIER_KEYS, where)
            Extras()
        }
        "inputText" -> {
            if ("into" !in body) {
                die("$where: inputText needs an `into` selector (the broker acts on explicit targets)")
            }
            toWireSelector(body.getValue("into"), "$where: into")
            requireStr(body, "text", where, allowEmpty = true)
            if (body["secret"] != null && JsonValues.bool(body.getValue("secret")) == null) {
                die("$where: secret must be a boolean")
            }
            reject(body, setOf("into", "text", "secret") + MODIFIER_KEYS, where)
            Extras()
        }
        "eraseText" -> {
            if ("into" !in body) die("$where: eraseText needs an `into` selector")
            toWireSelector(body.getValue("into"), "$where: into")
            reject(body, setOf("into") + MODIFIER_KEYS, where)
            Extras()
        }
        "pressKey" -> {
            requireStr(body, "key", where)
            val key = JsonValues.str(body.getValue("key"))
            if (key !in PRESS_KEYS) die("$where: key must be one of ${renderSorted(PRESS_KEYS)}")
            reject(body, setOf("key") + MODIFIER_KEYS, where)
            Extras()
        }
        "scroll" -> {
            val direction = (body["direction"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "down"
            if (direction !in DIRECTIONS) die("$where: direction must be one of ${renderSorted(DIRECTIONS)}")
            reject(body, setOf("direction") + MODIFIER_KEYS, where)
            Extras()
        }
        "swipe" -> {
            listOf("x1", "y1", "x2", "y2").forEach { requireInt(body, it, where, minimum = 0) }
            if (body["duration"] != null) requireInt(body, "duration", where, minimum = 0)
            reject(body, setOf("x1", "y1", "x2", "y2", "duration") + MODIFIER_KEYS, where)
            Extras()
        }
        "waitUntil" -> {
            if (("visible" in body) == ("notVisible" in body)) {
                die("$where: waitUntil needs exactly one of visible / notVisible")
            }
            for (key in listOf("visible", "notVisible")) {
                if (key in body) toWireSelector(body.getValue(key), "$where: $key")
            }
            reject(body, setOf("visible", "notVisible", "timeout") + MODIFIER_KEYS, where)
            Extras()
        }
        "waitForAnimationToEnd" -> {
            reject(body, setOf("timeout") + MODIFIER_KEYS, where)
            Extras()
        }
        "copyTextFrom" -> {
            requireSelector(body, where)
            requireStr(body, "into", where)
            reject(body, SELECTOR_KEYS + setOf("into") + MODIFIER_KEYS, where)
            Extras()
        }
        "setClipboard" -> {
            requireStr(body, "text", where, allowEmpty = true)
            if (body["secret"] != null && JsonValues.bool(body.getValue("secret")) == null) {
                die("$where: secret must be a boolean")
            }
            reject(body, setOf("text", "secret") + MODIFIER_KEYS, where)
            Extras()
        }
        "takeScreenshot" -> {
            if (body["into"] != null) requireStr(body, "into", where)
            reject(body, setOf("into") + MODIFIER_KEYS, where)
            Extras()
        }
        "notify" -> {
            requireStr(body, "title", where)
            requireStr(body, "text", where, allowEmpty = true)
            reject(body, setOf("title", "text") + MODIFIER_KEYS, where)
            Extras()
        }
        "ask" -> {
            requireStr(body, "title", where)
            requireStr(body, "message", where)
            val kind = (body["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "confirm"
            if (kind !in ASK_KINDS) die("$where: kind must be one of ${renderSorted(ASK_KINDS)}")
            if (kind == "choice") {
                val choices = body["choices"] as? JsonArray
                if (choices == null || choices.isEmpty() || choices.any { !JsonValues.isStr(it) || JsonValues.str(it).isEmpty() }) {
                    die("$where: kind 'choice' needs a non-empty choices list of strings")
                }
            } else if ("choices" in body) {
                die("$where: choices is only valid with kind 'choice'")
            }
            if (kind in setOf("text", "choice") && "into" !in body) {
                die("$where: kind '$kind' needs `into` (the variable that receives the answer)")
            }
            if (body["into"] != null) requireStr(body, "into", where)
            reject(body, setOf("title", "message", "kind", "choices", "into") + MODIFIER_KEYS, where)
            Extras()
        }
        "log" -> {
            requireStr(body, "message", where)
            val level = (body["level"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "info"
            if (level !in LOG_LEVELS) die("$where: level must be one of ${renderSorted(LOG_LEVELS)}")
            reject(body, setOf("message", "level") + MODIFIER_KEYS, where)
            Extras()
        }
        "setResult" -> {
            requireStr(body, "code", where)
            if (body["data"] != null && body["data"] !is JsonObject) die("$where: data must be an object")
            reject(body, setOf("code", "data") + MODIFIER_KEYS, where)
            Extras()
        }
        "nextRun" -> {
            requireInt(body, "afterMinutes", where, minimum = 1)
            reject(body, setOf("afterMinutes") + MODIFIER_KEYS, where)
            Extras()
        }
        "defineVariables" -> {
            checkEnvMap(body, "$where: defineVariables")
            val reserved = body.keys intersect MODIFIER_KEYS
            if (reserved.isNotEmpty()) {
                die("$where: variable name(s) ${renderSorted(reserved)} are reserved modifiers")
            }
            Extras()
        }
        "runFlow" -> {
            requireStr(body, "file", where)
            if (body["env"] != null) checkEnvMap(body.getValue("env"), "$where: runFlow env")
            reject(body, setOf("file", "env") + MODIFIER_KEYS, where)
            Extras()
        }
        "repeat" -> {
            if (("times" in body) == ("while" in body)) die("$where: repeat needs exactly one of times / while")
            var times: Int? = null
            var whileExpr: String? = null
            if ("times" in body) {
                requireInt(body, "times", where, minimum = 1)
                times = JsonValues.int(body.getValue("times"))
            } else {
                val expr = body.getValue("while")
                if (!JsonValues.isStr(expr) || JsonValues.str(expr).isBlank()) {
                    die("$where: while must be a non-empty expression")
                }
                whileExpr = JsonValues.str(expr)
            }
            val nested = body["steps"] as? JsonArray
            if (nested == null || nested.isEmpty()) die("$where: repeat needs a non-empty steps list")
            reject(body, setOf("times", "while", "steps") + MODIFIER_KEYS, where)
            Extras(nestedSteps = parseSteps(nested, "$where: repeat steps"), times = times, whileExpr = whileExpr)
        }
        "retry" -> {
            requireInt(body, "times", where, minimum = 0)
            var backoff = 500L
            if (body["backoff_ms"] != null) {
                requireInt(body, "backoff_ms", where, minimum = 0)
                backoff = JsonValues.int(body.getValue("backoff_ms")).toLong()
            }
            val nested = body["steps"] as? JsonArray
            if (nested == null || nested.isEmpty()) die("$where: retry needs a non-empty steps list")
            reject(body, setOf("times", "backoff_ms", "steps") + MODIFIER_KEYS, where)
            Extras(nestedSteps = parseSteps(nested, "$where: retry steps"), times = JsonValues.int(body.getValue("times")), backoffMs = backoff)
        }
        else -> { // selector-shaped commands: waitFor, assertVisible, assertNotVisible, scrollUntilVisible
            requireSelector(body, where)
            val extra = if (command == "scrollUntilVisible") setOf("direction") else emptySet()
            reject(body, SELECTOR_KEYS + extra + MODIFIER_KEYS, where)
            Extras()
        }
    }

    private fun requireStr(body: JsonObject, key: String, where: String, allowEmpty: Boolean = false) {
        val value = body[key]
        val ok = value != null && JsonValues.isStr(value) && (allowEmpty || JsonValues.str(value).isNotEmpty())
        if (!ok) {
            val got = if (value == null || value is JsonNull) "nothing" else
                if (JsonValues.isStr(value)) "'${JsonValues.str(value)}'" else value.toString()
            die("$where: $key must be a non-empty string, got $got")
        }
    }

    private fun requireInt(body: JsonObject, key: String, where: String, minimum: Int) {
        val value = body[key]
        if (value == null || JsonValues.isBool(value) || !JsonValues.isInt(value) || JsonValues.int(value) < minimum) {
            die("$where: $key must be an integer >= $minimum")
        }
    }

    private fun requireSelector(body: JsonObject, where: String) {
        if (SELECTOR_KEYS.none { it in body }) {
            die("$where: needs at least one selector key (${SELECTOR_KEYS.sorted().joinToString(", ")})")
        }
    }

    private fun reject(body: JsonObject, allowed: Set<String>, where: String) {
        val unknown = body.keys - allowed
        if (unknown.isNotEmpty()) die("$where: unknown key(s) ${renderSorted(unknown)}")
    }

    // --- selectors: flow form -> broker wire form (mirrors selector_from_flow) ---------------

    fun toWireSelector(raw: JsonElement, where: String = "selector"): JsonObject {
        val obj = raw as? JsonObject ?: die("$where: selector must be a non-empty object")
        if (obj.isEmpty()) die("$where: selector must be a non-empty object")
        val unknown = obj.keys - SELECTOR_KEYS
        if (unknown.isNotEmpty()) die("$where: unknown selector key(s) ${renderSorted(unknown)}")
        val base = mutableMapOf<String, JsonElement>()
        for (key in LABEL_KEYS) if (key in obj) base[key] = matchSpec(obj.getValue(key), key, where)
        for (key in STATE_KEYS) {
            if (key in obj) {
                val value = JsonValues.bool(obj.getValue(key))
                    ?: die("$where: $key must be a boolean")
                val state = base.getOrPut("state") { buildJsonObject { } } as? JsonObject ?: buildJsonObject { }
                base["state"] = buildJsonObject {
                    state.forEach { (k, v) -> put(k, v) }
                    put(key, value)
                }
            }
        }
        if ("bounds" in obj) {
            val bounds = obj.getValue("bounds") as? JsonObject
                ?: die("$where: bounds must be an object with at least one side")
            if (bounds.isEmpty()) die("$where: bounds must be an object with at least one side")
            val bad = bounds.keys - setOf("left", "top", "right", "bottom")
            if (bad.isNotEmpty()) die("$where: unknown bounds key(s) ${renderSorted(bad)}")
            base["bounds"] = buildJsonObject {
                for (key in listOf("left", "top", "right", "bottom")) {
                    if (key in bounds) {
                        val v = bounds.getValue(key)
                        if (JsonValues.isBool(v) || !JsonValues.isInt(v)) die("$where: bounds.$key must be an integer")
                        put(key, JsonValues.int(v))
                    }
                }
            }
        }
        for ((key, kind) in RELATION_KEYS) {
            if (key in obj) base[key] = toWireSelector(obj.getValue(key), "$where: $key")
        }
        if ("containsDescendants" in obj) {
            val nested = obj.getValue("containsDescendants") as? JsonArray
                ?: die("$where: containsDescendants must be a non-empty list of selectors")
            if (nested.isEmpty()) die("$where: containsDescendants must be a non-empty list of selectors")
            base["containsDescendants"] = buildJsonArray {
                nested.forEachIndexed { i, item -> add(toWireSelector(item, "$where: containsDescendants[$i]")) }
            }
        }
        val index = obj["index"]
        if (index != null) {
            if (JsonValues.isBool(index) || !JsonValues.isInt(index) || JsonValues.int(index) < 0) {
                die("$where: index must be a non-negative integer")
            }
            return buildJsonObject {
                put("op", "and")
                put("terms", buildJsonArray {
                    add(buildJsonObject {
                        put("relation", buildJsonObject {
                            put("kind", "index")
                            put("n", JsonValues.int(index))
                            put("selector", wireFromParts(base))
                        })
                    })
                })
            }
        }
        return wireFromParts(base)
    }

    private fun matchSpec(value: JsonElement, key: String, where: String): JsonObject {
        fun match(labels: List<String>, mode: String): JsonObject = buildJsonObject {
            put("labels", buildJsonArray { labels.forEach { add(JsonPrimitive(it)) } })
            put("mode", mode)
            put("case_sensitive", false)
        }
        if (value is JsonPrimitive && value.isString) {
            if (value.content.isEmpty()) die("$where: $key must not be empty")
            return match(listOf(value.content), "eq")
        }
        if (value is JsonArray) {
            if (value.isEmpty() || value.any { !JsonValues.isStr(it) || JsonValues.str(it).isEmpty() }) {
                die("$where: $key must be a non-empty string or list of non-empty strings")
            }
            return match(value.map { JsonValues.str(it) }, "eq")
        }
        if (value is JsonObject) {
            val bad = value.keys - setOf("value", "mode")
            val raw = value["value"]
            if (bad.isNotEmpty() || raw == null || !JsonValues.isStr(raw) || JsonValues.str(raw).isEmpty()) {
                die("$where: $key object form needs a non-empty `value` (and optional `mode`)")
            }
            val mode = (value["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "eq"
            if (mode !in MATCH_MODES) die("$where: $key mode must be one of $MATCH_MODES")
            return match(listOf(JsonValues.str(raw)), mode)
        }
        die("$where: $key must be a string, a list of strings or an object")
    }

    private fun wireFromParts(base: Map<String, JsonElement>): JsonObject {
        val terms = buildJsonArray {
            for (key in LABEL_KEYS) {
                if (key in base) {
                    val wireKey = if (key == "package") "pkg" else key
                    add(buildJsonObject { put(wireKey, base.getValue(key)) })
                }
            }
            if ("state" in base) add(buildJsonObject { put("state", base.getValue("state")) })
            if ("bounds" in base) add(buildJsonObject { put("bounds", base.getValue("bounds")) })
            for ((key, kind) in RELATION_KEYS) {
                if (key in base) {
                    add(buildJsonObject {
                        put("relation", buildJsonObject {
                            put("kind", kind)
                            put("selector", base.getValue(key))
                        })
                    })
                }
            }
            if ("containsDescendants" in base) {
                for (nested in base.getValue("containsDescendants") as JsonArray) {
                    add(buildJsonObject {
                        put("relation", buildJsonObject {
                            put("kind", "contains_descendants")
                            put("selector", nested)
                        })
                    })
                }
            }
        }
        return buildJsonObject {
            put("op", "and")
            put("terms", terms)
        }
    }

    private fun die(message: String): Nothing = throw FlowParseException("flow invalid: $message")

    /** Python-style sorted list repr used inside error messages: ['a', 'b']. */
    private fun renderSorted(keys: Set<String>): String =
        keys.sorted().joinToString(", ", "[", "]") { "'$it'" }
}
