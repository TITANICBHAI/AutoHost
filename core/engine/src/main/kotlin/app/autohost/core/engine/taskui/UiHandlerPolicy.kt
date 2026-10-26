package app.autohost.core.engine.taskui

import app.autohost.core.common.AhJson
import app.autohost.core.common.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * P7-03 (plan §5.2/§10.1): the pure policy for UI-handler calls. The `:ui` worker serves one handler
 * call at a time per task, with a hard 30 s cap ("warm UI-handler worker ... 30 s cap, never blocks
 * long runs"). The controller (:app) owns the binder plumbing; everything testable lives here.
 */
const val UI_HANDLER_TIMEOUT_MS: Long = 30_000L

/** Handler names share the Python-identifier shape the SDK decorator allows. */
private val HANDLER_NAME = Regex("[a-zA-Z_][a-zA-Z0-9_-]*")

/** The spec the `:ui` worker receives (mirrors autohost._runtime.run_ui_handler). */
data class UiHandlerSpec(
    val taskId: String,
    val taskPath: String,
    val entry: String,
    val handler: String,
    val args: JsonObject = JsonObject(emptyMap()),
    val config: JsonObject = JsonObject(emptyMap()),
    val profile: JsonObject = JsonObject(emptyMap()),
    val profileId: String? = null,
    val runToken: String = "",
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("taskId", taskId)
        put("taskPath", taskPath)
        put("entry", entry)
        put("handler", handler)
        put("args", args)
        put("config", config)
        put("profile", profile)
        profileId?.let { put("profileId", it) }
        if (runToken.isNotEmpty()) put("runToken", runToken)
    }

    companion object {
        /** Fails closed with a precise message; the controller surfaces it to the calling surface. */
        fun build(
            taskId: String,
            taskPath: String,
            entry: String,
            handler: String,
            args: JsonObject = JsonObject(emptyMap()),
            config: JsonObject = JsonObject(emptyMap()),
            profile: JsonObject = JsonObject(emptyMap()),
            profileId: String? = null,
        ): UiHandlerSpec {
            if (taskId.isBlank()) throw UiHandlerPolicyException("task id is required")
            if (taskPath.isBlank()) throw UiHandlerPolicyException("task path is required")
            if (entry.isBlank() || entry.contains("..") || entry.startsWith('/')) {
                throw UiHandlerPolicyException("entry must be a task-relative file name (got '$entry')")
            }
            if (!HANDLER_NAME.matches(handler)) {
                throw UiHandlerPolicyException("handler name must match $HANDLER_NAME (got '$handler')")
            }
            return UiHandlerSpec(taskId, taskPath, entry, handler, args, config, profile, profileId)
        }
    }
}

class UiHandlerPolicyException(message: String) : Exception(message)

/** The outcome JSON the worker returns (mirrors run_ui_handler's outcome). */
data class UiHandlerOutcome(
    val status: String,
    val reason: String? = null,
    val message: String? = null,
    val data: JsonObject? = null,
) {
    val ok: Boolean get() = status == "SUCCEEDED"

    companion object {
        val BUSY = UiHandlerOutcome("FAILED", "HANDLER_BUSY", "a handler call is already running for this task")
        val TIMEOUT = UiHandlerOutcome("FAILED", "HANDLER_TIMEOUT", "the handler did not finish within ${UI_HANDLER_TIMEOUT_MS / 1000} s")

        /** Lenient parse: a malformed worker answer surfaces as null (the controller turns it into INTERNAL). */
        fun fromJson(text: String?): UiHandlerOutcome? = runCatching {
            val obj = AhJson.json.parseToJsonElement(text ?: return null).let { it as? JsonObject ?: return null }
            val status = (obj["status"] as? JsonPrimitive)?.contentOrNull ?: return null
            UiHandlerOutcome(
                status = status,
                reason = (obj["reason"] as? JsonPrimitive)?.contentOrNull,
                message = (obj["message"] as? JsonPrimitive)?.contentOrNull,
                data = (obj["result"] as? JsonObject)?.get("data")?.let { it as? JsonObject },
            )
        }.getOrNull()
    }
}

/** Single-flight per task: a second concurrent call is refused (busy), never queued — the surface can retry. */
class UiHandlerGate {
    private val busy = ConcurrentHashMap.newKeySet<String>()

    fun tryAcquire(taskId: String): Boolean = busy.add(taskId)

    fun release(taskId: String) {
        busy.remove(taskId)
    }
}

/** Idle policy for the warm worker: stop the process after this long with no call (low-RAM rule, §5.2). */
class UiHandlerIdlePolicy(private val idleStopMs: Long = IDLE_STOP_MS, private val clock: Clock) {
    @Volatile private var lastUsedMs: Long = clock.nowMs()

    fun touch() {
        lastUsedMs = clock.nowMs()
    }

    fun shouldStop(nowMs: Long = clock.nowMs()): Boolean = nowMs - lastUsedMs >= idleStopMs

    companion object {
        const val IDLE_STOP_MS: Long = 30_000L
    }
}
