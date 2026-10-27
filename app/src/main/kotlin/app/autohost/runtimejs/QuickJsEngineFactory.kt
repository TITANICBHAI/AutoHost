package app.autohost.runtimejs

import app.autohost.core.common.AhLog
import app.autohost.runtime.js.JsEngine
import app.autohost.runtime.js.JsEngineFactory
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.moduleLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * P11-02 (ADR-076): the ONLY file in the repo that imports the QuickJS binding
 * (`io.github.dokar3:quickjs-kt-android` 1.0.x, Apache-2.0). It adapts the binding to the pure
 * [JsEngine] seam so `:runtime:js` and every test stay engine-agnostic — the same isolation
 * pattern as ShizukuProcessExec / MlKitOcrEngine / ZxingQrDecoder.
 *
 * OWNER-VERIFY ON FIRST BUILD (this file was written without Gradle/device access; the binding's
 * suspend-based API is used via runBlocking on the dedicated engine thread — the thread IS the
 * process entry point for the engine, ADR-076). Contract mapping:
 *
 *  - installBridge: one native function `__autohost_bridge(payloadJson)` where the payload is a
 *    JSON array `[op, payloadJson]` (single-arg keeps the binding arity simple); the reply JSON
 *    string comes back verbatim. Blocking the JS thread for the call duration is BY DESIGN —
 *    the same blocking semantics as the Python worker.
 *  - setLimits: heap/stack caps are applied through the binding's runtime options when the
 *    artifact exposes them; if 1.0.3 lacks a knob the value is skipped with a logged warning
 *    (honest caps: deadline + interrupt still bound the run).
 *  - setInterruptHandler: quickjs-kt has no poll handler — a 200 ms watcher thread polls the
 *    check and calls [QuickJs.interruptEvaluation] when it flips true (cancel + run deadline).
 *  - runEntry: prelude script → entry re-exported as a module (`import * as ns from
 *    "__autohost_entry__"` with the loader serving the real entry source, so its `./lib/` + `*.js`
 *    imports resolve through [moduleLoader] with containment from JsSources) → the namespace
 *    lands in `__autohost_exports` → `JSON.stringify(__autohost_run_main())` returned verbatim.
 *  - A script-level failure returns the outcome JSON from the prelude; only engine-internal
 *    failures throw out of runEntry (worker death, like every other runtime).
 */
object QuickJsEngineFactory : JsEngineFactory {

    private const val TAG = "js-runtime"
    private const val ENTRY_MODULE = "__autohost_entry__"

    override fun create(): JsEngine = QuickJsEngine()

    private class QuickJsEngine : JsEngine {

        private var bridgeFn: ((op: String, payload: String) -> String)? = null
        private var interruptCheck: (() -> Boolean)? = null
        private var maxMemoryBytes: Long = -1
        private var maxStackBytes: Long = -1
        private var watcher: Thread? = null
        private var quickjs: QuickJs? = null

        override fun installBridge(bridge: (op: String, payload: String) -> String) {
            this.bridgeFn = bridge
        }

        override fun setLimits(maxMemoryBytes: Long, maxStackBytes: Long) {
            this.maxMemoryBytes = maxMemoryBytes
            this.maxStackBytes = maxStackBytes
        }

        override fun setInterruptHandler(check: () -> Boolean) {
            this.interruptCheck = check
        }

        override fun runEntry(
            preludeSource: String,
            entrySource: String,
            entryName: String,
            moduleLoader: ((String) -> String?)?,
        ): String {
            val bridgeFn = requireNotNull(bridgeFn) { "installBridge must be called before runEntry" }
            val quickjs = QuickJs.create()
            this.quickjs = quickjs
            startInterruptWatcher(quickjs)
            return try {
                quickjs.function("__autohost_bridge") { payload: String ->
                    serveBridge(bridgeFn, payload)
                }
                applyLimits(quickjs)
                if (moduleLoader != null) {
                    quickjs.moduleLoader {
                        normalize { _, requestedName -> requestedName }
                        load { name ->
                            when {
                                name == ENTRY_MODULE -> com.dokar.quickjs.ModuleContent.Source(entrySource)
                                else -> moduleLoader(name)?.let { com.dokar.quickjs.ModuleContent.Source(it) }
                            }
                        }
                    }
                }
                runBlockingEval {
                    // 1) prelude: defines autohost, errors, sel and __autohost_run_main.
                    quickjs.evaluate<Any?>(preludeSource, filename = "prelude.js")
                    // 2) entry as a module: the loader serves the real entry source under the
                    //    reserved name; relative './lib/*.js' imports resolve through the loader.
                    quickjs.evaluate<Any?>(
                        "import * as ns from '$ENTRY_MODULE'; globalThis.__autohost_exports = ns;",
                        filename = "$entryName:reexport",
                        asModule = true,
                    )
                    // 3) invoke main and take the outcome JSON verbatim (contract in JsEngine).
                    val raw = quickjs.evaluate<String>(
                        "JSON.stringify(__autohost_run_main())",
                        filename = "main-invoker",
                    )
                    raw
                }
            } finally {
                stopInterruptWatcher()
                runCatching { quickjs.close() }
                    .onFailure { AhLog.w(TAG, "quickjs close failed: ${it.message}") }
                this.quickjs = null
            }
        }

        override fun close() {
            stopInterruptWatcher()
            quickjs?.let { q -> runCatching { q.close() } }
            quickjs = null
        }

        // --- internals -------------------------------------------------------------------------

        /** Parses the single-arg `[op, payloadJson]` frame and serves it; never throws into JS. */
        private fun serveBridge(
            bridgeFn: (op: String, payload: String) -> String,
            payloadJson: String,
        ): String {
            return try {
                val parsed = Json.parseToJsonElement(payloadJson) as? JsonArray
                val op = (parsed?.getOrNull(0) as? JsonPrimitive)?.content ?: ""
                val payload = (parsed?.getOrNull(1) as? JsonPrimitive)?.content ?: "null"
                bridgeFn(op, payload)
            } catch (e: Exception) {
                kotlinx.serialization.json.buildJsonObject {
                    put("ok", false)
                    put("error", kotlinx.serialization.json.buildJsonObject {
                        put("code", "INTERNAL")
                        put("message", "bridge frame error: ${e.message ?: e.javaClass.simpleName}")
                        put("retryable", false)
                    })
                }.let { Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), it) }
            }
        }

        private fun applyLimits(quickjs: QuickJs) {
            // Owner-verify: expose the caps through whatever runtime/options surface the artifact
            // provides. A missing knob is logged, never thrown — deadline + interrupt still bound
            // the run honestly.
            runCatching {
                val runtime = quickjs.javaClass.methods.firstOrNull { it.name == "getRuntime" }
                    ?.invoke(quickjs) ?: return
                runtime.javaClass.methods.firstOrNull { it.name == "setMemoryLimit" }
                    ?.invoke(runtime, maxMemoryBytes)
                runtime.javaClass.methods.firstOrNull { it.name == "setMaxStackSize" }
                    ?.invoke(runtime, maxStackBytes)
            }.onFailure {
                AhLog.w(TAG, "quickjs limits not applied (binding surface drifted): ${it.message}")
            }
        }

        /** Polls the interrupt check ~5×/s and interrupts the evaluation when it flips true. */
        private fun startInterruptWatcher(quickjs: QuickJs) {
            stopInterruptWatcher()
            val check = interruptCheck ?: return
            val thread = Thread({
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(200)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                    if (runCatching { check() }.getOrDefault(false)) {
                        runCatching { quickjs.interruptEvaluation() }
                        return@Thread
                    }
                }
            }, "autohost-js-interrupt")
            thread.isDaemon = true
            thread.start()
            watcher = thread
        }

        private fun stopInterruptWatcher() {
            watcher?.interrupt()
            watcher = null
        }

        /**
         * The binding's evaluate is suspend; the engine thread blocks here by design. Thread.sleep
         * inside the watcher is the one sanctioned blocking poll (no Clock dependency needed).
         */
        private fun <T> runBlockingEval(block: suspend () -> T): T =
            kotlinx.coroutines.runBlocking { block() }
    }
}
