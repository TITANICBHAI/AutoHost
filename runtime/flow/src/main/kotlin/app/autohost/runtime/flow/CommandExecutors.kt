package app.autohost.runtime.flow

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One command executor: runs a step against the scope/exec/state and either completes or throws
 * a typed [FlowFailure]. Top-level type alias (Kotlin forbids type aliases inside objects).
 */
internal typealias CommandExecutor =
    suspend (step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) -> Unit

/**
 * P6-02 + P6-04: one executor per flow command (plan §7.9). Each maps onto wave-1 broker
 * methods; failures are typed with broker error codes only (schemas/errors.json). This file is
 * the Kotlin mirror of the `_x_*` executors in `sdk-python/autohost_ref/flows.py`.
 */
internal object CommandExecutors {
    private val registry: Map<String, CommandExecutor> = mapOf(
        "launchApp" to ::launchApp,
        "stopApp" to ::stopApp,
        "openLink" to ::openLink,
        "tapOn" to ::tapOn,
        "longPressOn" to ::longPressOn,
        "inputText" to ::inputText,
        "eraseText" to ::eraseText,
        "pressKey" to ::pressKey,
        "scroll" to ::scroll,
        "scrollUntilVisible" to ::scrollUntilVisible,
        "swipe" to ::swipe,
        "hideKeyboard" to ::hideKeyboard,
        "waitFor" to ::waitFor,
        "waitUntil" to ::waitUntil,
        "waitForAnimationToEnd" to ::waitForAnimationToEnd,
        "assertVisible" to ::assertVisible,
        "assertNotVisible" to ::assertNotVisible,
        "copyTextFrom" to ::copyTextFrom,
        "setClipboard" to ::setClipboard,
        "takeScreenshot" to ::takeScreenshot,
        "notify" to ::notify,
        "runFlow" to ::runFlow,
        "repeat" to ::repeat,
        "retry" to ::retry,
        "log" to ::log,
        "setResult" to ::setResult,
        "nextRun" to ::nextRun,
        "ask" to ::ask,
        "defineVariables" to ::defineVariables,
    )

    suspend fun execute(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec,
                        state: RunState, label: String) {
        registry.getValue(step.command)(step, scope, ex, state, label)
    }

    // --- arg helpers -----------------------------------------------------------------------

    private fun body(step: app.autohost.core.model.FlowStep): JsonObject = step.body

    private fun argString(step: app.autohost.core.model.FlowStep, key: String, scope: FlowScope,
                          label: String, required: Boolean = true, allowEmpty: Boolean = false,
                          allowSecrets: Boolean = false): String {
        val raw = body(step)[key]
        if (raw == null) {
            if (required) throw FlowFailure("INVALID_ARGS", "$label: missing '$key'", label = label)
            return ""
        }
        if (!JsonValues.isStr(raw)) throw FlowFailure("INVALID_ARGS", "$label: $key must be a string", label = label)
        val resolved = try {
            scope.resolveString(raw, allowSecrets = allowSecrets)
        } catch (missing: SecretMissingException) {
            throw FlowFailure("NOT_FOUND", missing.message!!, label = label)
        }
        if (resolved !is JsonPrimitive || !resolved.isString) {
            throw FlowFailure("INVALID_ARGS", "$label: $key resolved to a non-string", label = label)
        }
        if (!allowEmpty && resolved.content.isEmpty()) {
            throw FlowFailure("INVALID_ARGS", "$label: $key must not be empty", label = label)
        }
        return resolved.content
    }

    private fun argBool(step: app.autohost.core.model.FlowStep, key: String, label: String, default: Boolean = false): Boolean {
        val raw = body(step)[key] ?: return default
        return JsonValues.bool(raw) ?: throw FlowFailure("INVALID_ARGS", "$label: $key must be a boolean", label = label)
    }

    private fun secretText(step: app.autohost.core.model.FlowStep, key: String, scope: FlowScope,
                           label: String, allowSecrets: Boolean): String {
        val raw = body(step).getValue(key)
        val resolved = try {
            scope.resolveString(raw, allowSecrets = allowSecrets)
        } catch (missing: SecretMissingException) {
            throw FlowFailure("NOT_FOUND", missing.message!!, label = label)
        }
        if (resolved !is JsonPrimitive || !resolved.isString) {
            throw FlowFailure("INVALID_ARGS", "$label: text resolved to a non-string", label = label)
        }
        return resolved.content
    }

    private fun selectorWire(step: app.autohost.core.model.FlowStep, scope: FlowScope, label: String): JsonObject =
        SelectorResolver.resolveLabels(FlowParser.toWireSelector(SelectorResolver.selectorPart(body(step)), label), scope, label)

    // --- app commands -----------------------------------------------------------------------

    private suspend fun launchApp(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val pkg = argString(step, "package", scope, label)
        if (argBool(step, "clearState", label)) {
            FlowInterpreter.call(ex, "device.app", "stop", buildJsonObject { put("package", pkg) }, label)
        }
        FlowInterpreter.call(ex, "device.app", "launch", buildJsonObject { put("package", pkg) }, label)
    }

    private suspend fun stopApp(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val pkg = argString(step, "package", scope, label)
        FlowInterpreter.call(ex, "device.app", "stop", buildJsonObject { put("package", pkg) }, label)
    }

    private suspend fun openLink(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val url = argString(step, "url", scope, label)
        val args = buildJsonObject {
            put("uri", url)
            if (body(step)["package"] != null) put("package", argString(step, "package", scope, label))
        }
        FlowInterpreter.call(ex, "device.app", "launch", args, label)
    }

    // --- ui act -----------------------------------------------------------------------------

    private suspend fun tapOn(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val wire = selectorWire(step, scope, label)
        if (argBool(step, "waitToSettle", label)) {
            FlowInterpreter.call(ex, "device.ui", "wait_for_settle", buildJsonObject { }, label)
        }
        val args = buildJsonObject {
            put("target", buildJsonObject { put("selector", wire) })
            put("timeout_ms", FlowInterpreter.timeoutMs(step))
            if (argBool(step, "retryTapIfNoChange", label)) put("retry_if_no_change", true)
        }
        val out = FlowInterpreter.call(ex, "device.ui", "click", args, label, wire)
        if (out["verified"]?.let { JsonValues.bool(it) } != true) {
            throw FlowFailure("TIMEOUT", "tap could not be verified", retryable = true, selector = wire, label = label)
        }
    }

    private suspend fun longPressOn(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val wire = selectorWire(step, scope, label)
        if (argBool(step, "waitToSettle", label)) {
            FlowInterpreter.call(ex, "device.ui", "wait_for_settle", buildJsonObject { }, label)
        }
        FlowInterpreter.call(ex, "device.ui", "long_click", buildJsonObject {
            put("target", buildJsonObject { put("selector", wire) })
        }, label, wire)
    }

    private suspend fun inputText(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val secret = argBool(step, "secret", label)
        val text = secretText(step, "text", scope, label, allowSecrets = secret)
        val wire = SelectorResolver.resolveNested(body(step).getValue("into"), scope, label, "$label: into")
        FlowInterpreter.call(ex, "device.ui", "set_text", buildJsonObject {
            put("target", buildJsonObject { put("selector", wire) })
            put("text", text)
            put("secret", secret)
        }, label, wire)
    }

    private suspend fun eraseText(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val wire = SelectorResolver.resolveNested(body(step).getValue("into"), scope, label, "$label: into")
        FlowInterpreter.call(ex, "device.ui", "set_text", buildJsonObject {
            put("target", buildJsonObject { put("selector", wire) })
            put("text", "")
            put("secret", false)
        }, label, wire)
    }

    private suspend fun pressKey(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        FlowInterpreter.call(ex, "device.ui", "press", buildJsonObject {
            put("key", JsonValues.str(body(step).getValue("key")))
        }, label)
    }

    private suspend fun scroll(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val direction = (body(step)["direction"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "down"
        val w = ex.viewportWidth
        val h = ex.viewportHeight
        val cx = w / 2
        val cy = h / 2
        val (x1, y1, x2, y2) = when (direction) {
            "down" -> listOf(cx, (h * 0.7).toInt(), cx, (h * 0.3).toInt())
            "up" -> listOf(cx, (h * 0.3).toInt(), cx, (h * 0.7).toInt())
            "left" -> listOf((w * 0.8).toInt(), cy, (w * 0.2).toInt(), cy)
            else -> listOf((w * 0.2).toInt(), cy, (w * 0.8).toInt(), cy)
        }
        FlowInterpreter.call(ex, "device.ui", "swipe", buildJsonObject {
            put("x1", x1); put("y1", y1); put("x2", x2); put("y2", y2); put("duration_ms", 300)
        }, label)
    }

    private suspend fun scrollUntilVisible(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val wire = selectorWire(step, scope, label)
        val direction = (body(step)["direction"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "down"
        val out = FlowInterpreter.call(ex, "device.ui", "scroll_until", buildJsonObject {
            put("selector", wire)
            put("direction", direction)
        }, label, wire)
        if (out["found"]?.let { JsonValues.bool(it) } != true) {
            throw FlowFailure("NOT_FOUND", "element never became visible", selector = wire, label = label)
        }
    }

    private suspend fun swipe(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val args = buildJsonObject {
            for (key in listOf("x1", "y1", "x2", "y2")) put(key, JsonValues.int(body(step).getValue(key)))
            if (body(step)["duration"] != null) put("duration_ms", JsonValues.int(body(step).getValue("duration")))
        }
        FlowInterpreter.call(ex, "device.ui", "swipe", args, label)
    }

    private suspend fun hideKeyboard(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        FlowInterpreter.call(ex, "device.ui", "hide_keyboard", buildJsonObject { }, label)
    }

    // --- observe ----------------------------------------------------------------------------

    private suspend fun waitFor(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val wire = selectorWire(step, scope, label)
        val out = FlowInterpreter.call(ex, "device.ui", "wait_for", buildJsonObject {
            put("selector", wire)
            put("timeout_ms", FlowInterpreter.timeoutMs(step))
        }, label, wire)
        if (out["node"] is JsonNull || out["node"] == null) {
            throw FlowFailure("TIMEOUT", "element did not appear in time", retryable = true, selector = wire, label = label)
        }
    }

    private suspend fun waitUntil(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val key = if ("visible" in body(step)) "visible" else "notVisible"
        val wire = SelectorResolver.resolveNested(body(step).getValue(key), scope, label, "$label: $key")
        val out = FlowInterpreter.call(ex, "device.ui", "wait_for", buildJsonObject {
            put("selector", wire)
            put("timeout_ms", FlowInterpreter.timeoutMs(step))
        }, label, wire)
        val appeared = out["node"] !is JsonNull && out["node"] != null
        if ((key == "visible") == appeared) return
        val message = if (key == "visible") "element did not appear in time" else "element is still visible"
        throw FlowFailure("TIMEOUT", message, retryable = true, selector = wire, label = label)
    }

    private suspend fun waitForAnimationToEnd(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val out = FlowInterpreter.call(ex, "device.ui", "wait_for_settle", buildJsonObject {
            put("timeout_ms", FlowInterpreter.timeoutMs(step))
        }, label)
        if (out["settled"]?.let { JsonValues.bool(it) } != true) {
            throw FlowFailure("TIMEOUT", "animation did not settle in time", retryable = true, label = label)
        }
    }

    private suspend fun assertVisible(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val wire = selectorWire(step, scope, label)
        if (FlowInterpreter.find(ex, wire, 1, label).isEmpty()) {
            throw FlowFailure("NOT_FOUND", "no element matches the selector", selector = wire, label = label)
        }
    }

    private suspend fun assertNotVisible(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val wire = selectorWire(step, scope, label)
        if (FlowInterpreter.find(ex, wire, 1, label).isNotEmpty()) {
            throw FlowFailure("NOT_FOUND", "element is visible but should not be", selector = wire, label = label)
        }
    }

    private suspend fun copyTextFrom(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val wire = selectorWire(step, scope, label)
        val nodes = FlowInterpreter.find(ex, wire, 2, label)
        if (nodes.isEmpty()) {
            throw FlowFailure("NOT_FOUND", "no element matches the selector", selector = wire, label = label)
        }
        if (nodes.size > 1) {
            throw FlowFailure("INVALID_ARGS", "selector matched ${nodes.size} nodes; copyTextFrom needs exactly one",
                selector = wire, label = label)
        }
        val node = nodes[0] as JsonObject
        val text = (node["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: (node["desc"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw FlowFailure("NOT_FOUND", "node carries no text", selector = wire, label = label)
        scope.env[JsonValues.str(body(step).getValue("into"))] = JsonPrimitive(text)
    }

    // --- data, notifications, prompts -------------------------------------------------------

    private suspend fun setClipboard(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val secret = argBool(step, "secret", label)
        val text = secretText(step, "text", scope, label, allowSecrets = secret)
        FlowInterpreter.call(ex, "device.ime", "clipboard_set", buildJsonObject { put("text", text) }, label)
    }

    private suspend fun takeScreenshot(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val out = FlowInterpreter.call(ex, "device.screenshot", "capture", buildJsonObject { }, label)
        val into = (body(step)["into"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val path = (out["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (into != null && path != null) scope.env[into] = JsonPrimitive(path)
    }

    private suspend fun notify(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val title = argString(step, "title", scope, label)
        val text = argString(step, "text", scope, label, allowEmpty = true)
        FlowInterpreter.call(ex, "notify.post", "post", buildJsonObject {
            put("title", title); put("text", text)
        }, label)
    }

    private suspend fun ask(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val raw = body(step)
        val kind = (raw["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "confirm"
        val args = buildJsonObject {
            put("title", argString(step, "title", scope, label))
            put("message", argString(step, "message", scope, label))
            put("kind", kind)
            if (kind == "choice") put("choices", raw.getValue("choices"))
            if (step.timeoutMs != null) put("timeout_ms", FlowInterpreter.timeoutMs(step))
        }
        val out = FlowInterpreter.call(ex, "ui.prompt", "ask", args, label)
        if (kind == "confirm") {
            val confirmed = out["confirmed"]?.let { JsonValues.bool(it) } == true
            val into = (raw["into"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (into != null) scope.env[into] = JsonPrimitive(confirmed)
            if (!confirmed) throw FlowWaitUserException("$label: prompt was not answered")
            return
        }
        val answer = (out["answer"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (answer == null) throw FlowWaitUserException("$label: prompt was not answered")
        scope.env[JsonValues.str(raw.getValue("into"))] = JsonPrimitive(answer)
    }

    // --- bookkeeping ------------------------------------------------------------------------

    private suspend fun log(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val message = argString(step, "message", scope, label, allowEmpty = true)
        val level = (body(step)["level"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "info"
        ex.emit(buildJsonObject {
            put("type", "log")
            put("level", level)
            put("message", scope.redact(message))
        })
    }

    private suspend fun setResult(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val code = argString(step, "code", scope, label)
        val data = scope.resolveValue(body(step)["data"] ?: buildJsonObject { })
        state.result = code to (data as? JsonObject ?: JsonObject(emptyMap()))
    }

    private suspend fun nextRun(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val after = buildJsonObject { put("afterMinutes", JsonValues.int(body(step).getValue("afterMinutes"))) }
        state.nextRun = after
        ex.emit(buildJsonObject {
            put("type", "next_run")
            put("data", after)
        })
    }

    private suspend fun defineVariables(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        for ((name, value) in body(step)) {
            scope.env[name] = scope.resolveValue(value)
        }
    }

    // --- control flow (P6-04) ---------------------------------------------------------------

    private suspend fun runFlow(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val name = argString(step, "file", scope, label)
        val doc = ex.subflows[name]
            ?: throw FlowFailure("INVALID_ARGS", "subflow '$name' is not registered", label = label)
        if (state.depth + 1 > FlowInterpreter.MAX_FLOW_DEPTH) {
            throw FlowFailure("INVALID_ARGS", "flow nesting deeper than ${FlowInterpreter.MAX_FLOW_DEPTH}", label = label)
        }
        val childScope = scope.child(emptyMap())
        doc.env.forEach { (k, v) -> childScope.env[k] = v }
        body(step)["env"]?.let { overlay ->
            (overlay as? JsonObject)?.forEach { (k, v) -> childScope.env[k] = scope.resolveValue(v) }
        }
        state.depth += 1
        try {
            FlowInterpreter.runSteps(doc.onFlowStart, childScope, ex, state)
            FlowInterpreter.runSteps(doc.steps, childScope, ex, state)
            FlowInterpreter.runSteps(doc.onFlowComplete, childScope, ex, state)
        } finally {
            state.depth -= 1
        }
    }

    private suspend fun repeat(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val steps = step.steps
        val times = step.times
        if (times != null) {
            repeat(times) {
                ex.checkCancel()
                for (inner in steps) FlowInterpreter.runStep(inner, scope, ex, state)
            }
            return
        }
        var iterations = 0
        while (true) {
            val expr = step.whileExpr ?: throw FlowFailure("INVALID_ARGS", "$label: while missing", label = label)
            val value = try {
                ExprEvaluator.eval(expr, scope)
            } catch (error: ExprError) {
                throw FlowFailure("INVALID_ARGS", "expression failed: ${error.message}", label = label)
            }
            when (JsonValues.bool(value)) {
                false -> return
                null -> throw FlowFailure(
                    "INVALID_ARGS",
                    "while must evaluate to true/false, got ${ExprEvaluator.render(value)}",
                    label = label,
                )
                else -> Unit
            }
            iterations += 1
            if (iterations > FlowInterpreter.MAX_WHILE_ITERATIONS) {
                throw FlowFailure("INVALID_ARGS", "while exceeded ${FlowInterpreter.MAX_WHILE_ITERATIONS} iterations", label = label)
            }
            ex.checkCancel()
            for (inner in steps) FlowInterpreter.runStep(inner, scope, ex, state)
        }
    }

    private suspend fun retry(step: app.autohost.core.model.FlowStep, scope: FlowScope, ex: FlowExec, state: RunState, label: String) {
        val steps = step.steps
        val times = step.times ?: 0
        var attempt = 0
        while (true) {
            ex.checkCancel()
            try {
                for (inner in steps) FlowInterpreter.runStep(inner, scope, ex, state)
                return
            } catch (failure: FlowFailure) {
                if (!failure.retryable || attempt >= times) throw failure
                ex.sleep(step.backoffMs)
                attempt += 1
            }
        }
    }
}
