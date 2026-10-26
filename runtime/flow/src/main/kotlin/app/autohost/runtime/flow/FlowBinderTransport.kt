package app.autohost.runtime.flow

/**
 * Minimal transport boundary for the flow runtime.
 *
 * The Android host adapts its AIDL broker to this interface; keeping Android types out of this
 * module lets the interpreter and its broker handling remain JVM-testable.
 */
interface FlowBinderTransport {
    fun call(runToken: String, capability: String, method: String, argsJson: String): String

    fun emit(runToken: String, eventJson: String)
}
