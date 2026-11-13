package app.autohost.ui2.macro

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A trigger rule ready to be stored (the same fields the Triggers editor writes). */
data class TriggerRuleSpec(val source: String, val params: JsonObject, val cooldownSeconds: Long, val debounceMs: Long, val busyPolicy: String)

data class CompiledMacro(val taskId: String, val folderName: String, val taskJson: String, val flowJson: String, val rule: TriggerRuleSpec)

sealed interface MacroResult {
    data class Ok(val compiled: CompiledMacro) : MacroResult
    data class Invalid(val problems: List<String>) : MacroResult
}

/** Turns a [Macro] into a dev-folder task (task.json + flow.json) and one trigger rule. Pure; tested against the real parsers. */
object MacroCompiler {

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    const val ID_PREFIX = "app.autohost.macro."

    fun slug(name: String): String {
        val s = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return if (s.isEmpty() || !s[0].isLetterOrDigit()) "macro" else s.take(40).trim('-')
    }

    fun validate(macro: Macro): List<String> {
        val problems = ArrayList<String>()
        if (macro.name.isBlank()) problems += "Give the macro a name."
        val kind = WhenKinds.byId(macro.whenId)
        if (kind == null) problems += "Pick when it should run." else problems += validateWhen(kind, macro.whenValues)
        if (macro.steps.isEmpty()) problems += "Add at least one step."
        macro.steps.forEachIndexed { i, step ->
            val n = "Step ${i + 1} (${step.label})"
            when (step) {
                is MacroStep.Notify -> if (step.title.isBlank()) problems += "$n needs a title."
                is MacroStep.OpenApp -> if (!PACKAGE.matches(step.pkg)) problems += "$n needs an app package like com.android.chrome."
                is MacroStep.Tap -> {
                    if (!PACKAGE.matches(step.pkg)) problems += "$n needs an app package."
                    if (step.text.isBlank()) problems += "$n needs the button text."
                }
                is MacroStep.WaitFor -> {
                    if (!PACKAGE.matches(step.pkg)) problems += "$n needs an app package."
                    if (step.text.isBlank()) problems += "$n needs the text to wait for."
                    if (step.seconds !in 1..120) problems += "$n: wait between 1 and 120 seconds."
                }
                is MacroStep.Ask -> if (step.title.isBlank() || step.message.isBlank()) problems += "$n needs a title and a message."
                is MacroStep.Log -> if (step.message.isBlank()) problems += "$n needs a message."
            }
        }
        return problems
    }

    private fun validateWhen(kind: WhenKind, values: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        for (p in kind.params) {
            val v = values[p.key]?.trim().orEmpty()
            if (v.isEmpty()) {
                if (p.required) out += "${p.label} is required."
                continue
            }
            when (p.kind) {
                ParamKind.TIME -> if (!TIME.matches(v)) out += "${p.label} must look like 07:30."
                ParamKind.NUMBER -> if (v.toIntOrNull()?.let { it >= 1 } != true) out += "${p.label} must be a whole number of 1 or more."
                ParamKind.PACKAGE -> if (!PACKAGE.matches(v)) out += "${p.label} must look like com.android.chrome."
                ParamKind.TEXT -> Unit
            }
        }
        return out
    }

    /** [nowMs] only makes the task id unique; the same macro compiled twice at different times gets two ids. */
    fun compile(macro: Macro, nowMs: Long): MacroResult {
        val problems = validate(macro)
        if (problems.isNotEmpty()) return MacroResult.Invalid(problems)
        val kind = WhenKinds.byId(macro.whenId)!!
        val folder = slug(macro.name) + "-" + nowMs.toString(36).takeLast(4)
        val taskId = ID_PREFIX + folder
        return MacroResult.Ok(
            CompiledMacro(
                taskId = taskId,
                folderName = "macro-$folder",
                taskJson = taskJson(taskId, macro).toString(),
                flowJson = flowJson(macro).toString(),
                rule = TriggerRuleSpec(kind.source, params(kind, macro.whenValues), cooldownSeconds = 5, debounceMs = 0, busyPolicy = "drop"),
            ),
        )
    }

    private fun params(kind: WhenKind, values: Map<String, String>): JsonObject = buildJsonObject {
        for (p in kind.params) {
            val v = values[p.key]?.trim().orEmpty()
            if (v.isEmpty()) continue
            if (p.kind == ParamKind.NUMBER) put(p.key, v.toInt()) else put(p.key, v)
        }
    }

    private fun flowJson(macro: Macro): JsonObject = buildJsonObject {
        put("schema", 1)
        put("name", macro.name.trim())
        putJsonArray("steps") { macro.steps.forEach { add(step(it)) } }
    }

    private fun selector(text: String): JsonArray = JsonArray(listOf(JsonPrimitive(text.trim())))

    private fun step(s: MacroStep): JsonElement = when (s) {
        is MacroStep.Notify -> buildJsonObject { putJsonObject("notify") { put("title", s.title.trim()); put("text", s.text.trim()) } }
        is MacroStep.OpenApp -> buildJsonObject { putJsonObject("launchApp") { put("package", s.pkg.trim()) } }
        is MacroStep.Tap -> buildJsonObject {
            putJsonObject("tapOn") { put("text", selector(s.text)); put("waitToSettle", true) }
        }
        is MacroStep.WaitFor -> buildJsonObject {
            putJsonObject("waitUntil") {
                putJsonObject("visible") { put("text", selector(s.text)) }
                put("timeout", s.seconds)
            }
        }
        is MacroStep.Ask -> buildJsonObject { putJsonObject("ask") { put("title", s.title.trim()); put("message", s.message.trim()); put("kind", "confirm") } }
        is MacroStep.Log -> buildJsonObject { putJsonObject("log") { put("message", s.message.trim()) } }
    }

    /** Capabilities the steps need, each scoped to exactly the packages the steps name (nothing broader). */
    private fun taskJson(taskId: String, macro: Macro): JsonObject {
        val launch = linkedSetOf<String>()
        val ui = linkedSetOf<String>()
        var notify = false
        var ask = false
        macro.steps.forEach {
            when (it) {
                is MacroStep.Notify -> notify = true
                is MacroStep.OpenApp -> launch += it.pkg.trim()
                is MacroStep.Tap -> ui += it.pkg.trim()
                is MacroStep.WaitFor -> ui += it.pkg.trim()
                is MacroStep.Ask -> ask = true
                is MacroStep.Log -> Unit
            }
        }
        fun packages(set: Set<String>): JsonObject = buildJsonObject { putJsonArray("packages") { set.forEach { add(JsonPrimitive(it)) } } }
        val usesUi = ui.isNotEmpty()
        return buildJsonObject {
            put("schema", 1)
            put("id", taskId)
            put("name", macro.name.trim())
            put("version", "1.0.0")
            put("description", "Quick macro")
            put("host_api", ">=1 <2")
            putJsonObject("runtime") { put("type", "flow"); put("entry", "flow.json") }
            putJsonObject("needs") {
                putJsonArray("capabilities") {
                    if (launch.isNotEmpty()) add(buildJsonObject { put("id", "device.app.launch"); put("scope", packages(launch)) })
                    if (usesUi) {
                        add(buildJsonObject { put("id", "device.ui.observe"); put("scope", packages(ui)) })
                        add(buildJsonObject { put("id", "device.ui.act"); put("scope", packages(ui)) })
                    }
                    if (notify) add(buildJsonObject { put("id", "notify.post"); put("scope", buildJsonObject { }) })
                    if (ask) add(buildJsonObject { put("id", "ui.prompt"); put("scope", buildJsonObject { }) })
                }
                putJsonArray("optional") { }
                putJsonArray("apps") { (launch + ui).forEach { add(buildJsonObject { put("package", it) }) } }
            }
            putJsonObject("triggers") {
                put("manual", true)
                putJsonObject("schedule") { put("allowed", false) }
                putJsonArray("events") { }
            }
            putJsonObject("execution") {
                put("ui_exclusive", usesUi)
                put("needs_unlocked_screen", usesUi || launch.isNotEmpty())
                put("pausable", true)
                put("timeout_minutes", 5)
                put("idle_timeout_seconds", 120)
                put("max_attempts", 1)
            }
            putJsonObject("ui") { put("mode", "auto") }
        }
    }
}
