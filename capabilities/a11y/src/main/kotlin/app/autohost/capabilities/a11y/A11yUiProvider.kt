package app.autohost.capabilities.a11y

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.common.AhJson
import app.autohost.core.common.AhResult
import app.autohost.core.common.Clock
import app.autohost.core.model.AhError
import app.autohost.core.model.Availability
import app.autohost.core.model.CompactDump
import app.autohost.core.model.DumpFilter
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import app.autohost.core.model.Meta
import app.autohost.core.model.Selector
import app.autohost.core.model.SelectorEngine
import app.autohost.core.model.UiNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Text entry behind the IME (implemented by :capabilities:ime; wired in AppGraph). Outcomes are
 *  `textInputOutcomes` values from schemas/errors.json. */
interface TextEntryBackend {
    fun available(): Boolean
    fun commit(text: String, clearFirst: Boolean): String
}

/** Clipboard behind the IME (background clipboard needs the IME session on API 29+). */
interface ClipboardAccess {
    fun set(text: String): Boolean
    fun clear(): Boolean
}

/**
 * The hardened `device.ui` provider (Phase 3, plan §5.7 + §7.6). Reads go through the [ReadGate] when wired;
 * actions are guarded by the [PopupGuard]; the click chain re-resolves stale handles; `set_text` runs the
 * strategy chain (ACTION_SET_TEXT → IME commit → paste) with the canary rule for secrets; `wait_for` is
 * event-driven with a polling fallback; `click` verifies `expect=` selectors and attaches evidence on failure.
 *
 * Scope rule (unchanged from wave 1): reads are filtered to the intersection of the granted packages and an
 * explicit `scope` argument — an explicit scope can only narrow, never widen (fail closed).
 */
class A11yUiProvider(
    private val reader: A11yReader,
    private val registry: NodeRegistry,
    private val actions: UiActions,
    private val windowChanges: WindowChangeSource,
    private val clock: Clock,
    private val readGate: ReadGate? = null,
    private val imeBackend: TextEntryBackend? = null,
    private val clipboard: ClipboardAccess? = null,
    private val popupMode: () -> PopupGuardPolicy.Mode = { PopupGuardPolicy.Mode.STOP },
    private val changeEvents: Flow<Long>? = null,
    private val screenWidth: Int = 1080,
    private val screenHeight: Int = 2400,
    private val connected: () -> Boolean = { A11yBridge.connected },
    private val pollIntervalMs: Long = 50,
) : CapabilityProvider {

    override val capabilityId: String = "device.ui"
    override val providerId: String = "a11y.hardened"
    override val tier: String = "T1"

    override fun availability(env: DeviceEnv): Availability =
        A11yAvailability.current(connected())

    override fun permissionFor(method: String, args: JsonObject): String? = when (method) {
        "snapshot", "find", "wait_for", "wait_for_settle", "is_window_updating", "dump", "keyboard_visible" ->
            "device.ui.observe"
        "click", "long_click", "set_text", "scroll_until", "swipe", "tap", "press", "hide_keyboard" ->
            "device.ui.act"
        else -> null
    }

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        val observePackages = ctx.scopePackages("device.ui.observe")
        val actPackages = ctx.scopePackages("device.ui.act")
        // P12-16 (A7): refs are RUN-SCOPED — every put/resolve carries the run id, so run B can
        // never act on run A's handles (one registry is shared by all runs).
        val runScope = ctx.runId
        // P12-16 (A8): the spec scope flags bind whenever the act grant is explicit (absent = false).
        val allowCoordinates = ctx.scopeFlag("device.ui.act", "allow_coordinates")
        val allowGlobal = ctx.scopeFlag("device.ui.act", "allow_global")
        return when (method) {
            "snapshot" -> snapshot(args, observePackages, runScope)
            "find" -> find(args, observePackages, timeoutMs = 0, runScope = runScope)
            "wait_for" -> find(args, observePackages, timeoutMs = longArg(args, "timeout_ms", 10_000), runScope = runScope).let { env ->
                // Parity with the simulator: wait_for resolves to {node: firstOrNull?} — a timeout
                // yields node:null (200), never an error envelope.
                if (!env.ok && env.error?.reason == "NO_MATCH") {
                    Envelope.ok(buildJsonObject { put("node", kotlinx.serialization.json.JsonNull) })
                } else if (!env.ok) env
                else Envelope.ok(buildJsonObject {
                    val nodes = env.result!!.jsonObject["nodes"] as? JsonArray
                    put("node", nodes?.firstOrNull() ?: kotlinx.serialization.json.JsonNull)
                })
            }
            "wait_for_settle" -> waitForSettle(args)
            "is_window_updating" -> Envelope.ok(
                buildJsonObject { put("updating", isWindowUpdating()) },
            )
            "dump" -> dump(args, observePackages)
            "keyboard_visible" -> Envelope.ok(buildJsonObject { put("visible", keyboardState(observePackages).visible) })
            "click" -> click(ctx, args, actPackages, observePackages, longClick = false)
            "long_click" -> click(ctx, args, actPackages, observePackages, longClick = true)
            "set_text" -> setText(ctx, args, actPackages, observePackages)
            "scroll_until" -> scrollUntil(ctx, args, actPackages, observePackages)
            "swipe" -> swipe(args, allowCoordinates)
            "tap" -> tap(args, allowCoordinates)
            "press" -> press(args, allowGlobal)
            "hide_keyboard" -> hideKeyboard(args, observePackages)
            else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.ui method '$method'")
        }
    }

    /** P12-16 (A8): the coordinate tools are gated behind `allow_coordinates` (absent = false). */
    private fun coordinateGate(flag: Boolean?): Envelope? = when (flag) {
        false -> Envelope.err(
            ErrorCode.SCOPE_VIOLATION,
            "this run's device.ui.act grant does not include allow_coordinates (tap/swipe need it)",
            reason = "SCOPE_NOT_GRANTED",
        )
        else -> null // true, or null = unrestricted (dev grant-all keeps the pre-ADR-098 behaviour)
    }

    /** P12-16 (A8): `back` stays always allowed (AGENT_RUNTIME §6); the rest need `allow_global`. */
    private fun globalGate(key: String?, flag: Boolean?): Envelope? {
        if (key == "back") return null
        return when (flag) {
            false -> Envelope.err(
                ErrorCode.SCOPE_VIOLATION,
                "this run's device.ui.act grant does not include allow_global (press home/recents/notifications need it)",
                reason = "SCOPE_NOT_GRANTED",
            )
            else -> null
        }
    }

    // --- reads (P3-05 / P3-06 / P3-17) ------------------------------------------------------------------------

    /** Read key: one gate slot per distinct scope set so concurrent runs do not thrash each other. */
    private fun gateKey(packages: Set<String>?): String = packages?.sorted()?.joinToString(",") ?: "*"

    private suspend fun read(packages: Set<String>?): Pair<A11ySnapshot, Meta?> {
        val gate = readGate ?: return reader.read(packages) to null
        return when (val result = gate.read(gateKey(packages)) { reader.read(packages) }) {
            is ReadGate.ReadResult.Fresh -> result.snapshot to null
            is ReadGate.ReadResult.Degraded ->
                if (result.snapshot != null) {
                    result.snapshot to Meta(
                        degraded = true,
                        degradedReason = result.reason.name,
                        snapshotAgeMs = result.snapshotAgeMs,
                    )
                } else {
                    throw ReadBudgetExhausted(result.detail)
                }
        }
    }

    private class ReadBudgetExhausted(val detail: String) : Exception(detail)

    private suspend fun snapshot(args: JsonObject, packages: Set<String>?, runScope: String?): Envelope {
        val requested = requestedPackages(args, packages)
        val (snap, meta) = try {
            read(requested)
        } catch (e: ReadBudgetExhausted) {
            return Envelope.err(ErrorCode.TIMEOUT, "UI read did not finish in the read budget: ${e.detail}")
        }
        if (snap.emptyReason == "NO_ACTIVE_WINDOW") {
            return Envelope.err(
                ErrorCode.NO_ACTIVE_WINDOW,
                "the accessibility service sees no active window (device frozen or service restarting)",
            )
        }
        val refs = snap.nodes.map { node -> registry.put(node, snap.infoAt(node.index), runScope) }
        val compact = CompactDump.render(
            nodes = snap.nodes,
            pkg = snap.pkg ?: "",
            keyboardVisible = snap.keyboardVisible,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
        return Envelope.ok(
            buildJsonObject {
                put("ts", snap.tsMs)
                put("package", snap.pkg)
                put("keyboard", snap.keyboardVisible)
                put("node_count", snap.nodes.size)
                put("empty", snap.nodes.isEmpty())
                if (snap.emptyReason != null) put("empty_reason", snap.emptyReason)
                put("degraded", snap.truncated || (meta?.degraded == true))
                if (snap.truncated) put("degraded_reason", "TREE_TRUNCATED")
                if (meta?.degraded == true) {
                    put("degraded_reason", meta.degradedReason ?: "DEGRADED")
                    meta.snapshotAgeMs?.let { put("snapshot_age_ms", it) }
                }
                putJsonArray("nodes") {
                    snap.nodes.forEachIndexed { index, node -> add(node.summary(refs[index])) }
                }
                put("compact", compact)
            },
            meta = meta,
        )
    }

    private suspend fun find(
        args: JsonObject,
        packages: Set<String>?,
        timeoutMs: Long,
        runScope: String? = null,
    ): Envelope {
        val selector = parseSelector(args) ?: return Envelope.err(
            ErrorCode.INVALID_ARGS, "find/wait_for need a 'selector' argument",
        )
        val requested = requestedPackages(args, packages)
        val deadline = clock.monotonicMs() + timeoutMs
        var backoff = pollIntervalMs
        while (true) {
            val (snap, _) = try {
                read(requested)
            } catch (e: ReadBudgetExhausted) {
                return Envelope.err(ErrorCode.TIMEOUT, "UI read did not finish in the read budget: ${e.detail}")
            }
            // P12-02 (A6b resolved): the parity shape — {nodes:[NodeSummary]}, each node carrying
            // its own ref (the simulator has spoken this shape since P6; ADR-077 host parity).
            val matches = SelectorEngine.findAll(snap.nodes, selector)
            if (matches.isNotEmpty()) {
                val refs = matches.map { registry.put(it, snap.infoAt(it.index), runScope) }
                return Envelope.ok(
                    buildJsonObject {
                        put("nodes", JsonArray(matches.zip(refs).map { (node, ref) -> node.summary(ref) }))
                    },
                )
            }
            if (clock.monotonicMs() >= deadline) {
                return Envelope.err(ErrorCode.NOT_FOUND, "no element matched the selector", reason = "NO_MATCH")
            }
            awaitChangeOrDelay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** Event-driven wait: wake on the next window change, fall back to polling (plan §5.7 "waits"). */
    private suspend fun awaitChangeOrDelay(backoffMs: Long) {
        val events = changeEvents
        if (events != null && withTimeoutOrNull(backoffMs) { events.first() } != null) return
        delay(backoffMs)
    }

    private suspend fun waitForSettle(args: JsonObject): Envelope {
        val quietMs = longArg(args, "quiet_ms", 500)
        val timeoutMs = longArg(args, "timeout_ms", 10_000)
        val started = clock.monotonicMs()
        while (true) {
            val elapsed = clock.monotonicMs() - started
            when (val decision = SettlePolicy.evaluate(
                quietMs = quietMs,
                timeoutMs = timeoutMs,
                elapsedMs = elapsed,
                lastChangedMs = windowChanges.lastChangeMs(),
                nowMs = clock.nowMs(),
            )) {
                is SettlePolicy.Decision.Settled ->
                    return Envelope.ok(buildJsonObject { put("settled", true); put("waited_ms", elapsed) })
                is SettlePolicy.Decision.TimedOut ->
                    return Envelope.err(ErrorCode.TIMEOUT, "the UI never settled within the timeout", reason = "UI_BUSY")
                is SettlePolicy.Decision.KeepWaiting -> awaitChangeOrDelay(pollIntervalMs)
            }
        }
    }

    private fun isWindowUpdating(): Boolean =
        clock.nowMs() - windowChanges.lastChangeMs() < WINDOW_UPDATING_WINDOW_MS

    private fun keyboardState(packages: Set<String>?): KeyboardState.State {
        val snap = reader.read(packages)
        val focused = snap.nodes.firstOrNull { it.has("focused") }
        val byWindows = KeyboardState.evaluate(
            windows = snap.windows,
            focusedId = focused?.id?.ifEmpty { null },
            focusedEditable = focused?.has("editable") == true,
        )
        // The reader's direct IME observation wins; the window-list heuristic is the fallback
        // (a snapshot may carry keyboardVisible without an input_method window entry).
        return if (snap.keyboardVisible) byWindows.copy(visible = true) else byWindows
    }

    private fun dump(args: JsonObject, packages: Set<String>?): Envelope {
        val requested = requestedPackages(args, packages)
        val filtering = (args["filter"] as? JsonPrimitive)?.content?.toBooleanStrict() ?: true
        val normalized = (args["normalized"] as? JsonPrimitive)?.content?.toBooleanStrict() ?: false
        val snap = reader.read(requested)
        val text = CompactDump.render(
            nodes = snap.nodes,
            pkg = snap.pkg ?: "",
            keyboardVisible = snap.keyboardVisible,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            filter = if (filtering) DumpFilter() else null,
            coordinates = if (normalized) CompactDump.Coordinates.NORMALIZED else CompactDump.Coordinates.ABSOLUTE,
        )
        return Envelope.ok(buildJsonObject { put("text", text) })
    }

    // --- act (P3-09 / P3-10 / P3-12 / P3-18) -------------------------------------------------------------------

    private suspend fun click(
        ctx: CallContext,
        args: JsonObject,
        actPackages: Set<String>?,
        observePackages: Set<String>?,
        longClick: Boolean,
    ): Envelope {
        val guardResult = guardAndResolve(args, actPackages, observePackages, ctx.runId)
        val info = when (guardResult) {
            is AhResult.Ok -> guardResult.value
            is AhResult.Err -> return mapResolveError(guardResult.error, "click target not found")
        }
        // P12-03 (A2/RM-A4): the nodes are captured BEFORE the click so the effect is computed
        // by the state-aware ChangeDetector (checked/selected/encluded included, focused excluded).
        val nodesBefore = nodesOfCurrent(observePackages)
        // P12-07 (A3/RM-A2): the chain is AWAITED — a refused/hung coordinate stroke reports
        // NOT_DISPATCHED instead of "dispatchGesture returned true".
        val outcome = if (longClick) actions.awaitedLongClick(info) else actions.awaitedClick(info)
        // P12-04 (A2): the effect verification happens FIRST (settle happens in the caller's next
        // observation); `retry_if_no_change` then repeats the accepted action ONCE when the screen
        // did not change — and re-observes, never falling through to another channel after
        // `unchanged` (ACTION_HARDENING §2).
        val retryFlag = (args["retry_if_no_change"] as? JsonPrimitive)?.content?.toBooleanStrict() ?: false
        if (retryFlag && outcome.verified &&
            ChangeDetector.detect(nodesBefore, nodesOfCurrent(observePackages)) == ChangeDetector.Change.Unchanged
        ) {
            // One repeat with the same handle is the documented single retry; a second miss is
            // reported, never looped.
            if (longClick) actions.awaitedLongClick(info) else actions.awaitedClick(info)
        }
        val verifiedByExpect = verifyExpect(args, observePackages)
        if (verifiedByExpect != null) return verifiedByExpect
        // P12-01 (A6): the result speaks the SPEC's vocabulary, not the internal Route enum —
        // NODE→NODE_ACTION, PARENT→ANCESTOR_ACTION, COORDINATE→COORDINATE. A null route means the
        // action was never dispatched: a typed error, never a "method" value (frozen spec).
        val method = when (outcome.route) {
            NodeActions.Route.NODE -> "NODE_ACTION"
            NodeActions.Route.PARENT -> "ANCESTOR_ACTION"
            NodeActions.Route.COORDINATE -> "COORDINATE"
            null -> return Envelope.err(
                ErrorCode.CAPABILITY_UNAVAILABLE,
                "the click could not be dispatched (the node action, the ancestor action and the gesture were all refused)",
                reason = "NOT_DISPATCHED",
            )
        }
        // P12-03/04 (ACTION_HARDENING §2, RM-A4): the additive effect — a THREE-VALUED signal
        // from the state-aware ChangeDetector (null on either side = Unverifiable, never silently
        // Changed). `verified` keeps its "accepted" meaning (P12-04's settle-then-verify refines).
        val nodesAfter = nodesOfCurrent(observePackages)
        val effect = when (ChangeDetector.detect(nodesBefore, nodesAfter)) {
            ChangeDetector.Change.Changed -> "changed"
            ChangeDetector.Change.Unchanged -> "unchanged"
            ChangeDetector.Change.Unverifiable -> "unverifiable"
        }
        val warnings = ArrayList<String>()
        if (effect == "unchanged") warnings.add("no observable change after the click")
        return Envelope.ok(
            buildJsonObject {
                put("method", method)
                put("verified", outcome.verified)
                put("effect", effect)
                if (warnings.isNotEmpty()) put("warnings", JsonArray(warnings.map { JsonPrimitive(it) }))
            },
        )
    }

    /** Resolves the target and applies the popup guard; returns the resolved node handle or an error envelope. */
    private suspend fun guardAndResolve(
        args: JsonObject,
        actPackages: Set<String>?,
        observePackages: Set<String>?,
        runScope: String?,
    ): AhResult<android.view.accessibility.AccessibilityNodeInfo?> {
        val resolved = resolveTarget(args, actPackages, observePackages, runScope)
        val info = when (resolved) {
            is AhResult.Ok -> resolved.value
            is AhResult.Err -> return AhResult.err(resolved.error)
        }
        // Popup guard: a higher-layer window above the target's window refuses blind taps (plan §5.7).
        val targetWindowId = info?.let { UiTreeBuilder.safeWindowId(it) } ?: -1
        if (targetWindowId >= 0) {
            val windows = reader.windows()
            val snap = lastSnapshotFor(observePackages)
            val mode = popupMode()
            val decision = PopupGuard.evaluate(
                targetWindowId = targetWindowId,
                windows = windows,
                visibleIdsOf = { windowId ->
                    val nodes = snap?.nodes?.filterIndexed { i, _ -> snap.windowIds.getOrNull(i) == windowId } ?: emptyList()
                    Pair(nodes.mapNotNull { it.id }.toSet(), nodes.map { it.text + it.desc }.filter { it.isNotBlank() }.toSet())
                },
                mode = mode,
            )
            when (decision) {
                is PopupGuardPolicy.Decision.Obscured -> return AhResult.err(
                    AhError.of(ErrorCode.OBSCURED_BY_WINDOW, decision.detail),
                )
                is PopupGuardPolicy.Decision.Dismiss -> {
                    val dismissed = dismissKnownDialog(decision, observePackages)
                    if (!dismissed) {
                        return AhResult.err(
                            AhError.of(
                                ErrorCode.OBSCURED_BY_WINDOW,
                                "a known dialog is in the way but could not be dismissed (is its package inside the granted scope?)",
                            ),
                        )
                    }
                }
                PopupGuardPolicy.Decision.Clear -> Unit
            }
        }
        return resolved
    }

    /** Clicks the affirmative control of a known dialog found in the last snapshot (scope-honouring). */
    private suspend fun dismissKnownDialog(decision: PopupGuardPolicy.Decision.Dismiss, packages: Set<String>?): Boolean {
        val snap = lastSnapshotFor(packages) ?: return false
        val suffixes = decision.dialog.ids.map { it.substringAfterLast('/') }
        val target = snap.nodes.firstOrNull { n ->
            n.pkg.startsWith("com.android") &&
                (n.id.isNotEmpty() && suffixes.any { n.id.endsWith(it) } ||
                    decision.dialog.labels.any { l -> (n.text + n.desc).contains(l, ignoreCase = true) })
        } ?: return false
        val info = snap.infoAt(target.index)
            ?: registry.resolve(registry.put(target, null, null), null).getOrNull()
            ?: return false
        return try {
            info.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun lastSnapshotFor(packages: Set<String>?): A11ySnapshot? = try {
        reader.read(packages)
    } catch (e: RuntimeException) {
        null
    }

    private fun nodesOfCurrent(packages: Set<String>?): List<app.autohost.core.model.UiNode>? = try {
        reader.read(packages).nodes
    } catch (e: RuntimeException) {
        null
    }

    private fun changed(before: List<app.autohost.core.model.UiNode>?, packages: Set<String>?): Boolean {
        val now = nodesOfCurrent(packages) ?: return true // unreadable state counts as changed (fail open for retry only)
        return ChangeDetector.detect(before, now) != ChangeDetector.Change.Unchanged
    }

    /**
     * `expect=` verification (plan §7.6): "click succeeded" means "the expected result appeared". A coordinate
     * click alone is never proof. Returns an error envelope when the expectation was not met, null otherwise.
     */
    private suspend fun verifyExpect(args: JsonObject, packages: Set<String>?): Envelope? {
        val expect = args["expect"] ?: return null
        val selector = try {
            AhJson.json.decodeFromJsonElement(Selector.serializer(), expect.jsonObject)
        } catch (e: Exception) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "expect must be a selector object")
        }
        val timeoutMs = longArg(args, "timeout_ms", 10_000)
        val deadline = clock.monotonicMs() + timeoutMs
        var backoff = pollIntervalMs
        while (true) {
            val (snap, _) = read(packages)
            if (SelectorEngine.findFirst(snap.nodes, selector) != null) {
                return null // expectation met; the caller's ok-envelope stands
            }
            if (clock.monotonicMs() >= deadline) {
                val dump = CompactDump.render(nodes = snap.nodes, pkg = snap.pkg ?: "", keyboardVisible = snap.keyboardVisible)
                return Envelope.err(
                    AhError.of(
                        ErrorCode.TIMEOUT,
                        "the expected state never appeared after the click",
                        reason = "UNKNOWN_SCREEN",
                        details = buildJsonObject {
                            putJsonObject("evidence") {
                                put("kind", "ui_dump")
                                put("compact", dump)
                                put("package", snap.pkg)
                            }
                        },
                    ),
                )
            }
            awaitChangeOrDelay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private suspend fun setText(ctx: CallContext, args: JsonObject, actPackages: Set<String>?, observePackages: Set<String>?): Envelope {
        val text = args["text"]?.let { it as? JsonPrimitive }?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "set_text needs a 'text' argument")
        val secret = (args["secret"] as? JsonPrimitive)?.content?.toBooleanStrict() ?: false
        val info = when (val result = guardAndResolve(args, actPackages, observePackages, ctx.runId)) {
            is AhResult.Ok -> result.value
            is AhResult.Err -> return mapResolveError(result.error, "set_text target not found")
        }
        // Strategy chain (plan §7.6): ACTION_SET_TEXT → IME commit → paste. First VERIFIED wins; unverifiable
        // outcomes are reported, never assumed. Secrets never touch the clipboard (canary rule).
        val attempts = ArrayList<SetTextChain.Attempt>()
        var loopGuard = 0
        while (loopGuard++ < 8) {
            when (val decision = SetTextChain.next(attempts, secret, imeBackend?.available() == true)) {
                is SetTextChain.Decision.Next -> {
                    val outcome = when (decision.strategy) {
                        SetTextChain.Strategy.SET_TEXT -> {
                            val applied = actions.setText(info, text)
                            when {
                                applied -> SetTextChain.VERIFIED
                                else -> SetTextChain.REJECTED
                            }
                        }
                        SetTextChain.Strategy.IME_COMMIT ->
                            imeBackend?.commit(text, clearFirst = true) ?: SetTextChain.COMMIT_OUTCOME_UNKNOWN
                        SetTextChain.Strategy.CLIPBOARD_PASTE -> {
                            val stored = clipboard?.set(text) == true
                            if (!stored) SetTextChain.REJECTED else if (actions.paste(info)) SetTextChain.ACCEPTED_UNVERIFIED else SetTextChain.REJECTED
                        }
                    }
                    attempts.add(SetTextChain.Attempt(decision.strategy, outcome))
                }
                is SetTextChain.Decision.Done -> {
                    return Envelope.ok(
                        buildJsonObject {
                            put("outcome", decision.attempt.outcome)
                            put("strategy", decision.attempt.strategy.name.lowercase())
                            put("verified", decision.attempt.outcome == SetTextChain.VERIFIED)
                        },
                    )
                }
                is SetTextChain.Decision.Unverified -> {
                    return Envelope.err(
                        ErrorCode.TEXT_INPUT_UNVERIFIED,
                        "the text was submitted but the field did not confirm it",
                        reason = "UNVERIFIED_INPUT",
                    )
                }
                is SetTextChain.Decision.Rejected -> {
                    return Envelope.err(
                        ErrorCode.TEXT_INPUT_REJECTED,
                        "the field did not accept the text",
                        reason = "FIELD_REJECTED",
                    )
                }
            }
        }
        return Envelope.err(ErrorCode.INTERNAL, "set_text chain did not converge")
    }

    private suspend fun scrollUntil(
        ctx: CallContext,
        args: JsonObject,
        actPackages: Set<String>?,
        observePackages: Set<String>?,
    ): Envelope {
        val selector = parseSelector(args) ?: return Envelope.err(
            ErrorCode.INVALID_ARGS, "scroll_until needs a 'selector' argument",
        )
        val direction = args["direction"]?.let { it as? JsonPrimitive }?.content ?: "down"
        if (direction !in setOf("up", "down", "left", "right")) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "direction must be up, down, left or right")
        }
        val requested = requestedPackages(args, observePackages)
        val maxSwipes = longArg(args, "max_swipes", 10)
        repeat(maxSwipes.toInt()) {
            val (snap, _) = try {
                read(requested)
            } catch (e: ReadBudgetExhausted) {
                return Envelope.err(ErrorCode.TIMEOUT, "UI read did not finish in the read budget: ${e.detail}")
            }
            val match = SelectorEngine.findFirst(snap.nodes, selector)
            if (match != null) {
                val ref = registry.put(match, snap.infoAt(match.index), ctx.runId)
                return Envelope.ok(
                    buildJsonObject {
                        put("found", true)
                        put("node", match.summary(ref)) // the ref rides inside the node summary (parity)
                    },
                )
            }
            val (cx, cy) = screenWidth / 2f to screenHeight / 2f
            val distance = if (direction == "up" || direction == "down") screenHeight * 0.45f else screenWidth * 0.45f
            val (x1, y1, x2, y2) = when (direction) {
                "down" -> listOf(cx, cy + distance / 2, cx, cy - distance / 2) // content moves up
                "up" -> listOf(cx, cy - distance / 2, cx, cy + distance / 2)
                "right" -> listOf(cx + distance / 2, cy, cx - distance / 2, cy)
                else -> listOf(cx - distance / 2, cy, cx + distance / 2, cy)
            }
            actions.swipe(x1, y1, x2, y2, SWIPE_DURATION_MS)
            delay(SCROLL_SETTLE_MS)
        }
        return Envelope.err(ErrorCode.NOT_FOUND, "element never appeared within $maxSwipes swipes", reason = "NO_MATCH")
    }

    private fun swipe(args: JsonObject, allowCoordinates: Boolean?): Envelope {
        val x1 = floatArg(args, "x1") ?: return invalidSwipe(args)
        val y1 = floatArg(args, "y1") ?: return invalidSwipe(args)
        val x2 = floatArg(args, "x2") ?: return invalidSwipe(args)
        val y2 = floatArg(args, "y2") ?: return invalidSwipe(args)
        // the flag gate rides AFTER arg validation (validate → policy, the pipeline's order)
        coordinateGate(allowCoordinates)?.let { return it }
        val durationMs = longArg(args, "duration_ms", 300)
        val dispatched = actions.swipe(x1, y1, x2, y2, durationMs)
        // P12-01: the frozen spec promises {method:'COORDINATE'} (refused gesture = typed error).
        if (!dispatched) {
            return Envelope.err(
                ErrorCode.CAPABILITY_UNAVAILABLE,
                "the swipe gesture was refused by the OS",
                reason = "NOT_DISPATCHED",
            )
        }
        return Envelope.ok(buildJsonObject { put("method", "COORDINATE") })
    }

    private fun invalidSwipe(args: JsonObject): Envelope =
        Envelope.err(ErrorCode.INVALID_ARGS, "swipe needs x1, y1, x2, y2 (numbers)")

    private fun tap(args: JsonObject, allowCoordinates: Boolean?): Envelope {
        val x = floatArg(args, "x") ?: return Envelope.err(ErrorCode.INVALID_ARGS, "tap needs numeric 'x'")
        val y = floatArg(args, "y") ?: return Envelope.err(ErrorCode.INVALID_ARGS, "tap needs numeric 'y'")
        // the flag gate rides AFTER arg validation (validate → policy, the pipeline's order)
        coordinateGate(allowCoordinates)?.let { return it }
        val dispatched = actions.tap(x, y)
        // P12-01: the frozen spec promises {method:'COORDINATE'}; a refused gesture is a typed
        // error, never a method value (same rule as click).
        if (!dispatched) {
            return Envelope.err(
                ErrorCode.CAPABILITY_UNAVAILABLE,
                "the tap gesture was refused by the OS",
                reason = "NOT_DISPATCHED",
            )
        }
        return Envelope.ok(buildJsonObject { put("method", "COORDINATE") })
    }

    private fun press(args: JsonObject, allowGlobal: Boolean?): Envelope {
        val key = args["key"]?.let { it as? JsonPrimitive }?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "press needs a 'key' (back, home, recents, notifications)")
        if (key !in GlobalActions.known()) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "unknown key '$key'; known: back, home, recents, notifications")
        }
        globalGate(key, allowGlobal)?.let { return it }
        val performed = actions.global(key)
        // P12-01: the frozen spec promises {ok:bool} — the simulator already speaks this shape.
        return Envelope.ok(buildJsonObject { put("ok", performed) })
    }

    private fun hideKeyboard(args: JsonObject, packages: Set<String>?): Envelope {
        val visible = keyboardState(packages).visible
        // P12-01: the frozen spec promises {ok:bool}; `was_visible` rides as an additive key
        // until the P12-02 regeneration declares it.
        if (!visible) return Envelope.ok(buildJsonObject { put("ok", true); put("was_visible", false) })
        val performed = actions.global(GlobalActions.BACK)
        return Envelope.ok(buildJsonObject { put("ok", performed); put("was_visible", true) })
    }

    // --- shared helpers ----------------------------------------------------------------------------------------

    /**
     * Ref target from the registry, or selector target from a fresh scoped read. P12-16 (A7):
     * the resolution is RUN-SCOPED, and the resolved target's package is checked against the
     * ACT scope — a ref to a node of package P under an act scope of {Q} is a SCOPE_VIOLATION
     * (reason SCOPE_NOT_GRANTED, the ScopeInterceptor vocabulary), never a silent act.
     */
    private suspend fun resolveTarget(
        args: JsonObject,
        actPackages: Set<String>?,
        observePackages: Set<String>?,
        runScope: String?,
    ): AhResult<android.view.accessibility.AccessibilityNodeInfo?> {
        args["ref"]?.let { it as? JsonPrimitive }?.content?.let { ref -> return resolveHandle(ref, actPackages, runScope) }
        args["target"]?.let { target ->
            (target as? JsonObject)?.get("ref")?.let { r ->
                return resolveHandle((r as JsonPrimitive).content, actPackages, runScope)
            }
        }
        args["selector"]?.let {
            val found = find(buildJsonObject { put("selector", it) }, observePackages, timeoutMs = 0, runScope = runScope)
            val nodes = (found.result as? JsonObject)?.get("nodes") as? JsonArray
            val firstRef = nodes?.firstOrNull()?.jsonObject?.get("ref")?.let { r -> (r as JsonPrimitive).content }
            if (firstRef != null) return resolveHandle(firstRef, actPackages, runScope)
        }
        return AhResult.err(app.autohost.core.model.AhError.of(ErrorCode.INVALID_ARGS, "click needs 'ref' or 'selector'"))
    }

    private fun resolveHandle(
        ref: String,
        actPackages: Set<String>? = null,
        runScope: String? = null,
    ): AhResult<android.view.accessibility.AccessibilityNodeInfo?> {
        val node = registry.nodeOf(ref, runScope)
        if (node != null && actPackages != null && node.pkg !in actPackages) {
            return AhResult.err(
                app.autohost.core.model.AhError.of(
                    app.autohost.core.model.ErrorCode.SCOPE_VIOLATION,
                    "node ref '$ref' points at '${node.pkg}', which is outside this run's act scope",
                    reason = "SCOPE_NOT_GRANTED",
                ),
            )
        }
        return when (val result = registry.resolve(ref, runScope)) {
            is AhResult.Err -> AhResult.err(result.error)
            // A registry entry without a live handle is not stale — the node was seen. The action
            // seam turns a null handle into an honest unverified outcome (ServiceUiActions).
            is AhResult.Ok -> AhResult.ok(result.value)
        }
    }

    private fun mapResolveError(e: AhError, fallback: String): Envelope = when (e.code) {
        ErrorCode.NOT_FOUND.name -> Envelope.err(ErrorCode.NOT_FOUND, fallback, reason = "NO_MATCH")
        else -> Envelope.err(e)
    }

    private fun parseSelector(args: JsonObject): Selector? = try {
        args["selector"]?.let { AhJson.json.decodeFromJsonElement(Selector.serializer(), it.jsonObject) }
    } catch (e: Exception) {
        null
    }

    /** Explicit `scope` in args may only narrow the granted set. */
    private fun requestedPackages(args: JsonObject, granted: Set<String>?): Set<String>? {
        val explicit = ScopePackagesExtractor.fromArgs(args) ?: return granted
        if (granted == null) return explicit
        return explicit.intersect(granted)
    }

    private fun longArg(args: JsonObject, key: String, default: Long): Long =
        (args[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: default

    private fun floatArg(args: JsonObject, key: String): Float? =
        (args[key] as? JsonPrimitive)?.content?.toFloatOrNull()

    companion object {
        private const val WINDOW_UPDATING_WINDOW_MS = 1_000L
        private const val SCROLL_SETTLE_MS = 150L
        private const val SWIPE_DURATION_MS = 300L
        private const val MAX_BACKOFF_MS = 200L
    }
}

/** Extracts `scope.packages` / `scope.package` / `package` from call args (pure; unit-tested). */
object ScopePackagesExtractor {
    fun fromArgs(args: JsonObject): Set<String>? {
        val scope = args["scope"] as? JsonObject ?: return null
        return when (val pkgs = scope["packages"]) {
            is JsonArray -> pkgs.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
            else -> null
        } ?: scope["package"]?.let { (it as? JsonPrimitive)?.content }?.let { setOf(it) }
    }
}

/** Availability decision for the a11y capability (kept separate so the catalog can reuse it). */
object A11yAvailability {
    fun current(connected: Boolean): Availability =
        if (connected) {
            Availability.Available
        } else {
            Availability.NeedsSetup(
                reason = "A11Y_SERVICE_DISABLED",
                fixAction = "OPEN_ACCESSIBILITY_SETTINGS",
            )
        }
}
