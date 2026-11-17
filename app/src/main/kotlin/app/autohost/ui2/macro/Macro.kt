package app.autohost.ui2.macro

/**
 * Quick macros (UI v2): a small "when this happens, do these steps" model for people who do not want to write a task.
 *
 * Concept credit: OpenTasker (MIT, Copyright (c) 2026 SysAdminDoc) — its profile = context + task split and its compact
 * editor shaped this. No OpenTasker code is used: a macro compiles to AutoHost's own flow runtime and trigger rules
 * ([MacroCompiler]), so there is no second engine to keep in step with the permission broker.
 */
enum class ParamKind { TEXT, PACKAGE, NUMBER, TIME }

data class WhenParam(val key: String, val label: String, val kind: ParamKind, val required: Boolean = true, val hint: String = "")

/** One kind of trigger a macro can start from. [source] is a value of schemas/trigger-rule.schema.json `source`. */
data class WhenKind(val id: String, val label: String, val source: String, val params: List<WhenParam> = emptyList())

object WhenKinds {
    val all: List<WhenKind> = listOf(
        WhenKind("daily", "Every day at a time", "time.daily", listOf(WhenParam("time", "Time (24 h)", ParamKind.TIME, hint = "07:30"))),
        WhenKind("delay", "After a delay", "time.delay", listOf(WhenParam("seconds", "Seconds from now", ParamKind.NUMBER, hint = "60"))),
        WhenKind("charger_on", "Charger plugged in", "power.connected"),
        WhenKind("charger_off", "Charger unplugged", "power.disconnected"),
        WhenKind("battery_low", "Battery gets low", "battery.low"),
        WhenKind("unlocked", "Phone unlocked", "user.present"),
        WhenKind("screen_on", "Screen turns on", "screen.on"),
        WhenKind("app_opened", "An app is opened", "app.entered", listOf(WhenParam("package", "App", ParamKind.PACKAGE))),
        WhenKind("app_closed", "An app is closed", "app.exited", listOf(WhenParam("package", "App", ParamKind.PACKAGE))),
        WhenKind(
            "notification", "A notification arrives", "notification.posted",
            listOf(
                WhenParam("package", "From app (optional)", ParamKind.PACKAGE, required = false),
                WhenParam("text_regex", "Text contains (optional)", ParamKind.TEXT, required = false),
            ),
        ),
        WhenKind("wifi", "Joined a Wi-Fi network", "network.wifi_ssid", listOf(WhenParam("ssid", "Network name", ParamKind.TEXT))),
        WhenKind("webhook", "A webhook is called", "external", listOf(WhenParam("name", "Hook name", ParamKind.TEXT, hint = "doorbell"))),
    )

    fun byId(id: String): WhenKind? = all.firstOrNull { it.id == id }
}

/** The steps a macro can run. Each maps to one AutoHost flow command (docs/UI_V2.md §6). */
sealed interface MacroStep {
    val label: String

    data class Notify(val title: String, val text: String) : MacroStep { override val label get() = "Show notification" }
    data class OpenApp(val pkg: String) : MacroStep { override val label get() = "Open app" }
    data class Tap(val pkg: String, val text: String) : MacroStep { override val label get() = "Tap a button" }
    data class WaitFor(val pkg: String, val text: String, val seconds: Int) : MacroStep { override val label get() = "Wait for text" }
    data class Ask(val title: String, val message: String) : MacroStep { override val label get() = "Ask me to confirm" }
    data class Log(val message: String) : MacroStep { override val label get() = "Write to the run log" }

    /** Type [text] into the field labelled [field]; a blank [field] means the field you just tapped. */
    data class TypeText(val pkg: String, val field: String, val text: String) : MacroStep { override val label get() = "Type text" }

    /** Scroll the screen: [direction] is one of [MacroSteps.DIRECTIONS]. */
    data class Scroll(val pkg: String, val direction: String) : MacroStep { override val label get() = "Scroll" }

    /** Press a system key: [key] is one of [MacroSteps.KEYS]. */
    data class PressKey(val pkg: String, val key: String) : MacroStep { override val label get() = "Press a key" }

    /**
     * Start another task (a macro or a script) and wait up to [waitSeconds] for it. [inputs] are handed to it; its answer
     * is kept under [into] when that is not blank, and can be used in later steps as {into.code} or {into.data.name}.
     */
    data class RunTask(val task: String, val inputs: List<NamedValue>, val waitSeconds: Int, val into: String) : MacroStep {
        override val label get() = "Run another task"
    }

    /** End the macro with a result word and some named values, which a script (or another macro) can read. */
    data class Finish(val code: String, val values: List<NamedValue>) : MacroStep { override val label get() = "Finish with a result" }
}

/** A name and a value: a macro input with its default, a value handed to another task, or one value a macro finishes with. */
data class NamedValue(val name: String, val value: String = "")

/** Small pure helpers the editor and the compiler share. */
object MacroSteps {
    val DIRECTIONS = listOf("up", "down", "left", "right")
    val KEYS = listOf("back", "home", "enter")

    /** The steps the editor offers, in menu order, each with its starting values. */
    fun menu(): List<MacroStep> = listOf(
        MacroStep.OpenApp(""),
        MacroStep.WaitFor("", "", 10),
        MacroStep.Tap("", ""),
        MacroStep.TypeText("", "", ""),
        MacroStep.Scroll("", "down"),
        MacroStep.PressKey("", "back"),
        MacroStep.RunTask("", emptyList(), 60, ""),
        MacroStep.Finish("DONE", emptyList()),
        MacroStep.Notify("", ""),
        MacroStep.Ask("", ""),
        MacroStep.Log(""),
    )

    /** The app a step names, or null when the step does not name one. */
    fun packageOf(step: MacroStep): String? = when (step) {
        is MacroStep.OpenApp -> step.pkg
        is MacroStep.Tap -> step.pkg
        is MacroStep.WaitFor -> step.pkg
        is MacroStep.TypeText -> step.pkg
        is MacroStep.Scroll -> step.pkg
        is MacroStep.PressKey -> step.pkg
        is MacroStep.Notify, is MacroStep.Ask, is MacroStep.Log, is MacroStep.RunTask, is MacroStep.Finish -> null
    }

    /** [step] with its app set to [pkg]; steps that name no app come back unchanged. */
    fun withPackage(step: MacroStep, pkg: String): MacroStep = when (step) {
        is MacroStep.OpenApp -> step.copy(pkg = pkg)
        is MacroStep.Tap -> step.copy(pkg = pkg)
        is MacroStep.WaitFor -> step.copy(pkg = pkg)
        is MacroStep.TypeText -> step.copy(pkg = pkg)
        is MacroStep.Scroll -> step.copy(pkg = pkg)
        is MacroStep.PressKey -> step.copy(pkg = pkg)
        is MacroStep.Notify, is MacroStep.Ask, is MacroStep.Log, is MacroStep.RunTask, is MacroStep.Finish -> step
    }

    /** The app named by the latest step that names one, so the next step can start with it filled in. */
    fun lastPackage(steps: List<MacroStep>): String =
        steps.asReversed().firstNotNullOfOrNull { s -> packageOf(s)?.takeIf { it.isNotBlank() } }.orEmpty()
}

/** [inputs] are values something else can pass when it starts the macro (each with a default); steps use them as {name}. */
data class Macro(
    val name: String,
    val whenId: String,
    val whenValues: Map<String, String>,
    val steps: List<MacroStep>,
    val inputs: List<NamedValue> = emptyList(),
)
