package app.autohost.core.model.taskui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * P7-01 (plan §10.1): the Tier B declarative task-UI document (`ui/main.json`) — model, parser and
 * binding evaluator. Pure JVM: the Compose renderer (:app) renders whatever this parses, so parsing,
 * validation and binding semantics are unit-testable without Android.
 *
 * Document shape (frozen v1 set — a deviation from this list needs a recorded decision):
 *   {"version": 1, "title"?: str, "body": [widget...]}
 * Widgets: column, row, card, tabs, text, markdown, input, toggle, select, button, progress, table,
 * image, logview, stat, profile-picker.
 * Bindings: {{config.x}} {{profile.y}} {{run.status}} {{state.z}} {{input.NAME}} — one placeholder
 * covering a whole string keeps the native type (same rule as flow templating, ADR-051); anything
 * else interpolates into a string. Unresolved bindings render empty and are collected as warnings,
 * never failures.
 * Actions: run | run_stage(name) | call(handler, args?) | navigate(route) | confirm(title, message, action).
 * Input widgets write their current value into the `input` scope under their `name`.
 */
sealed class UiWidget {
    abstract val whenVisible: UiExpr?

    data class Column(val children: List<UiWidget>, override val whenVisible: UiExpr?) : UiWidget()
    data class Row(val children: List<UiWidget>, override val whenVisible: UiExpr?) : UiWidget()
    data class Card(val title: String?, val children: List<UiWidget>, override val whenVisible: UiExpr?) : UiWidget()
    data class Tabs(val items: List<TabItem>, override val whenVisible: UiExpr?) : UiWidget() {
        data class TabItem(val title: String, val children: List<UiWidget>)
    }

    data class Text(val value: String, val style: String, override val whenVisible: UiExpr?) : UiWidget()
    data class Markdown(val value: String, override val whenVisible: UiExpr?) : UiWidget()
    data class Input(val name: String, val label: String?, val default: String?, val secret: Boolean,
                     override val whenVisible: UiExpr?) : UiWidget()
    data class Toggle(val name: String, val label: String?, val default: Boolean,
                      override val whenVisible: UiExpr?) : UiWidget()
    data class Select(val name: String, val label: String?, val options: List<Option>,
                      val defaultIndex: Int, override val whenVisible: UiExpr?) : UiWidget() {
        data class Option(val value: String, val label: String)
    }

    data class Button(val label: String, val action: UiAction, val style: String,
                      override val whenVisible: UiExpr?) : UiWidget()
    data class Progress(val value: String?, override val whenVisible: UiExpr?) : UiWidget()
    data class Table(val columns: List<String>, val rows: List<List<String>>, val rowsBinding: String?,
                     override val whenVisible: UiExpr?) : UiWidget()
    data class Image(val path: String, override val whenVisible: UiExpr?) : UiWidget()
    data class LogView(val source: String, override val whenVisible: UiExpr?) : UiWidget()
    data class Stat(val label: String, val value: String, override val whenVisible: UiExpr?) : UiWidget()
    data class ProfilePicker(val name: String, override val whenVisible: UiExpr?) : UiWidget()
}

/** `when`-style conditional visibility: a binding path that must resolve to a truthy value. */
data class UiExpr(val raw: String)

sealed class UiAction {
    data class Run(val stage: String?) : UiAction()
    data class Call(val handler: String, val args: JsonObject) : UiAction()
    data class Navigate(val route: String) : UiAction()
    data class Confirm(val title: String, val message: String, val action: UiAction) : UiAction()
}

data class DeclarativeUi(val version: Int, val title: String?, val body: List<UiWidget>) {

    /** Parse errors carry the JSON path of the offending node — agents read these messages verbatim. */
    class UiDocError(val path: String, message: String) : Exception("$path: $message")

    companion object {
        const val MAX_DEPTH = 12
        const val MAX_WIDGETS = 200
        private val STYLES = setOf("headline", "body", "label")
        private val BUTTON_STYLES = setOf("filled", "outlined", "danger")
        private val ROUTES = setOf("overview", "config", "profiles", "history")
        private val LOG_SOURCES = setOf("console", "run")
        private val NAME_RE = Regex("[a-zA-Z][a-zA-Z0-9_]*")
        private val TABLE_BINDING = Regex("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_.]*)\\s*\\}\\}")

        fun parse(doc: JsonObject): DeclarativeUi {
            val version = doc["version"]?.jsonPrimitive?.intOrNull
                ?: throw UiDocError("$", "declarative UI needs an integer version (use 1)")
            if (version != 1) throw UiDocError("$.version", "unsupported ui document version $version (use 1)")
            val body = doc["body"] ?: throw UiDocError("$", "declarative UI needs a 'body' array")
            val widgets = parseChildren(body, "$.body", 0)
            if (widgets.isEmpty()) throw UiDocError("$.body", "declarative UI body is empty")
            return DeclarativeUi(
                version = version,
                title = (doc["title"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                body = widgets,
            )
        }

        private fun parseChildren(element: JsonElement, path: String, depth: Int): List<UiWidget> {
            if (depth > MAX_DEPTH) throw UiDocError(path, "nesting deeper than $MAX_DEPTH is not allowed")
            val array = element as? JsonArray ?: throw UiDocError(path, "expected an array of widgets")
            if (array.size > MAX_WIDGETS) throw UiDocError(path, "more than $MAX_WIDGETS widgets")
            return array.mapIndexed { i, item -> parseWidget(item, "$path[$i]", depth) }
        }

        private fun parseWidget(element: JsonElement, path: String, depth: Int): UiWidget {
            val obj = element as? JsonObject ?: throw UiDocError(path, "widget must be an object")
            val type = obj["type"]?.jsonPrimitive?.content
                ?: throw UiDocError("$path.type", "widget needs a 'type'")
            val whenExpr = (obj["when"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let(::UiExpr)
            fun child(name: String): List<UiWidget> =
                parseChildren(obj[name] ?: throw UiDocError("$path.$name", "widget '$type' needs '$name'"),
                    "$path.$name", depth + 1)
            fun str(name: String): String =
                (obj[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                    ?: throw UiDocError("$path.$name", "widget '$type' needs string '$name'")
            fun optStr(name: String): String? =
                (obj[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            fun boolOr(name: String, fallback: Boolean): Boolean =
                obj[name]?.jsonPrimitive?.booleanOrNull ?: fallback

            return when (type) {
                "column" -> UiWidget.Column(child("children"), whenExpr)
                "row" -> UiWidget.Row(child("children"), whenExpr)
                "card" -> UiWidget.Card(optStr("title"), child("children"), whenExpr)
                "tabs" -> {
                    val items = obj["items"] as? JsonArray
                        ?: throw UiDocError("$path.items", "tabs needs an 'items' array")
                    if (items.isEmpty()) throw UiDocError("$path.items", "tabs needs at least one tab")
                    UiWidget.Tabs(
                        items = items.mapIndexed { i, item ->
                            val t = item as? JsonObject
                                ?: throw UiDocError("$path.items[$i]", "tab must be an object")
                            UiWidget.Tabs.TabItem(
                                title = t["title"]?.jsonPrimitive?.content
                                    ?: throw UiDocError("$path.items[$i].title", "tab needs a title"),
                                children = parseChildren(
                                    t["children"] ?: throw UiDocError("$path.items[$i].children", "tab needs children"),
                                    "$path.items[$i].children", depth + 1),
                            )
                        },
                        whenVisible = whenExpr,
                    )
                }
                "text" -> UiWidget.Text(value = str("value"),
                    style = optStr("style")?.also { requireIn(it, STYLES, "$path.style") } ?: "body",
                    whenVisible = whenExpr)
                "markdown" -> UiWidget.Markdown(value = str("value"), whenVisible = whenExpr)
                "input" -> UiWidget.Input(
                    name = inputName(obj, path), label = optStr("label"), default = optStr("default"),
                    secret = boolOr("secret", false), whenVisible = whenExpr)
                "toggle" -> UiWidget.Toggle(
                    name = inputName(obj, path), label = optStr("label"),
                    default = boolOr("default", false), whenVisible = whenExpr)
                "select" -> {
                    val options = obj["options"] as? JsonArray
                        ?: throw UiDocError("$path.options", "select needs an 'options' array")
                    if (options.isEmpty()) throw UiDocError("$path.options", "select needs at least one option")
                    val parsed = options.mapIndexed { i, o ->
                        when (o) {
                            is JsonPrimitive -> UiWidget.Select.Option(value = o.content, label = o.content)
                            is JsonObject -> UiWidget.Select.Option(
                                value = o["value"]?.jsonPrimitive?.content
                                    ?: throw UiDocError("$path.options[$i].value", "option needs a value"),
                                label = o["label"]?.jsonPrimitive?.content
                                    ?: o["value"]?.jsonPrimitive?.content ?: "")
                            else -> throw UiDocError("$path.options[$i]", "option must be a string or an object")
                        }
                    }
                    val default = optStr("default")
                    UiWidget.Select(
                        name = inputName(obj, path), label = optStr("label"), options = parsed,
                        defaultIndex = parsed.indexOfFirst { it.value == default }.coerceAtLeast(0),
                        whenVisible = whenExpr,
                    )
                }
                "button" -> UiWidget.Button(
                    label = str("label"),
                    action = parseAction(
                        obj["action"] ?: throw UiDocError("$path.action", "button needs an action"),
                        "$path.action"),
                    style = optStr("style")?.also { requireIn(it, BUTTON_STYLES, "$path.style") } ?: "filled",
                    whenVisible = whenExpr,
                )
                "progress" -> UiWidget.Progress(value = optStr("value"), whenVisible = whenExpr)
                "table" -> {
                    val columns = obj["columns"] as? JsonArray
                        ?: throw UiDocError("$path.columns", "table needs a 'columns' array")
                    if (columns.isEmpty()) throw UiDocError("$path.columns", "table needs at least one column")
                    val rowsElement = obj["rows"]
                    val (rows, rowsBinding) = when (rowsElement) {
                        is JsonArray -> rowsElement.mapIndexed { r, row ->
                            val cells = row as? JsonArray
                                ?: throw UiDocError("$path.rows[$r]", "table row must be an array")
                            cells.map { c -> c.jsonPrimitive.content }
                        } to null
                        is JsonPrimitive -> {
                            val binding = TABLE_BINDING.matchEntire(rowsElement.content.trim())?.groupValues?.get(1)
                                ?: throw UiDocError("$path.rows", "table rows string must be a single binding expression")
                            emptyList<List<String>>() to binding
                        }
                        null -> emptyList<List<String>>() to null
                        else -> throw UiDocError("$path.rows", "table rows must be an array or a binding string")
                    }
                    UiWidget.Table(
                        columns = columns.map { it.jsonPrimitive.content }, rows = rows,
                        rowsBinding = rowsBinding, whenVisible = whenExpr,
                    )
                }
                "image" -> UiWidget.Image(path = str("path"), whenVisible = whenExpr)
                "logview" -> UiWidget.LogView(
                    source = optStr("source")?.also { requireIn(it, LOG_SOURCES, "$path.source") } ?: "console",
                    whenVisible = whenExpr)
                "stat" -> UiWidget.Stat(label = str("label"), value = str("value"), whenVisible = whenExpr)
                "profile-picker" -> UiWidget.ProfilePicker(name = inputName(obj, path), whenVisible = whenExpr)
                else -> throw UiDocError("$path.type", "unknown widget type '$type'")
            }
        }

        private fun parseAction(element: JsonElement, path: String): UiAction {
            val obj = element as? JsonObject ?: throw UiDocError(path, "action must be an object")
            return when (val type = obj["type"]?.jsonPrimitive?.content) {
                "run" -> UiAction.Run(stage = (obj["stage"] as? JsonPrimitive)?.content)
                "run_stage" -> UiAction.Run(stage = obj["name"]?.jsonPrimitive?.content
                    ?: throw UiDocError("$path.name", "run_stage needs a stage name"))
                "call" -> UiAction.Call(
                    handler = obj["handler"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        ?: throw UiDocError("$path.handler", "call needs a handler name"),
                    args = obj["args"] as? JsonObject ?: JsonObject(emptyMap()))
                "navigate" -> UiAction.Navigate(
                    route = obj["route"]?.jsonPrimitive?.content?.also { requireIn(it, ROUTES, "$path.route") }
                        ?: throw UiDocError("$path.route", "navigate needs a route (one of $ROUTES)"))
                "confirm" -> UiAction.Confirm(
                    title = obj["title"]?.jsonPrimitive?.content ?: "Confirm",
                    message = obj["message"]?.jsonPrimitive?.content ?: "",
                    action = parseAction(
                        obj["action"] ?: throw UiDocError("$path.action", "confirm needs an action"),
                        "$path.action"))
                else -> throw UiDocError("$path.type", "unknown action type '$type'")
            }
        }

        private fun inputName(obj: JsonObject, path: String): String {
            val name = obj["name"]?.jsonPrimitive?.content
            if (name.isNullOrBlank() || !name.matches(NAME_RE)) {
                throw UiDocError("$path.name", "input widgets need a name matching [a-zA-Z][a-zA-Z0-9_]*")
            }
            return name
        }

        private fun requireIn(value: String, allowed: Set<String>, path: String) {
            if (value !in allowed) throw UiDocError(path, "'$value' is not one of $allowed")
        }
    }
}

/**
 * Binding scope: config/profile/run/state/input maps addressed with dotted paths ({{config.target}}).
 * `run.` exposes only whitelisted keys (status/stage/reason/attempt) — the whole run record never
 * leaks into task UIs. Unresolved lookups return null; the evaluator decides (warn + empty).
 */
data class UiBindingScope(
    val config: JsonObject = JsonObject(emptyMap()),
    val profile: JsonObject = JsonObject(emptyMap()),
    val run: Map<String, String> = emptyMap(),
    val state: JsonObject = JsonObject(emptyMap()),
    val input: JsonObject = JsonObject(emptyMap()),
) {
    fun resolve(path: String): JsonElement? {
        val parts = path.split('.')
        val root: JsonElement
        val rest: List<String>
        when (parts.firstOrNull()) {
            "config" -> { root = config; rest = parts.drop(1) }
            "profile" -> { root = profile; rest = parts.drop(1) }
            "state" -> { root = state; rest = parts.drop(1) }
            "input" -> { root = input; rest = parts.drop(1) }
            "run" -> {
                val key = parts.getOrNull(1) ?: return null
                if (key !in RUN_KEYS) return null
                return run[key]?.let { JsonPrimitive(it) }
            }
            else -> return null
        }
        if (rest.isEmpty()) return null
        var current: JsonElement = root
        for (part in rest) {
            val obj = current as? JsonObject ?: return null
            current = obj[part] ?: return null
        }
        return current
    }

    companion object {
        val RUN_KEYS = setOf("status", "stage", "reason", "attempt")
    }
}

object UiBindings {

    private val TOKEN = Regex("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_.]*)\\s*\\}\\}")

    data class Bound(val value: JsonElement, val unresolved: List<String>)

    /** One placeholder covering the whole string keeps the native type; else interpolate into a string. */
    fun bind(template: String, scope: UiBindingScope): Bound {
        val whole = TOKEN.matchEntire(template.trim())?.groupValues?.get(1)
        if (whole != null) {
            val resolved = scope.resolve(whole)
            return if (resolved != null) Bound(resolved, emptyList())
            else Bound(JsonPrimitive(""), listOf(whole))
        }
        val warnings = mutableListOf<String>()
        val text = TOKEN.replace(template) { match ->
            val path = match.groupValues[1]
            val resolved = scope.resolve(path)
            if (resolved == null) {
                warnings.add(path)
                ""
            } else {
                when (val p = resolved) {
                    is JsonPrimitive -> p.content
                    else -> p.toString()
                }
            }
        }
        return Bound(JsonPrimitive(text), warnings)
    }

    /** `when` visibility: the path must resolve to a truthy value (non-empty string, true, non-zero). */
    fun isVisible(expr: UiExpr, scope: UiBindingScope): Boolean {
        val bound = bind("{{${expr.raw}}}", scope)
        return when (val v = bound.value) {
            is JsonPrimitive ->
                v.booleanOrNull ?: (v.content.isNotEmpty() && v.content != "false" && v.doubleOrNull != 0.0)
            else -> true
        }
    }
}
