package app.autohost.runtime.js

import app.autohost.core.common.Clock
import app.autohost.core.model.Envelope
import app.autohost.core.common.AhJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * P11-01 (ADR-076): the host side of the JS bridge. The prelude exposes ONE engine global —
 * `__autohost_bridge(op, payloadJson) -> replyJson` — and this class serves the ops synchronously
 * on the engine thread, exactly like the Python `Runtime` serves SDK calls on the worker thread:
 * broker calls block, pause parks, cancel raises a typed `CANCELLED` error the prelude turns into
 * a JS exception. The JS v1 surface (one table, docs/SDK_JS.md):
 *
 * | op            | payload                          | reply.result                       |
 * |---------------|----------------------------------|------------------------------------|
 * | call          | {capability, method, args}       | broker result (or broker error)    |
 * | emit          | event object                     | null (redacted host-side)          |
 * | sleep         | {seconds}                        | null (CANCELLED error on cancel)   |
 * | ctx           | {what: config|profile|run|trigger|state} | the value                  |
 * | state_save    | full state object                | null (also calls state.set)        |
 * | secret        | {name}                           | the value (NOT_FOUND when absent)  |
 * | clock         | {}                               | {now_iso}                          |
 * | result        | {code, data}                     | null (the run result)              |
 * | next_run      | {after_minutes}|{at}             | null (+ emits the next_run event)  |
 * | capabilities  | {}                               | the availability describe()        |
 *
 * Reply shape: {"ok":true,"result":<json>} | {"ok":false,"error":{code,message,retryable,reason,
 * fixAction,details}} — the prelude throws the latter as a JS `AutoHostError`.
 */
class JsBridge(
    private val caller: JsBrokerCaller,
    private val clock: Clock,
    private val controls: JsRunControls,
    private val runInfo: JsRunInfo,
    private val secretOf: (String) -> String?,
    private val redact: (String) -> String,
    private val sleeper: (Long) -> Unit,
) {

    /** Checkpoint state (RunSpec.checkpoint) — JS reads a copy, [OP_STATE_SAVE] replaces it. */
    private val state = AtomicReference(runInfo.checkpoint)

    /** Set by the `result` op (mirror of Python `ctx.result` → `rt.result`). */
    val pendingResult = AtomicReference<JsonObject?>(null)

    /** Entry point installed into the engine. Never throws; errors ride the reply envelope. */
    fun handle(op: String, payloadJson: String): String {
        val reply = try {
            serve(op, payloadJson)
        } catch (e: JsCancelledSignal) {
            errorReply(CANCELLED_CODE, "run was cancelled", retryable = false)
        } catch (e: Exception) {
            errorReply("INTERNAL", e.message ?: e.javaClass.simpleName, retryable = false)
        }
        return AhJson.json.encodeToString(JsonElement.serializer(), reply)
    }

    private fun serve(op: String, payloadJson: String): JsonObject {
        val payload = parsePayload(payloadJson)
        return when (op) {
            OP_CALL -> opCall(payload)
            OP_EMIT -> opEmit(payload)
            OP_SLEEP -> opSleep(payload)
            OP_CTX -> opCtx(payload)
            OP_STATE_SAVE -> opStateSave(payload)
            OP_SECRET -> opSecret(payload)
            OP_CLOCK -> opClock()
            OP_RESULT -> opResult(payload)
            OP_NEXT_RUN -> opNextRun(payload)
            OP_CAPABILITIES -> opCapabilities()
            else -> errorReply("INVALID_ARGS", "unknown bridge op '$op'", retryable = false)
        }
    }

    // --- ops -------------------------------------------------------------------------------

    private fun opCall(payload: JsonObject?): JsonObject {
        val p = payload ?: return errorReply("INVALID_ARGS", "call needs a payload", retryable = false)
        val capability = (p["capability"] as? JsonPrimitive)?.content
        val method = (p["method"] as? JsonPrimitive)?.content
        if (capability.isNullOrBlank() || method.isNullOrBlank()) {
            return errorReply("INVALID_ARGS", "call needs capability and method", retryable = false)
        }
        val args = (p["args"] as? JsonObject) ?: JsonObject(emptyMap())
        controls.checkpoint() // SDK-call cancellation semantics, same checkpoint position as Python
        val raw = caller.call(runInfo.runToken, capability, method, AhJson.json.encodeToString(JsonElement.serializer(), args))
        val envelope = try {
            AhJson.json.decodeFromString(Envelope.serializer(), raw)
        } catch (e: Exception) {
            null
        }
        if (envelope == null) {
            return errorReply("INTERNAL", "unparsable broker envelope for $capability.$method", retryable = false)
        }
        if (!envelope.ok) {
            val err = envelope.error
            return errorReply(
                code = err?.code ?: "INTERNAL",
                message = err?.message ?: "",
                retryable = err?.retryable ?: false,
                reason = err?.reason,
                fixAction = err?.fixAction,
                details = err?.details,
            )
        }
        return buildJsonObject {
            put("ok", true)
            put("result", envelope.result ?: JsonNull)
        }
    }

    private fun opEmit(payload: JsonObject?): JsonObject {
        val event = payload ?: JsonObject(emptyMap())
        caller.emit(runInfo.runToken, AhJson.json.encodeToString(JsonElement.serializer(), redactDeep(event)))
        return okNull()
    }

    private fun opSleep(payload: JsonObject?): JsonObject {
        val seconds = (payload?.get("seconds") as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
        controls.sleepMs((seconds.coerceIn(0.0, MAX_SLEEP_SECONDS) * 1000).toLong(), sleeper)
        return okNull()
    }

    private fun opCtx(payload: JsonObject?): JsonObject {
        val what = (payload?.get("what") as? JsonPrimitive)?.content ?: ""
        return when (what) {
            "config" -> runInfo.config
            "profile" -> runInfo.profile
            "run" -> buildJsonObject {
                put("id", runInfo.runId)
                put("attempt", runInfo.attempt)
                put("trigger", buildJsonObject {
                    put("type", runInfo.triggerType)
                    put("payload", runInfo.triggerPayload)
                })
                put("started_at", runInfo.startedAtIso)
            }
            "trigger" -> buildJsonObject {
                put("type", runInfo.triggerType)
                put("payload", runInfo.triggerPayload)
            }
            "state" -> state.get()
            else -> throw IllegalArgumentException("unknown ctx '$what'")
        }.let { value ->
            buildJsonObject { put("ok", true); put("result", value) }
        }
    }

    private fun opStateSave(payload: JsonObject?): JsonObject {
        val newState = payload ?: JsonObject(emptyMap())
        state.set(newState)
        // ctx.save() in Python calls state.set through the broker; mirror exactly (raises on error).
        val reply = callViaBridge("state", "set", buildJsonObject { put("state", newState) })
        return if ((reply["ok"] as? JsonPrimitive)?.content == "true") okNull() else reply
    }

    private fun opSecret(payload: JsonObject?): JsonObject {
        val name = (payload?.get("name") as? JsonPrimitive)?.content.orEmpty()
        val value = if (name.isEmpty()) null else secretOf(name)
        return if (value == null) {
            errorReply("NOT_FOUND", "secret '$name' is not declared for this profile", retryable = false)
        } else {
            buildJsonObject { put("ok", true); put("result", JsonPrimitive(value)) }
        }
    }

    private fun opClock(): JsonObject = buildJsonObject {
        put("ok", true)
        put("result", buildJsonObject { put("now_iso", IsoFormat.ofMs(clock.nowMs())) })
    }

    private fun opResult(payload: JsonObject?): JsonObject {
        val code = (payload?.get("code") as? JsonPrimitive)?.content ?: "OK"
        val data = (payload?.get("data") as? JsonObject) ?: JsonObject(emptyMap())
        pendingResult.set(buildJsonObject { put("code", JsonPrimitive(code)); put("data", data) })
        return okNull()
    }

    private fun opNextRun(payload: JsonObject?): JsonObject {
        val afterMinutes = (payload?.get("after_minutes") as? JsonPrimitive)?.content?.toIntOrNull()
        val at = (payload?.get("at") as? JsonPrimitive)?.content
        if ((afterMinutes == null) == (at == null)) {
            return errorReply("INVALID_ARGS", "give exactly one of after_minutes or at", retryable = false)
        }
        val request = buildJsonObject {
            put("after_minutes", afterMinutes?.let { JsonPrimitive(it) } ?: JsonNull)
            put("at", at?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        caller.emit(
            runInfo.runToken,
            AhJson.json.encodeToString(
                JsonElement.serializer(),
                buildJsonObject { put("type", "next_run"); put("data", request) },
            ),
        )
        return okNull()
    }

    private fun opCapabilities(): JsonObject {
        val described = runCatching { caller.describe(runInfo.runToken) }.getOrDefault("{}")
        val element = runCatching { AhJson.json.parseToJsonElement(described) }.getOrDefault(JsonObject(emptyMap()))
        return buildJsonObject { put("ok", true); put("result", element) }
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun callViaBridge(capability: String, method: String, args: JsonObject): JsonObject =
        opCall(buildJsonObject {
            put("capability", capability)
            put("method", method)
            put("args", args)
        })

    private fun redactDeep(element: JsonElement): JsonElement = when (element) {
        is JsonPrimitive -> if (element.isString) JsonPrimitive(redact(element.content)) else element
        is JsonArray -> JsonArray(element.map { redactDeep(it) })
        is JsonObject -> JsonObject(element.mapValues { (_, v) -> redactDeep(v) })
    }

    private fun parsePayload(payloadJson: String?): JsonObject? {
        if (payloadJson.isNullOrBlank() || payloadJson == "null") return null
        val element = runCatching { AhJson.json.parseToJsonElement(payloadJson) }.getOrNull() ?: return null
        return element as? JsonObject
    }

    private fun okNull(): JsonObject = buildJsonObject { put("ok", true); put("result", JsonNull) }

    private fun errorReply(
        code: String,
        message: String,
        retryable: Boolean,
        reason: String? = null,
        fixAction: String? = null,
        details: JsonElement? = null,
    ): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
            put("retryable", retryable)
            reason?.let { put("reason", it) }
            fixAction?.let { put("fixAction", it) }
            details?.let { put("details", it) }
        })
    }

    companion object {
        const val OP_CALL = "call"
        const val OP_EMIT = "emit"
        const val OP_SLEEP = "sleep"
        const val OP_CTX = "ctx"
        const val OP_STATE_SAVE = "state_save"
        const val OP_SECRET = "secret"
        const val OP_CLOCK = "clock"
        const val OP_RESULT = "result"
        const val OP_NEXT_RUN = "next_run"
        const val OP_CAPABILITIES = "capabilities"
        const val CANCELLED_CODE = "CANCELLED"

        /** One bridge sleep is capped (a JS task asking for a month of sleep still ticks cancel). */
        const val MAX_SLEEP_SECONDS = 3600.0
    }
}

/** Broker surface the bridge uses — the app wires it to the AIDL binder, tests to a fake. */
interface JsBrokerCaller {
    fun call(runToken: String, capability: String, method: String, argsJson: String): String

    fun describe(runToken: String): String

    fun emit(runToken: String, eventJson: String)
}

/** Immutable per-run values the bridge mirrors into `ctx.run`/`ctx.trigger` (Python shape). */
data class JsRunInfo(
    val runToken: String,
    val runId: String,
    val attempt: Int,
    val triggerType: String,
    val triggerPayload: JsonObject,
    val startedAtIso: String,
    val config: JsonObject,
    val profile: JsonObject,
    val checkpoint: JsonObject,
)

/** ISO-8601 UTC seconds precision — the same shape the Python SDK's `now_iso()` produces. */
object IsoFormat {
    fun ofMs(epochMs: Long): String {
        val instant = java.time.Instant.ofEpochMilli(epochMs)
        val seconds = instant.epochSecond
        return java.time.Instant.ofEpochSecond(seconds).toString().replace(".000Z", "Z")
    }
}

/** Thrown internally when a control check trips; converted to the CANCELLED error reply. */
class JsCancelledSignal : Exception("run was cancelled")

/**
 * Control flags shared by the handle (caller thread) and the engine thread — the JS twin of the
 * flow runtime's `RunControls`. Pause parks inside [sleepMs] and between ops ([checkpoint]);
 * cancel raises [JsCancelledSignal] from both. `finished` latches so late controls are no-ops.
 * The monotonic source is injectable so JVM tests can advance sleeps without real waiting.
 */
class JsRunControls(private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    val cancelled = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val pauseLock = Object()

    fun requestCancel() {
        cancelled.set(true)
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    fun requestPause() {
        paused.set(true)
    }

    fun requestResume() {
        paused.set(false)
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    fun finish() {
        finished.set(true)
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    /** Called before every bridge op; throws [JsCancelledSignal] when cancelled, parks while paused. */
    fun checkpoint() {
        if (cancelled.get()) throw JsCancelledSignal()
        waitWhilePaused()
        if (cancelled.get()) throw JsCancelledSignal()
    }

    /** Blocking, pause-aware, cancel-throwing sleep (runs on the engine thread). */
    fun sleepMs(ms: Long, sleeper: (Long) -> Unit) {
        checkpoint()
        val deadline = monotonicMs() + ms.coerceAtLeast(0)
        while (true) {
            if (cancelled.get()) throw JsCancelledSignal()
            waitWhilePaused()
            if (cancelled.get()) throw JsCancelledSignal()
            val now = monotonicMs()
            if (now >= deadline) break
            sleeper(minOf(deadline - now, 200))
        }
        if (cancelled.get()) throw JsCancelledSignal()
    }

    private fun waitWhilePaused() {
        synchronized(pauseLock) {
            while (paused.get() && !cancelled.get() && !finished.get()) {
                try {
                    pauseLock.wait(200)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }
}
