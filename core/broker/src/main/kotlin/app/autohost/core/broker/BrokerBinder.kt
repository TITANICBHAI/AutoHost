package app.autohost.core.broker

import app.autohost.core.model.BrokerEvent
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.runBlocking

/**
 * The Binder stub workers get. `call` blocks the worker's thread by design (Python SDK calls are synchronous);
 * `emit` is fire-and-forget and never blocks. AIDL parameters are Java platform types — declared nullable here,
 * returns non-null (HANDOFF §6: first-build AIDL nullability pitfalls). Nothing throws across the boundary.
 */
class BrokerBinder(private val broker: BrokerService) : app.autohost.broker.ICapabilityBroker.Stub() {

    override fun call(runToken: String?, capability: String?, method: String?, argsJson: String?): String =
        runBlocking {
            val args = EnvelopeCodec.decodeArgs(argsJson)
                ?: return@runBlocking EnvelopeCodec.encode(
                    Envelope.err(ErrorCode.INVALID_ARGS, "arguments must be a JSON object", reason = "MALFORMED_ARGS"),
                )
            val envelope = broker.call(
                token = runToken.orEmpty(),
                capability = capability.orEmpty(),
                method = method.orEmpty(),
                args = args,
            )
            EnvelopeCodec.encode(envelope)
        }

    override fun emit(runToken: String?, eventJson: String?) {
        val token = runToken ?: return
        val event = EnvelopeCodec.decodeEvent(eventJson) ?: return
        runCatching { sink.onEvent(token, event) } // emit must never throw back into the worker
    }

    override fun describe(runToken: String?): String = EnvelopeCodec.describe(broker.describe(runToken.orEmpty()))

    companion object {
        /** Installed once by the host: the binder forwards events to the engine's orchestrator. */
        @Volatile
        var sink: EventSink = EventSink { _, _ -> }
    }
}

/** Event stream for tests. */
class RecordingEventSink : EventSink {
    val events = mutableListOf<Pair<String, BrokerEvent>>()

    override fun onEvent(runToken: String, event: BrokerEvent) {
        events.add(runToken to event)
    }
}
