package app.autohost.runtime.js

/**
 * P11-01: the engine seam. `:runtime:js` is pure JVM and knows nothing about QuickJS; the
 * Android-backed implementation (`QuickJsEngineFactory` in `:app`, the only file touching
 * `io.github.dokar3:quickjs-kt-android`, ADR-076) plugs in at wiring time, and JVM tests use a
 * scripted fake. This is the same isolation pattern as ShizukuProcessExec / MlKitOcrEngine /
 * ZxingQrDecoder: the third-party touchpoint is one owner-verified file behind a seam.
 *
 * Outcome contract (pinned by [JsOutcome], the node shim and the Python parity tests):
 * [runEntry] evaluates the prelude, then the entry, then invokes `main` and returns the outcome
 * as one JSON string:
 *   {"status":"ok","value":<json|null>}
 *   {"status":"error","error":{"kind":"entry|autohost|needs_user|cancelled|exception",
 *                              "code"?,"message","stack"?,"reason"?,"resume_hint"?,"details"?}}
 * Engines that support ES modules (QuickJS) make the entry module's namespace available to the
 * prelude's main-resolution as `__autohost_exports`; script engines rely on the global `main`.
 */
interface JsEngine {

    /** Installs the host bridge: [bridge] receives `(op, payloadJson)` and returns a JSON reply. */
    fun installBridge(bridge: (op: String, payload: String) -> String)

    /** Hard caps from [JsRunPolicy]; engines apply them before any script runs. */
    fun setLimits(maxMemoryBytes: Long, maxStackBytes: Long)

    /** Polled by the engine between operations; true aborts the script (cancel/deadline). */
    fun setInterruptHandler(check: () -> Boolean)

    /** Runs the contract described above. Throws only for engine-internal failures (worker death). */
    fun runEntry(
        preludeSource: String,
        entrySource: String,
        entryName: String,
        moduleLoader: ((String) -> String?)?,
    ): String

    /** Releases engine resources. Never called while the engine thread is still running. */
    fun close()
}

/** Factory seam so the provider stays engine-agnostic and tests script their own engine. */
fun interface JsEngineFactory {
    fun create(): JsEngine
}
