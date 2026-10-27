package app.autohost.prompt

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * P5-06 + P7-04 (plan §12.3/§7.4/§10.2): the `ui.prompt` capability — `ask`/`toast` (P5) plus
 * `dialog`, `form` and `overlay` (P7). A prompt flips the run to WAITING_USER semantics by
 * *blocking the worker call* until the person answers (PromptActivity), the timeout elapses, or the
 * run is cancelled — the run record itself is flipped by the worker's finished event when the script
 * chooses to end, or stays RUNNING when it continues.
 *
 * Post policy: heads-up notification on channel `prompts` with a full-screen intent so the prompt
 * is visible even in the shade; FLAG_SECURE on the activity (§14.4). `overlay` needs the
 * accessibility service and reports {"shown": false} honestly when it is off.
 */
class UiPromptProvider(
    private val context: Context,
    private val clock: app.autohost.core.common.Clock,
    private val textOverlay: UiTextOverlay? = null,
) : CapabilityProvider {

    override val capabilityId: String = "ui.prompt"
    override val providerId: String = "host.uiprompt"
    override val tier: String = "T0"

    class PendingPrompt(
        val promptId: String,
        val runId: String,
        val title: String,
        val message: String,
        val kind: String,
        val choices: List<String>,
        val deferred: CompletableDeferred<JsonObject>,
        val buttons: List<String> = emptyList(),
        val fields: kotlinx.serialization.json.JsonArray? = null,
    )

    private val pending = ConcurrentHashMap<String, PendingPrompt>()
    private val counter = AtomicLong()

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method in setOf("ask", "toast", "dialog", "form", "overlay")) "ui.prompt" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        return when (method) {
        "toast" -> {
            val text = args["text"]?.jsonPrimitive?.content ?: ""
            runCatching { android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show() }
            Envelope.ok(buildJsonObject { put("ok", true) })
        }
        "ask" -> {
            val title = args["title"]?.jsonPrimitive?.content ?: "AutoHost"
            val message = args["message"]?.jsonPrimitive?.content ?: ""
            val kind = args["kind"]?.jsonPrimitive?.content ?: "confirm"
            if (kind !in setOf("confirm", "text", "choice")) {
                return Envelope.err(ErrorCode.INVALID_ARGS, "unknown prompt kind '$kind'")
            }
            val choices = (args["choices"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?: emptyList()
            if (kind == "choice" && choices.isEmpty()) {
                return Envelope.err(ErrorCode.INVALID_ARGS, "a choice prompt needs choices")
            }
            val timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.intOrNull ?: DEFAULT_TIMEOUT_MS)
                .coerceIn(1_000, MAX_TIMEOUT_MS)

            val deferred = CompletableDeferred<JsonObject>()
            val prompt = PendingPrompt(
                promptId = nextId(),
                runId = ctx?.runId ?: "",
                title = title,
                message = message,
                kind = kind,
                choices = choices,
                deferred = deferred,
            )
            pending[prompt.promptId] = prompt
            notifyUser(prompt)
            try {
                val answer = withTimeoutOrNull(timeoutMs.toLong()) { deferred.await() }
                answer ?: Envelope.ok(buildJsonObject { put("answered", false); put("reason", "timeout") })
            } finally {
                pending.remove(prompt.promptId)
                cancelNotification(prompt.promptId)
            }
        }
        "dialog" -> {
            val title = args["title"]?.jsonPrimitive?.content ?: "AutoHost"
            val message = args["message"]?.jsonPrimitive?.content ?: ""
            val buttons = (args["buttons"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            if (buttons.size > MAX_BUTTONS) {
                return Envelope.err(ErrorCode.INVALID_ARGS, "at most $MAX_BUTTONS buttons are supported")
            }
            val deferred = CompletableDeferred<JsonObject>()
            val prompt = PendingPrompt(
                promptId = nextId(),
                runId = ctx?.runId ?: "",
                title = title,
                message = message,
                kind = "dialog",
                choices = emptyList(),
                deferred = deferred,
                buttons = buttons.ifEmpty { listOf("OK") },
            )
            pending[prompt.promptId] = prompt
            notifyUser(prompt)
            try {
                val answer = withTimeoutOrNull(DEFAULT_TIMEOUT_MS.toLong()) { deferred.await() }
                answer
                    ?: Envelope.ok(buildJsonObject { put("answered", false); put("reason", "timeout") })
            } finally {
                pending.remove(prompt.promptId)
                cancelNotification(prompt.promptId)
            }
        }
        "form" -> {
            val title = args["title"]?.jsonPrimitive?.content ?: "AutoHost"
            val fields = args["fields"] as? kotlinx.serialization.json.JsonArray
            if (fields == null || fields.isEmpty()) {
                return Envelope.err(ErrorCode.INVALID_ARGS, "form needs a non-empty 'fields' array")
            }
            if (fields.size > MAX_FORM_FIELDS) {
                return Envelope.err(ErrorCode.INVALID_ARGS, "at most $MAX_FORM_FIELDS form fields are supported")
            }
            val deferred = CompletableDeferred<JsonObject>()
            val prompt = PendingPrompt(
                promptId = nextId(),
                runId = ctx?.runId ?: "",
                title = title,
                message = "",
                kind = "form",
                choices = emptyList(),
                deferred = deferred,
                fields = fields,
            )
            pending[prompt.promptId] = prompt
            notifyUser(prompt)
            try {
                val answer = withTimeoutOrNull(DEFAULT_TIMEOUT_MS.toLong()) { deferred.await() }
                answer
                    ?: Envelope.ok(buildJsonObject { put("answered", false); put("reason", "timeout") })
            } finally {
                pending.remove(prompt.promptId)
                cancelNotification(prompt.promptId)
            }
        }
        "overlay" -> {
            val text = args["text"]?.jsonPrimitive?.content ?: ""
            val durationMs = args["duration_ms"]?.jsonPrimitive?.intOrNull ?: 3_000
            if (text.isBlank()) {
                return Envelope.err(ErrorCode.INVALID_ARGS, "overlay needs non-empty text")
            }
            if (durationMs < MIN_OVERLAY_MS || durationMs > MAX_OVERLAY_MS) {
                return Envelope.err(
                    ErrorCode.INVALID_ARGS,
                    "overlay duration_ms must be between $MIN_OVERLAY_MS and $MAX_OVERLAY_MS",
                )
            }
            val shown = textOverlay?.show(text, durationMs) ?: false
            Envelope.ok(
                buildJsonObject {
                    put("shown", shown)
                    if (!shown) put("reason", "ACCESSIBILITY_OFF")
                },
            )
        }
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown ui.prompt method '$method'")
        }
    }

    /** Called by [PromptActivity] when the user answers. */
    fun resolve(promptId: String, value: JsonObject): Boolean {
        val prompt = pending[promptId] ?: return false
        return prompt.deferred.complete(value)
    }

    fun pendingOf(promptId: String): PendingPrompt? = pending[promptId]

    /** The global kill switch (§5.2) closes prompts; cancelled runs resolve with answered=false. */
    fun closeAllForRun(runId: String) {
        for ((id, prompt) in pending) {
            if (prompt.runId == runId) {
                prompt.deferred.complete(buildJsonObject { put("answered", false); put("reason", "cancelled") })
                pending.remove(id)
                cancelNotification(id)
            }
        }
    }

    private fun nextId(): String = "prompt-${clock.nowMs()}-${counter.incrementAndGet()}"

    private fun notifyUser(prompt: PendingPrompt) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannelHelper.ensure(manager),
                )
            }
            val intent = PromptActivity.intent(context, prompt.promptId)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val contentIntent = android.app.PendingIntent.getActivity(context, prompt.promptId.hashCode(), intent, flags)
            val notification: Notification = android.app.Notification.Builder(context, NotificationChannelHelper.CHANNEL_ID)
                .setSmallIcon(context.resources.getIdentifier("ic_autohost", "drawable", context.packageName))
                .setContentTitle(prompt.title)
                .setContentText(prompt.message)
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_STOPWATCH) // keeps the heads-up visible
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            manager.notify(prompt.promptId.hashCode(), notification)
        }
    }

    private fun cancelNotification(promptId: String) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(promptId.hashCode())
        }
    }

    object NotificationChannelHelper {
        const val CHANNEL_ID = "prompts"

        fun ensure(manager: NotificationManager): android.app.NotificationChannel {
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing != null) return existing
            val channel = android.app.NotificationChannel(
                CHANNEL_ID, "Task prompts", NotificationManager.IMPORTANCE_HIGH,
            )
            manager.createNotificationChannel(channel)
            return channel
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 300_000
        const val MAX_TIMEOUT_MS = 3_600_000
        const val MAX_BUTTONS = 4
        const val MAX_FORM_FIELDS = 24
        const val MIN_OVERLAY_MS = 250
        const val MAX_OVERLAY_MS = 30_000
    }
}
