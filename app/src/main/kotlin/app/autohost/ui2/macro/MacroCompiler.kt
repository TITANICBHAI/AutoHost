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

    private val NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val TASK_ID = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9-]*)+$")

    /** The flow runtime reads these as the start of a path (env.x, run.args, secret.x), so they cannot be a macro's own names. */
    private val RESERVED = setOf("env", "config", "profile", "run", "secret")

    /** The longest a task started by a macro may be waited for: the host never waits longer than ten minutes. */
    const val MAX_WAIT_SECONDS = 600

    /** The task manifest allows 1 to 1440 minutes (schemas/task.schema.json). */
    private const val MAX_TIMEOUT_MINUTES = 1440

    /** {name} or {name.more.parts}. A `$` right before it means the text is already a flow placeholder, which is left alone. */
    private val PLACEHOLDER = Regex("(?<![\$])\\{([A-Za-z_][A-Za-z0-9_]*)((?:\\.[A-Za-z0-9_]+)*)\\}")

    /** Turns {name} into the flow's own placeholder for names in [known]; every other piece of text stays as typed. */
    internal fun expand(text: String, known: Set<String>): String = PLACEHOLDER.replace(text) { m ->
        val root = m.groupValues[1]
        val rest = m.groupValues[2]
        when {
            root !in known -> m.value
            rest.isEmpty() -> "\${" + root + "}"
            else -> "\${env." + root + rest + "}"
        }
    }

    /** The {names} used in [text] that are not in [known], each once. */
    internal fun unknownNames(text: String, known: Set<String>): List<String> =
        PLACEHOLDER.findAll(text).map { it.groupValues[1] }.filter { it !in known }.distinct().toList()

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
        val known = LinkedHashSet<String>()
        macro.inputs.forEachIndexed { i, input ->
            val name = input.name.trim()
            val n = "Input ${i + 1}"
            if (checkName(n, "it", name, problems) && !known.add(name)) problems += "$n repeats the name $name."
        }
        macro.steps.forEachIndexed { i, step ->
            val n = "Step ${i + 1} (${step.label})"
            when (step) {
                is MacroStep.Notify -> {
                    if (step.title.isBlank()) problems += "$n needs a title."
                    checkText(n, step.title + " " + step.text, known, problems)
                }
                is MacroStep.OpenApp -> if (!PACKAGE.matches(step.pkg)) problems += "$n needs an app: choose one from the list."
                is MacroStep.Tap -> {
                    if (!PACKAGE.matches(step.pkg)) problems += "$n needs an app: choose one from the list."
                    if (step.text.isBlank()) problems += "$n needs the button text."
                }
                is MacroStep.WaitFor -> {
                    if (!PACKAGE.matches(step.pkg)) problems += "$n needs an app: choose one from the list."
                    if (step.text.isBlank()) problems += "$n needs the text to wait for."
                    if (step.seconds !in 1..120) problems += "$n: wait between 1 and 120 seconds."
                }
                is MacroStep.Ask -> {
                    if (step.title.isBlank() || step.message.isBlank()) problems += "$n needs a title and a message."
                    checkText(n, step.title + " " + step.message, known, problems)
                }
                is MacroStep.Log -> {
                    if (step.message.isBlank()) problems += "$n needs a message."
                    checkText(n, step.message, known, problems)
                }
                is MacroStep.TypeText -> {
                    if (!PACKAGE.matches(step.pkg)) problems += "$n needs an app: choose one from the list."
                    if (step.text.isEmpty()) problems += "$n needs the text to type."
                    checkText(n, step.text, known, problems)
                }
                is MacroStep.Scroll -> {
                    if (!PACKAGE.matches(step.pkg)) problems += "$n needs an app: choose one from the list."
                    if (step.direction !in MacroSteps.DIRECTIONS) problems += "$n needs a direction."
                }
                is MacroStep.PressKey -> {
                    if (!PACKAGE.matches(step.pkg)) problems += "$n needs an app: choose one from the list."
                    if (step.key !in MacroSteps.KEYS) problems += "$n needs a key."
                }
                is MacroStep.RunTask -> {
                    if (!TASK_ID.matches(step.task.trim())) problems += "$n needs the task to run."
                    if (step.waitSeconds !in 1..MAX_WAIT_SECONDS) problems += "$n: wait between 1 and $MAX_WAIT_SECONDS seconds."
                    checkNamed(n, "an input", step.inputs, known, problems)
                    val into = step.into.trim()
                    if (into.isNotEmpty() && checkName(n, "the saved result", into, problems)) {
                        if (into in known) {
                            problems += "$n saves its result as $into, which is already used."
                        } else {
                            known += into
                        }
                    }
                }
                is MacroStep.Finish -> {
                    if (i != macro.steps.lastIndex) problems += "$n must be the last step."
                    if (step.code.isBlank()) problems += "$n needs a result word, for example DONE."
                    checkText(n, step.code, known, problems)
                    checkNamed(n, "a value", step.values, known, problems)
                }
            }
        }
        return problems
    }

    private fun checkName(n: String, what: String, name: String, problems: MutableList<String>): Boolean {
        if (!NAME.matches(name)) {
            problems += "$n: $what needs a name of letters, numbers and _ that starts with a letter."
            return false
        }
        if (name in RESERVED) {
            problems += "$n: $what needs another name, because $name is used by AutoHost itself."
            return false
        }
        return true
    }

    private fun checkText(n: String, text: String, known: Set<String>, problems: MutableList<String>) {
        val unknown = unknownNames(text, known)
        if (unknown.isEmpty()) return
        val list = unknown.joinToString(", ") { "{" + it + "}" }
        problems += "$n uses $list, which is not an input or a saved value at that point."
    }

    private fun checkNamed(n: String, what: String, items: List<NamedValue>, known: Set<String>, problems: MutableList<String>) {
        val seen = HashSet<String>()
        items.forEach { item ->
            val name = item.name.trim()
            if (checkName(n, what, name, problems) && !seen.add(name)) problems += "$n gives $what named $name twice."
            checkText(n, item.value, known, problems)
        }
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
                ParamKind.PACKAGE -> if (!PACKAGE.matches(v)) out += "${p.label}: choose an app from the list."
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

    /** The inputs go into the flow's own `env` block: that block is what a script or another macro is allowed to override. */
    private fun flowJson(macro: Macro): JsonObject = buildJsonObject {
        put("schema", 1)
        put("name", macro.name.trim())
        if (macro.inputs.isNotEmpty()) {
            putJsonObject("env") { macro.inputs.forEach { put(it.name.trim(), it.value) } }
        }
        val known = LinkedHashSet<String>()
        macro.inputs.forEach { known += it.name.trim() }
        putJsonArray("steps") {
            macro.steps.forEach { s ->
                add(step(s, known))
                if (s is MacroStep.RunTask && s.into.isNotBlank()) known += s.into.trim()
            }
        }
    }

    private fun selector(text: String): JsonArray = JsonArray(listOf(JsonPrimitive(text.trim())))

    private fun step(s: MacroStep, known: Set<String>): JsonElement = when (s) {
        is MacroStep.Notify -> buildJsonObject {
            putJsonObject("notify") { put("title", expand(s.title.trim(), known)); put("text", expand(s.text.trim(), known)) }
        }
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
        is MacroStep.Ask -> buildJsonObject {
            putJsonObject("ask") {
                put("title", expand(s.title.trim(), known))
                put("message", expand(s.message.trim(), known))
                put("kind", "confirm")
            }
        }
        is MacroStep.Log -> buildJsonObject { putJsonObject("log") { put("message", expand(s.message.trim(), known)) } }
        // A blank field means "the field you just tapped": the focused editable one. A named field is found by its label.
        is MacroStep.TypeText -> buildJsonObject {
            putJsonObject("inputText") {
                putJsonObject("into") {
                    if (s.field.isBlank()) {
                        put("class", "android.widget.EditText")
                        put("focused", true)
                    } else {
                        putJsonObject("text") { put("value", s.field.trim()); put("mode", "contains") }
                    }
                }
                put("text", expand(s.text, known))
            }
        }
        is MacroStep.Scroll -> buildJsonObject { putJsonObject("scroll") { put("direction", s.direction) } }
        is MacroStep.PressKey -> buildJsonObject { putJsonObject("pressKey") { put("key", s.key) } }
        is MacroStep.RunTask -> buildJsonObject {
            putJsonObject("runTask") {
                put("task", s.task.trim())
                if (s.inputs.isNotEmpty()) {
                    putJsonObject("args") { s.inputs.forEach { put(it.name.trim(), expand(it.value, known)) } }
                }
                if (s.into.isNotBlank()) put("into", s.into.trim())
                put("timeout", s.waitSeconds)
            }
        }
        // setResult only records the answer; validate() makes it the last step so "finish" means what it says.
        is MacroStep.Finish -> buildJsonObject {
            putJsonObject("setResult") {
                put("code", expand(s.code.trim(), known))
                if (s.values.isNotEmpty()) {
                    putJsonObject("data") { s.values.forEach { put(it.name.trim(), expand(it.value, known)) } }
                }
            }
        }
    }

    /** Capabilities the steps need, each scoped to exactly the packages the steps name (nothing broader). */
    private fun taskJson(taskId: String, macro: Macro): JsonObject {
        val launch = linkedSetOf<String>()
        val ui = linkedSetOf<String>()
        val tasks = linkedSetOf<String>()
        var notify = false
        var ask = false
        var waitTotal = 0
        macro.steps.forEach {
            when (it) {
                is MacroStep.Notify -> notify = true
                is MacroStep.OpenApp -> launch += it.pkg.trim()
                is MacroStep.Tap -> ui += it.pkg.trim()
                is MacroStep.WaitFor -> ui += it.pkg.trim()
                is MacroStep.Ask -> ask = true
                is MacroStep.Log -> Unit
                is MacroStep.TypeText -> ui += it.pkg.trim()
                is MacroStep.Scroll -> ui += it.pkg.trim()
                is MacroStep.PressKey -> ui += it.pkg.trim()
                is MacroStep.RunTask -> {
                    tasks += it.task.trim()
                    waitTotal += it.waitSeconds
                }
                is MacroStep.Finish -> Unit
            }
        }
        fun packages(set: Set<String>): JsonObject = buildJsonObject { putJsonArray("packages") { set.forEach { add(JsonPrimitive(it)) } } }
        val usesUi = ui.isNotEmpty()
        // A runTask step waits inside one broker call and the idle watchdog counts calls, so the limits grow by the waits.
        val timeoutMinutes = (5 + (waitTotal + 59) / 60).coerceAtMost(MAX_TIMEOUT_MINUTES)
        val idleSeconds = 120 + waitTotal
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
                    if (tasks.isNotEmpty()) {
                        add(
                            buildJsonObject {
                                put("id", "tasks.run")
                                put("scope", buildJsonObject { putJsonArray("tasks") { tasks.forEach { add(JsonPrimitive(it)) } } })
                            },
                        )
                    }
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
                put("timeout_minutes", timeoutMinutes)
                put("idle_timeout_seconds", idleSeconds)
                put("max_attempts", 1)
            }
            putJsonObject("ui") { put("mode", "auto") }
        }
    }
}
