package app.autohost.core.engine.host

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * P7-05 (plan §10.3): the `notify.post` capability — task notifications with optional handler actions
 * and progress. Pure JVM: Android specifics live in the injected [NotificationSink] (:app builds the
 * Notification + PendingIntents; action taps route to the UI-handler path).
 *
 * Actions (validated here, schema: {"label", "handler", "args"?}) — at most 3, each naming a
 * @ui.handler of the same task; a tap re-enters the task through the `:ui` worker (30 s cap).
 */
data class NotificationActionSpec(val label: String, val handler: String, val args: JsonObject)

data class NotificationRequest(
    val taskId: String,
    val title: String,
    val text: String,
    val channel: String?,
    val actions: List<NotificationActionSpec>,
    val progress: Double?,
)

fun interface NotificationSink {
    /** Returns the platform notification id. Must never throw for honest-failure cases — return -1. */
    fun post(request: NotificationRequest): Int
}

class NotifyProvider(
    private val sink: NotificationSink,
) : CapabilityProvider {

    override val capabilityId: String = "notify.post"
    override val providerId: String = "host.notify"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method == "post") "notify.post" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        if (method != "post") {
            return Envelope.err(ErrorCode.INVALID_ARGS, "unknown notify.post method '$method'")
        }
        val title = (args["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        val text = (args["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        if (title.isBlank() && text.isBlank()) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "notification needs a title or text")
        }
        val actions = mutableListOf<NotificationActionSpec>()
        val rawActions = args["actions"] as? kotlinx.serialization.json.JsonArray
        if (rawActions != null) {
            if (rawActions.size > MAX_ACTIONS) {
                return Envelope.err(ErrorCode.INVALID_ARGS, "at most $MAX_ACTIONS notification actions")
            }
            rawActions.forEachIndexed { i, element ->
                val action = element as? JsonObject
                    ?: return Envelope.err(ErrorCode.INVALID_ARGS, "action[$i] must be an object")
                val label = (action["label"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim() ?: ""
                val handler = (action["handler"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim() ?: ""
                if (label.isEmpty()) {
                    return Envelope.err(ErrorCode.INVALID_ARGS, "action[$i].label must be a non-empty string")
                }
                if (handler.isEmpty()) {
                    return Envelope.err(ErrorCode.INVALID_ARGS, "action[$i].handler must name a @ui.handler")
                }
                actions.add(
                    NotificationActionSpec(
                        label = label,
                        handler = handler,
                        args = action["args"] as? JsonObject ?: JsonObject(emptyMap()),
                    ),
                )
            }
        }
        val progress = (args["progress"] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull?.let {
            if (it < 0.0 || it > 1.0) {
                return Envelope.err(ErrorCode.INVALID_ARGS, "progress must be between 0 and 1")
            }
            it
        }
        val channel = (args["channel"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val id = sink.post(
            NotificationRequest(
                taskId = ctx.taskId,
                title = title,
                text = text,
                channel = channel?.takeIf { it.isNotBlank() },
                actions = actions,
                progress = progress,
            ),
        )
        return Envelope.ok(buildJsonObject { put("id", id) })
    }

    companion object {
        const val MAX_ACTIONS = 3
    }
}
