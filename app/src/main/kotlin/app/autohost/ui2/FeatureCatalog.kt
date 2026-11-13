package app.autohost.ui2

/**
 * UI v2 (docs/UI_V2.md): what a person must DO on the phone before a capability works. One value per capability,
 * so every screen (Home checklist, task review, feature list) says the same thing and never "plug in a computer"
 * for something that does not need one. Pure data: no Android, no I/O.
 */
enum class Need {
    /** Works as soon as the app is installed. */
    READY,

    /** Android asks once, the first time a task uses it (a normal permission dialog). */
    ASK_ON_USE,

    /** One system switch on the phone: accessibility service, notification access, or choosing the AutoHost keyboard. */
    TOGGLE,

    /** The person picks a folder (system picker); the task only sees what was picked. */
    PICK,

    /** Off by default; a switch in Settings → Security turns it on (and the task must still be granted it). */
    SWITCH,

    /** Optional advanced access (Shizuku, root, ADB). The rest of the app works without it. */
    ADVANCED,
}

/** The groups the person sees. Titles are plain words; capability ids never appear on a normal screen. */
enum class FeatureGroup(val title: String, val blurb: String) {
    CONTROL_APPS("Control other apps", "Tap, type, scroll, open and read other apps."),
    TYPE_COPY("Type and copy", "Reliable text entry and the clipboard."),
    NOTIFICATIONS("Notifications", "Read, answer and show notifications."),
    PHONE("Phone state and sound", "Screen, battery, volume, sensors, flashlight."),
    PERSONAL("Your data", "Contacts, calendar, photos, location, camera, microphone."),
    MESSAGING("SMS and calls", "Read and send SMS, place calls (full build only)."),
    FILES("Files", "Private task storage and folders you pick."),
    MEMORY("Memory and secrets", "Settings, passwords and progress a task keeps."),
    INTERNET("Internet", "Web requests, sockets, webhooks, remote control."),
    WEB("Web pages", "Automate pages in a visible browser."),
    SEE("Read the screen", "Text, QR codes and images on the screen."),
    CHAIN("Chain and schedule", "Start other tasks, ask you something, adjust the next run."),
    SYSTEM("System settings", "Change phone settings."),
    ADVANCED("Advanced device control", "Shell, install apps, radios, display, logs, mock location."),
    RAW("Raw Android access", "Direct Android API calls from Python."),
}

/** One capability as the person experiences it. [capabilityId] matches `schemas/capability-specs.w1.json`. */
data class CapabilityNeed(val capabilityId: String, val group: FeatureGroup, val title: String, val need: Need)

object FeatureCatalog {

    val all: List<CapabilityNeed> = listOf(
        CapabilityNeed("device.ui", FeatureGroup.CONTROL_APPS, "See and tap other apps", Need.TOGGLE),
        CapabilityNeed("device.screenshot", FeatureGroup.CONTROL_APPS, "Take screenshots", Need.TOGGLE),
        CapabilityNeed("device.app", FeatureGroup.CONTROL_APPS, "Open, list and stop apps", Need.READY),
        CapabilityNeed("ui.overlay", FeatureGroup.CONTROL_APPS, "Draw highlights on screen", Need.TOGGLE),
        CapabilityNeed("browser.a11y", FeatureGroup.CONTROL_APPS, "Use your own browser app", Need.TOGGLE),
        CapabilityNeed("device.ime", FeatureGroup.TYPE_COPY, "Type text reliably", Need.TOGGLE),
        CapabilityNeed("device.clipboard", FeatureGroup.TYPE_COPY, "Clipboard", Need.READY),
        CapabilityNeed("device.notifications", FeatureGroup.NOTIFICATIONS, "Read and press notifications", Need.TOGGLE),
        CapabilityNeed("notify.post", FeatureGroup.NOTIFICATIONS, "Show notifications", Need.ASK_ON_USE),
        CapabilityNeed("device.screen", FeatureGroup.PHONE, "Screen on / unlocked state", Need.READY),
        CapabilityNeed("device.audio", FeatureGroup.PHONE, "Volume, speech, vibrate, flashlight", Need.READY),
        CapabilityNeed("device.power", FeatureGroup.PHONE, "Battery and keep-awake", Need.READY),
        CapabilityNeed("sensors", FeatureGroup.PHONE, "Sensors", Need.READY),
        CapabilityNeed("device.events", FeatureGroup.PHONE, "Recent phone events", Need.READY),
        CapabilityNeed("contacts", FeatureGroup.PERSONAL, "Contacts", Need.ASK_ON_USE),
        CapabilityNeed("calendar", FeatureGroup.PERSONAL, "Calendar", Need.ASK_ON_USE),
        CapabilityNeed("media", FeatureGroup.PERSONAL, "Photos, video, audio", Need.ASK_ON_USE),
        CapabilityNeed("location", FeatureGroup.PERSONAL, "Location", Need.ASK_ON_USE),
        CapabilityNeed("camera", FeatureGroup.PERSONAL, "Camera", Need.ASK_ON_USE),
        CapabilityNeed("microphone", FeatureGroup.PERSONAL, "Microphone", Need.ASK_ON_USE),
        CapabilityNeed("device.sms", FeatureGroup.MESSAGING, "SMS", Need.SWITCH),
        CapabilityNeed("device.telephony", FeatureGroup.MESSAGING, "Phone calls", Need.SWITCH),
        CapabilityNeed("files.private", FeatureGroup.FILES, "Task-private files", Need.READY),
        CapabilityNeed("files.shared", FeatureGroup.FILES, "Folders you pick", Need.PICK),
        CapabilityNeed("store", FeatureGroup.MEMORY, "Remember settings", Need.READY),
        CapabilityNeed("secrets", FeatureGroup.MEMORY, "Passwords and keys", Need.READY),
        CapabilityNeed("state", FeatureGroup.MEMORY, "Resume after a kill", Need.READY),
        CapabilityNeed("net.http", FeatureGroup.INTERNET, "Web requests", Need.READY),
        CapabilityNeed("net.ws", FeatureGroup.INTERNET, "WebSocket", Need.READY),
        CapabilityNeed("net.socket", FeatureGroup.INTERNET, "TCP / UDP", Need.READY),
        CapabilityNeed("net.server", FeatureGroup.INTERNET, "Webhooks into the phone", Need.READY),
        CapabilityNeed("net.reverse", FeatureGroup.INTERNET, "Phone dials out to a control host", Need.SWITCH),
        CapabilityNeed("browser.webview", FeatureGroup.WEB, "Visible in-app browser", Need.READY),
        CapabilityNeed("browser.cdp", FeatureGroup.WEB, "Chrome DevTools control", Need.ADVANCED),
        CapabilityNeed("vision", FeatureGroup.SEE, "Read text, QR codes, images", Need.TOGGLE),
        CapabilityNeed("tasks.run", FeatureGroup.CHAIN, "Start another task", Need.READY),
        CapabilityNeed("schedule.self", FeatureGroup.CHAIN, "Adjust the next run", Need.READY),
        CapabilityNeed("ui.prompt", FeatureGroup.CHAIN, "Ask you something", Need.READY),
        CapabilityNeed("device.settings", FeatureGroup.SYSTEM, "Change system settings", Need.ADVANCED),
        CapabilityNeed("device.shell", FeatureGroup.ADVANCED, "Run allowlisted shell commands", Need.ADVANCED),
        CapabilityNeed("device.app.manage", FeatureGroup.ADVANCED, "Install, remove, force-stop apps", Need.ADVANCED),
        CapabilityNeed("device.logcat", FeatureGroup.ADVANCED, "Read the system log", Need.ADVANCED),
        CapabilityNeed("device.radio", FeatureGroup.ADVANCED, "Wi-Fi, Bluetooth, airplane, data", Need.ADVANCED),
        CapabilityNeed("device.display", FeatureGroup.ADVANCED, "Dark mode, rotation, font size", Need.ADVANCED),
        CapabilityNeed("device.location.mock", FeatureGroup.ADVANCED, "Mock location", Need.ADVANCED),
        CapabilityNeed("android.api.direct", FeatureGroup.RAW, "Direct Android API from Python", Need.SWITCH),
    )

    private val byId: Map<String, CapabilityNeed> = all.associateBy { it.capabilityId }

    fun need(capabilityId: String): CapabilityNeed? = byId[capabilityId]

    /** Groups in display order, each with its capabilities, skipping empty ones. */
    fun groups(): List<Pair<FeatureGroup, List<CapabilityNeed>>> =
        FeatureGroup.entries.mapNotNull { g -> all.filter { it.group == g }.takeIf { it.isNotEmpty() }?.let { g to it } }

    /** How many capabilities need nothing beyond the install, per the table. Used by the Home summary line. */
    fun countWorkingWithoutSetup(): Int = all.count { it.need == Need.READY }
}
