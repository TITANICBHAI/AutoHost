package app.autohost.runtime.python

import app.autohost.broker.ICapabilityBroker
import app.autohost.core.common.AhJson
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode

/**
 * The object Python sees as its bridge to the broker (HANDOFF §3). JSON strings in, JSON strings out — the
 * Python side (autohost._runtime.BridgeTransport) does the dict conversion. AIDL parameters are platform
 * types: nullable in, non-null out. Nothing throws into Python: a dead binder arrives as an INTERNAL envelope
 * and the host learns about process death from the runtime's death listener.
 */
class PyBridge(private val broker: ICapabilityBroker, private val runToken: String) {

    /** Blocking capability call; returns the Envelope JSON string. */
    fun call(capability: String?, method: String?, argsJson: String?): String = try {
        broker.call(runToken, capability.orEmpty(), method.orEmpty(), argsJson ?: "{}")
            ?: error("broker returned null")
    } catch (e: Exception) {
        encodeError("bridge call failed: ${e.javaClass.simpleName}: ${e.message}")
    }

    /** Fire-and-forget event delivery. Never throws into Python. */
    fun emit(eventJson: String?) {
        try {
            broker.emit(runToken, eventJson ?: "{}")
        } catch (e: Exception) {
            // Events are best-effort; process death is observed via the death listener.
        }
    }

    /** Granted capabilities + live availability, for autohost.capabilities(). */
    fun describe(): String = try {
        broker.describe(runToken) ?: "{}"
    } catch (e: Exception) {
        "{}"
    }

    private fun encodeError(message: String): String =
        AhJson.json.encodeToString(Envelope.serializer(), Envelope.err(ErrorCode.INTERNAL, message))
}
