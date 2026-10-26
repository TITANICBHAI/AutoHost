package app.autohost.capabilities.data

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * P10-08 (ADR-074): `device.sms` and `device.telephony` — the DANGEROUS communication pair,
 * `full` flavor only (the `core` flavor manifest does not declare SEND/RECEIVE/READ_SMS, so
 * the platform cannot grant them; the providers still report honest availability either way).
 *
 * Sends are the highest-cost action in the platform, so [SmsPolicy] rails them: validated
 * non-premium destinations, body caps, per-run send budget, and honest outcomes (sent vs
 * delivered-unverified — SMS delivery receipts are a carrier promise, not a readback).
 * Reading is row-capped and filtered to what the run's grant declares (`senders` scope).
 */
object SmsPolicy {

    const val FEATURE = "sms" // the full-flavor feature gate

    fun sendAllowed(sendsSoFar: Int): Boolean = sendsSoFar < DataPolicy.MAX_SMS_SENDS_PER_RUN

    fun destinationVerdict(raw: String, allowShort: Boolean): DataPolicy.NumberVerdict =
        DataPolicy.numberVerdict(raw, allowShort)
}

object TelephonySeams {

    data class SmsRow(
        val sender: String,
        val body: String,
        val receivedMs: Long,
        val read: Boolean,
    )

    data class SendOutcome(val sent: Boolean, val error: String? = null, val parts: Int = 1)

    interface Sms {
        fun inbox(filter: String?, senders: Set<String>?, limit: Int): List<SmsRow>
        fun send(address: String, body: String): SendOutcome
    }

    enum class CallState { IDLE, RINGING, OFFHOOK }

    interface Telephony {
        fun callState(): Pair<CallState, String?> // state + ringing number when known
        fun placeCall(number: String): Boolean    // ACTION_CALL through the dialer — user-visible
    }
}

/** Flavor + permission gate shared by both providers. */
abstract class FullFlavorProvider : CapabilityProvider {
    @Volatile
    var flavorFull: Boolean = false

    @Volatile
    var granted: Boolean = false

    override fun availability(env: DeviceEnv): Availability = when {
        !flavorFull -> Availability.Unsupported(reason = "UNSUPPORTED_FLAVOR")
        !granted -> Availability.NeedsSetup(reason = "RUNTIME_PERMISSION:sms", fixAction = "REQUEST_PERMISSIONS")
        else -> Availability.Available
    }
}

class SmsProvider(
    private val sms: TelephonySeams.Sms,
    private val clock: app.autohost.core.common.Clock,
) : FullFlavorProvider() {

    override val capabilityId: String = "device.sms"
    override val providerId: String = "device.sms"
    override val tier: String = "T0"

    override fun permissionFor(method: String, args: JsonObject): String? = when (method) {
        "read_inbox", "wait_for" -> "device.sms.read"
        "send" -> "device.sms.send"
        else -> null
    }

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "read_inbox" -> {
            val limit = DataPolicy.rows((args["limit"] as? JsonPrimitive)?.content?.toIntOrNull())
            val filter = (args["filter"] as? JsonPrimitive)?.content
            val senders = ctx.scopePackages("device.sms.read")
            val rows = sms.inbox(filter, senders, limit)
            Envelope.ok(buildJsonObject {
                putJsonArray("messages") {
                    rows.forEach { row ->
                        add(buildJsonObject {
                            put("sender", row.sender)
                            put("body", row.body)
                            put("received_ms", row.receivedMs)
                            put("read", row.read)
                        })
                    }
                }
                put("count", rows.size)
            })
        }
        "send" -> {
            val address = (args["address"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"address\" is required")
            val body = (args["body"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"body\" is required")
            val allowShort = (args["allow_short"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
            when (val verdict = SmsPolicy.destinationVerdict(address, allowShort)) {
                is DataPolicy.NumberVerdict.Bad -> return Envelope.err(ErrorCode.SCOPE_VIOLATION, verdict.detail, fixAction = "OPEN_PERMISSION_REVIEW")
                is DataPolicy.NumberVerdict.Ok -> {}
            }
            DataPolicy.smsBodyVerdict(body)?.let { return Envelope.err(ErrorCode.INVALID_ARGS, it) }
            val sent = sendsThisRun.getOrDefault(ctx.runId, 0)
            if (!SmsPolicy.sendAllowed(sent)) {
                return Envelope.err(
                    ErrorCode.RATE_LIMITED,
                    "at most ${DataPolicy.MAX_SMS_SENDS_PER_RUN} SMS per run (this is a money-costing action)",
                )
            }
            val outcome = sms.send(address, body)
            if (outcome.sent) {
                sendsThisRun[ctx.runId] = sent + 1
            }
            Envelope.ok(buildJsonObject {
                put("sent", outcome.sent)
                put("delivered", false) // receipts are a carrier promise — never claimed
                if (outcome.error != null) put("error", outcome.error)
            })
        }
        "wait_for" -> {
            val contains = (args["contains"] as? JsonPrimitive)?.content
            val from = (args["from"] as? JsonPrimitive)?.content
            val timeoutMs = ((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 30_000L).coerceIn(1_000, 300_000)
            val deadline = clock.nowMs() + timeoutMs
            while (clock.nowMs() < deadline) {
                val rows = sms.inbox(contains, from?.let { setOf(it) }, 5)
                val hit = rows.maxByOrNull { it.receivedMs }
                if (hit != null && (contains == null || hit.body.contains(contains)) && (from == null || hit.sender == from)) {
                    return Envelope.ok(buildJsonObject {
                        put("sender", hit.sender)
                        put("body", hit.body)
                        put("received_ms", hit.receivedMs)
                    })
                }
                kotlinx.coroutines.delay(250)
            }
            Envelope.err(ErrorCode.TIMEOUT, "no matching SMS within ${timeoutMs}ms")
        }
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.sms method '$method'")
    }

    companion object {
        /** Per-run send budget counters (cleared with the run; app wiring resets on terminal). */
        val sendsThisRun = java.util.concurrent.ConcurrentHashMap<String, Int>()

        fun clearRun(runId: String) {
            sendsThisRun.remove(runId)
        }
    }
}

class TelephonyProvider(private val telephony: TelephonySeams.Telephony) : FullFlavorProvider() {

    override val capabilityId: String = "device.telephony"
    override val providerId: String = "device.telephony"
    override val tier: String = "T0"

    override fun permissionFor(method: String, args: JsonObject): String? = when (method) {
        "call_state" -> "device.telephony.read"
        "place_call" -> "device.telephony.place_call"
        else -> null
    }

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "call_state" -> {
            val (state, number) = telephony.callState()
            Envelope.ok(buildJsonObject {
                put("state", state.name.lowercase())
                if (number != null) put("number", number)
            })
        }
        "place_call" -> {
            val number = (args["number"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"number\" is required")
            val allowShort = (args["allow_short"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
            when (val verdict = SmsPolicy.destinationVerdict(number, allowShort)) {
                is DataPolicy.NumberVerdict.Bad -> return Envelope.err(ErrorCode.SCOPE_VIOLATION, verdict.detail, fixAction = "OPEN_PERMISSION_REVIEW")
                is DataPolicy.NumberVerdict.Ok -> {}
            }
            val placed = telephony.placeCall(number)
            Envelope.ok(buildJsonObject {
                put("placed", placed)
                put("verified", false) // the dialer owns the call; we never claim it connected
            })
        }
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.telephony method '$method'")
    }
}
