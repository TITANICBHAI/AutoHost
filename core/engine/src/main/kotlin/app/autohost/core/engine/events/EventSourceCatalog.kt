package app.autohost.core.engine.events

/**
 * P8-02: the event-source catalog (plan §11.8 + §11.9) — one honest table of every event kind,
 * where it comes from on Android, which grant the *target task* needs for a rule on that source
 * to fire, and the params its rules accept. Drives `triggers.catalog`, the Triggers UI and the
 * grant gate in the controller; a kind missing here cannot be armed.
 */
object EventSourceCatalog {

    /** How the host observes this kind. */
    enum class Access {
        /** Dynamic broadcast receiver inside the low-priority EventHub FGS (API 26+ rule). */
        RECEIVER,

        /** ConnectivityManager network callback, hosted by the same FGS. */
        NETWORK_CALLBACK,

        /** ForegroundTracker transitions — needs the accessibility service enabled. */
        A11Y_FOREGROUND,

        /** A11y toast events (best effort per API level). */
        A11Y_TOAST,

        /** The NotificationListenerService (user must grant notification access). */
        NOTIFICATION_LISTENER,

        /** Host-internal: fired by the app layer (external intents, webhooks, task chains). */
        HOST,
    }

    /** The Android permission note shown in the catalog (documentation only — never auto-requested). */
    data class Source(
        val kind: String,
        val access: Access,
        /** The permission the target task must hold; null = no capability grant needed. */
        val requiredGrant: String?,
        val paramsDoc: Map<String, String>,
        val note: String = "",
    )

    val ALL: List<Source> = listOf(
        Source(
            "notification.posted", Access.NOTIFICATION_LISTENER, "device.notifications",
            mapOf(
                "package" to "exact package name (optional)",
                "title_regex" to "Java regex against the notification title (optional)",
                "text_regex" to "Java regex against the notification text (optional)",
            ),
            "Needs notification access; bursts are debounced by the rule's debounce_ms.",
        ),
        Source(
            "notification.removed", Access.NOTIFICATION_LISTENER, "device.notifications",
            mapOf("package" to "exact package name (optional)"),
        ),
        Source(
            "app.entered", Access.A11Y_FOREGROUND, "device.events",
            mapOf("package" to "exact package name"),
            "Package name only (plan §11.8 privacy).",
        ),
        Source(
            "app.exited", Access.A11Y_FOREGROUND, "device.events",
            mapOf("package" to "exact package name"),
        ),
        Source("battery.low", Access.RECEIVER, "device.events", emptyMap(), "Fires crossing to level <= 15%."),
        Source("battery.okay", Access.RECEIVER, "device.events", emptyMap(), "Fires recovering above 25% after low."),
        Source(
            "battery.level", Access.RECEIVER, "device.events",
            mapOf("op" to "lt|lte|gt|gte|eq", "percent" to "0..100"),
        ),
        Source("power.connected", Access.RECEIVER, "device.events", emptyMap()),
        Source("power.disconnected", Access.RECEIVER, "device.events", emptyMap()),
        Source("screen.on", Access.RECEIVER, "device.events", emptyMap()),
        Source("screen.off", Access.RECEIVER, "device.events", emptyMap()),
        Source("user.present", Access.RECEIVER, "device.events", emptyMap()),
        Source(
            "network.connected", Access.NETWORK_CALLBACK, "device.events",
            mapOf("type" to "wifi|cellular|ethernet|none (optional)"),
        ),
        Source(
            "network.type_changed", Access.NETWORK_CALLBACK, "device.events",
            mapOf("type" to "filter (optional)"),
        ),
        Source(
            "network.wifi_ssid", Access.NETWORK_CALLBACK, "device.events",
            mapOf("ssid" to "exact SSID"),
            "SSID is only present when the app holds fine location (Android 10 rule); " +
                "without it the event never matches — shown honestly in availability.",
        ),
        Source(
            "sms.received", Access.RECEIVER, "device.events",
            mapOf("sender_regex" to "regex (optional)", "body_regex" to "regex (optional)"),
            "W3 / full flavor only; the source is not wired in the core flavor.",
        ),
        Source(
            "toast.shown", Access.A11Y_TOAST, "device.events",
            mapOf("package" to "exact package (optional)", "text_regex" to "regex (optional)"),
            "Best effort; needs the accessibility service.",
        ),
        Source(
            "external", Access.HOST, null,
            mapOf("name" to "the trigger name the caller presents"),
            "Fired by the token-guarded RUN_TASK intent, the Dev API triggers.test and (Phase 8 part 2) webhooks.",
        ),
        Source(
            "task.chain", Access.HOST, null,
            mapOf("task" to "upstream task id", "on" to "succeeded|failed|any (default any)"),
            "Fired when an upstream run reaches a terminal status.",
        ),
    )

    fun byKind(kind: String): Source? = ALL.firstOrNull { it.kind == kind }

    /** Receiver/callback kinds — the EventHub FGS must exist while any enabled rule uses one. */
    val FGS_KINDS: Set<String> = ALL.filter {
        it.access == Access.RECEIVER || it.access == Access.NETWORK_CALLBACK
    }.map { it.kind }.toSet()

    /** Kinds served by long-lived system-bound services (no FGS needed for those). */
    val SERVICE_KINDS: Set<String> = ALL.filter {
        it.access == Access.A11Y_FOREGROUND || it.access == Access.A11Y_TOAST ||
            it.access == Access.NOTIFICATION_LISTENER
    }.map { it.kind }.toSet()
}
