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
        WhenKind("app_opened", "An app is opened", "app.entered", listOf(WhenParam("package", "App package", ParamKind.PACKAGE, hint = "com.android.chrome"))),
        WhenKind("app_closed", "An app is closed", "app.exited", listOf(WhenParam("package", "App package", ParamKind.PACKAGE, hint = "com.android.chrome"))),
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
}

data class Macro(
    val name: String,
    val whenId: String,
    val whenValues: Map<String, String>,
    val steps: List<MacroStep>,
)
