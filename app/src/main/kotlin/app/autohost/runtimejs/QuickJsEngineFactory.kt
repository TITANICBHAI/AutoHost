package app.autohost.runtimejs

import app.autohost.core.common.AhLog
import app.autohost.runtime.js.JsEngine
import app.autohost.runtime.js.JsEngineFactory
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

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
 *  - setLimits: heap and stack caps use the public QuickJS properties.
 *  - setInterruptHandler: the binding exposes no native interrupt primitive; the check is applied
 *    at host-bridge boundaries and between evaluation stages.
 *  - runEntry: prelude script → entry re-exported as a module (`import * as ns from
 *    "__autohost_entry__"` with statically registered modules from the JsSources loader) → the namespace
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
            val quickjs = QuickJs.create(Dispatchers.IO)
            this.quickjs = quickjs
            return try {
                quickjs.function("__autohost_bridge") { payload: String ->
                    serveBridge(bridgeFn, payload)
                }
                applyLimits(quickjs)
                registerModules(quickjs, entrySource, moduleLoader)
                runBlockingEval {
                    checkInterrupted()
                    // 1) prelude: defines autohost, errors, sel and __autohost_run_main.
                    quickjs.evaluate<Any?>(preludeSource, filename = "prelude.js")
                    checkInterrupted()
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
                    checkInterrupted()
                    raw
                }
            } finally {
                runCatching { quickjs.close() }
                    .onFailure { AhLog.w(TAG, "quickjs close failed: ${it.message}") }
                this.quickjs = null
            }
        }

        override fun close() {
            quickjs?.let { q -> runCatching { q.close() } }
            quickjs = null
        }

        // --- internals -------------------------------------------------------------------------

        private fun registerModules(
            quickjs: QuickJs,
            entrySource: String,
            moduleLoader: ((String) -> String?)?,
        ) {
            quickjs.addModule(ENTRY_MODULE, entrySource)
            if (moduleLoader == null) return

            val visited = mutableSetOf(ENTRY_MODULE)
            val pending = ArrayDeque<Pair<String, String>>()
            pending.addLast(ENTRY_MODULE to entrySource)
            while (pending.isNotEmpty()) {
                val (_, source) = pending.removeFirst()
                for (match in IMPORT_SPECIFIERS.findAll(source)) {
                    val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
                    if (!name.startsWith("./") && !name.startsWith("../")) continue
                    if (!visited.add(name)) continue
                    val imported = moduleLoader(name) ?: continue
                    quickjs.addModule(name, imported)
                    pending.addLast(name to imported)
                }
            }
        }

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
            if (maxMemoryBytes > 0) quickjs.memoryLimit = maxMemoryBytes
            if (maxStackBytes > 0) quickjs.maxStackSize = maxStackBytes
        }

        private fun checkInterrupted() {
            if (interruptCheck?.invoke() == true) {
                throw kotlinx.coroutines.CancellationException("JavaScript run cancelled or timed out")
            }
        }

        /**
         * The binding's evaluate is suspend; the engine thread blocks here by design. Thread.sleep
         * inside the watcher is the one sanctioned blocking poll (no Clock dependency needed).
         */
        private fun <T> runBlockingEval(block: suspend () -> T): T =
            kotlinx.coroutines.runBlocking { block() }

        companion object {
            private val IMPORT_SPECIFIERS = Regex(
                """(?:\bimport\s*(?:[^'"]*?\s+from\s*)?|\bexport\s+[^'"]*?\s+from\s*)['"]([^'"]+)['"]|import\s*\(\s*['"]([^'"]+)['"]\s*\)""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            )
        }
    }
}
