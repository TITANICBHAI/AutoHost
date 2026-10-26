package app.autohost.capabilities.notifications

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.common.Clock
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * P8-01: the `device.notifications` capability (plan §6.1 wave 2) — list / read / press /
 * dismiss / wait_for over the NotificationListener bridge. Availability is honest: the listener
 * must be enabled by the user (`NEEDS_SETUP` + the notification-access fix otherwise).
 *
 * Privacy scoping (plan §11.8): when the run's grant restricts `device.notifications` to a
 * package set, ONLY those packages are visible; unrestricted grants see every notification the
 * listener holds. Payloads carry title/text only — never extras, never content intents.
 */
class NotificationsProvider(
    private val clock: Clock,
    private val access: Access = AndroidAccess,
) : CapabilityProvider {

    /** Whether the user enabled the listener; injected so tests run on the JVM. */
    fun interface Access {
        fun listenerEnabled(): Boolean
    }

    object AndroidAccess : Access {
        override fun listenerEnabled(): Boolean = NotificationBridge.connected
    }

    override val capabilityId: String = "device.notifications"
    override val providerId: String = "notification.listener"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability =
        if (access.listenerEnabled()) Availability.Available
        else Availability.NeedsSetup(
            reason = "NOTIFICATION_ACCESS_DISABLED",
            fixAction = "OPEN_NOTIFICATION_LISTENER_SETTINGS",
        )

    override fun permissionFor(method: String, args: kotlinx.serialization.json.JsonObject): String =
        "device.notifications"

    override suspend fun invoke(ctx: CallContext, method: String, args: kotlinx.serialization.json.JsonObject): Envelope =
        try {
            when (method) {
                "list" -> list(ctx, args)
                "read" -> read(ctx, args)
                "press" -> press(ctx, args)
                "dismiss" -> dismiss(ctx, args)
                "dismiss_all" -> dismissAll(ctx)
                "wait_for" -> waitFor(ctx, args)
                else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.notifications method '$method'")
            }
        } catch (e: RuntimeException) {
            Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "notification listener unavailable: ${e.message}")
        }

    private fun scopedItems(ctx: CallContext): List<NotificationBridge.Active> {
        val scope = ctx.scopePackages("device.notifications")
        val items = NotificationBridge.active.value.values.sortedByDescending { it.postedAtMs }
        return if (scope == null) items else items.filter { it.pkg in scope }
    }

    private fun byKey(ctx: CallContext, key: String): NotificationBridge.Active? =
        scopedItems(ctx).firstOrNull { it.key == key }

    private fun list(ctx: CallContext, args: kotlinx.serialization.json.JsonObject): Envelope {
        val packageFilter = args["package"]?.let { (it as? JsonPrimitive)?.content }
        val includeOngoing = args["include_ongoing"]?.let { (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull() } ?: true
        val items = scopedItems(ctx)
            .filter { packageFilter == null || it.pkg == packageFilter }
            .filter { includeOngoing || !it.ongoing }
        return Envelope.ok(buildJsonObject {
            put("count", items.size)
            put("notifications", kotlinx.serialization.json.JsonArray(items.map { it.toJson() }))
        })
    }

    private fun read(ctx: CallContext, args: kotlinx.serialization.json.JsonObject): Envelope {
        val key = args["id"]?.let { (it as? JsonPrimitive)?.content }
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"id\" is required")
        val item = byKey(ctx, key)
            ?: return Envelope.err(ErrorCode.NOT_FOUND, "no notification with that id in the visible scope")
        return Envelope.ok(item.toJson())
    }

    private suspend fun press(ctx: CallContext, args: kotlinx.serialization.json.JsonObject): Envelope {
        val key = args["id"]?.let { (it as? JsonPrimitive)?.content }
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"id\" is required")
        val item = byKey(ctx, key)
            ?: return Envelope.err(ErrorCode.NOT_FOUND, "no notification with that id in the visible scope")
        if (item.actions.isEmpty()) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "the notification has no action buttons")
        }
        val index: Int = when (val actionParam = args["action"]) {
            is JsonPrimitive -> actionParam.content.toIntOrNull()
                ?: item.actions.firstOrNull { it.label == actionParam.content }?.index
                ?: return Envelope.err(
                    ErrorCode.INVALID_ARGS,
                    "no action at index or labelled '${actionParam.content}'",
                )
            else -> item.actions.first().index
        }
        val sent = AutoHostNotificationListenerHandlers.press?.invoke(key, index) ?: false
        return if (sent) {
            Envelope.ok(buildJsonObject { put("sent", true); put("action_index", index) })
        } else {
            Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "could not send the action intent")
        }
    }

    private suspend fun dismiss(ctx: CallContext, args: kotlinx.serialization.json.JsonObject): Envelope {
        val key = args["id"]?.let { (it as? JsonPrimitive)?.content }
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"id\" is required")
        val item = byKey(ctx, key)
            ?: return Envelope.err(ErrorCode.NOT_FOUND, "no notification with that id in the visible scope")
        return if (AutoHostNotificationListenerHandlers.dismiss?.invoke(item.key) == true) {
            NotificationBridge.noteDismissed(item.key)
            Envelope.ok(buildJsonObject { put("dismissed", true) })
        } else {
            Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "the system refused the dismissal")
        }
    }

    private suspend fun dismissAll(ctx: CallContext): Envelope {
        val keys = scopedItems(ctx).filter { !it.ongoing }.map { it.key }
        var count = 0
        for (key in keys) {
            if (AutoHostNotificationListenerHandlers.dismiss?.invoke(key) == true) {
                NotificationBridge.noteDismissed(key)
                count += 1
            }
        }
        return Envelope.ok(buildJsonObject { put("dismissed", count) })
    }

    private suspend fun waitFor(ctx: CallContext, args: kotlinx.serialization.json.JsonObject): Envelope {
        val timeoutMs = args["timeout_ms"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 10_000
        val packageFilter = args["package"]?.let { (it as? JsonPrimitive)?.content }
        val titleRegex = args["title_regex"]?.let { (it as? JsonPrimitive)?.content }
        val textRegex = args["text_regex"]?.let { (it as? JsonPrimitive)?.content }
        val deadline = clock.monotonicMs() + timeoutMs
        while (clock.monotonicMs() < deadline) {
            val items = scopedItems(ctx)
                .filter { packageFilter == null || it.pkg == packageFilter }
                .filter { matches(titleRegex, it.title) && matches(textRegex, it.text + " " + it.bigText) }
            if (items.isNotEmpty()) {
                return Envelope.ok(buildJsonObject {
                    put("found", true)
                    put("notification", items.first().toJson())
                })
            }
            delay(200)
        }
        return Envelope.err(ErrorCode.TIMEOUT, "no matching notification within ${timeoutMs}ms")
    }

    private fun matches(pattern: String?, value: String): Boolean {
        if (pattern == null) return true
        return runCatching { Regex(pattern).containsMatchIn(value) }.getOrDefault(false)
    }

    private fun NotificationBridge.Active.toJson() = buildJsonObject {
        put("id", key)
        put("package", pkg)
        put("title", title)
        put("text", text)
        if (subtext.isNotEmpty()) put("subtext", subtext)
        if (bigText.isNotEmpty()) put("big_text", bigText)
        put("posted_at_ms", postedAtMs)
        put("ongoing", ongoing)
        put("actions", kotlinx.serialization.json.JsonArray(actions.map { action ->
            buildJsonObject { put("index", action.index); put("label", action.label) }
        }))
    }
}
