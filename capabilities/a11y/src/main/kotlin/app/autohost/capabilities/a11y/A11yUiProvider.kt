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
        return when (method) {
            "snapshot" -> snapshot(args, observePackages)
            "find" -> find(args, observePackages, timeoutMs = 0)
            "wait_for" -> find(args, observePackages, timeoutMs = longArg(args, "timeout_ms", 10_000))
            "wait_for_settle" -> waitForSettle(args)
            "is_window_updating" -> Envelope.ok(
                buildJsonObject { put("updating", isWindowUpdating()) },
            )
            "dump" -> dump(args, observePackages)
            "keyboard_visible" -> Envelope.ok(buildJsonObject { put("visible", keyboardState(observePackages).visible) })
            "click" -> click(ctx, args, actPackages, observePackages, longClick = false)
            "long_click" -> click(ctx, args, actPackages, observePackages, longClick = true)
            "set_text" -> setText(args, actPackages, observePackages)
            "scroll_until" -> scrollUntil(ctx, args, actPackages, observePackages)
            "swipe" -> swipe(args)
            "tap" -> tap(args)
            "press" -> press(args)
            "hide_keyboard" -> hideKeyboard(args, observePackages)
            else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.ui method '$method'")
        }
    }

    // --- reads (P3-05 / P3-06 / P3-17) ------------------------------------------------------------------------

    /** Read key: one gate slot per distinct scope set so concurrent runs do not thrash each other. */
    private fun gateKey(packages: Set<String>?): String = packages?.sorted()?.joinToString(",") ?: "*"

    private fun read(packages: Set<String>?): Pair<A11ySnapshot, Meta?> {
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

    private fun snapshot(args: JsonObject, packages: Set<String>?): Envelope {
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
        val refs = snap.nodes.map { node -> registry.put(node, snap.infoAt(node.index)) }
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

    private suspend fun find(args: JsonObject, packages: Set<String>?, timeoutMs: Long): Envelope {
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
            val match = SelectorEngine.findFirst(snap.nodes, selector)
            if (match != null) {
                val ref = registry.put(match, snap.infoAt(match.index))
                return Envelope.ok(
                    buildJsonObject {
                        put("ref", ref)
                        put("node", match.summary(ref))
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
                    return Envelope.ok(buildJsonObject { put("settled", true); put("elapsed_ms", elapsed) })
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
        return KeyboardState.evaluate(
            windows = snap.windows,
            focusedId = focused?.id?.ifEmpty { null },
            focusedEditable = focused?.has("editable") == true,
        )
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
            filter = if (filtering) CompactDump.DumpFilter() else null,
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
        val guardResult = guardAndResolve(args, actPackages, observePackages)
        val info = guardResult.getOrElse { e -> return mapResolveError(e, "click target not found") }
        val fingerprintBefore = fingerprintOfCurrent(observePackages)
        val outcome = if (longClick) actions.longClick(info) else actions.click(info)
        // retry_if_no_change: one honest repetition when the screen did not change at all (plan §5.7).
        val retryFlag = (args["retry_if_no_change"] as? JsonPrimitive)?.content?.toBooleanStrict() ?: false
        if (retryFlag && outcome.verified && SettlePolicy.retryIfNoChange(changed(fingerprintBefore, observePackages), alreadyRetried = false)) {
            // The handle was just re-resolved inside the chain; one repeat with the same handle is the
            // documented single retry. A second miss is reported, never looped.
            if (longClick) actions.longClick(info) else actions.click(info)
        }
        val verifiedByExpect = verifyExpect(args, observePackages)
        return when {
            verifiedByExpect != null -> verifiedByExpect
            else -> Envelope.ok(
                buildJsonObject {
                    put("method", outcome.route?.name ?: "NONE")
                    put("verified", outcome.verified)
                    put("coordinate", outcome.route == NodeActions.Route.COORDINATE)
                },
            )
        }
    }

    /** Resolves the target and applies the popup guard; returns the resolved node handle or an error envelope. */
    private suspend fun guardAndResolve(
        args: JsonObject,
        actPackages: Set<String>?,
        observePackages: Set<String>?,
    ): AhResult<android.view.accessibility.AccessibilityNodeInfo> {
        val resolved = resolveTarget(args, actPackages, observePackages)
        val info = resolved.getOrElse { e ->
            return AhResult.err(e)
        }
        // Popup guard: a higher-layer window above the target's window refuses blind taps (plan §5.7).
        val targetWindowId = UiTreeBuilder.safeWindowId(info)
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
        val info = snap.infoAt(target.index) ?: registry.resolve(registry.put(target, null)).getOrNull() ?: return false
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

    private fun fingerprintOfCurrent(packages: Set<String>?): String? = try {
        SettlePolicy.fingerprint(reader.read(packages).nodes)
    } catch (e: RuntimeException) {
        null
    }

    private fun changed(before: String?, packages: Set<String>?): Boolean {
        val now = fingerprintOfCurrent(packages) ?: return true // unreadable state counts as changed (fail open for retry only)
        return now != before
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

    private suspend fun setText(args: JsonObject, actPackages: Set<String>?, observePackages: Set<String>?): Envelope {
        val text = args["text"]?.let { it as? JsonPrimitive }?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "set_text needs a 'text' argument")
        val secret = (args["secret"] as? JsonPrimitive)?.content?.toBooleanStrict() ?: false
        val info = guardAndResolve(args, actPackages, observePackages).getOrElse { e ->
            return mapResolveError(e, "set_text target not found")
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
                val ref = registry.put(match, snap.infoAt(match.index))
                return Envelope.ok(
                    buildJsonObject {
                        put("found", true)
                        put("ref", ref)
                        put("node", match.summary(ref))
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

    private fun swipe(args: JsonObject): Envelope {
        val x1 = floatArg(args, "x1") ?: return invalidSwipe(args)
        val y1 = floatArg(args, "y1") ?: return invalidSwipe(args)
        val x2 = floatArg(args, "x2") ?: return invalidSwipe(args)
        val y2 = floatArg(args, "y2") ?: return invalidSwipe(args)
        val durationMs = longArg(args, "duration_ms", 300)
        val dispatched = actions.swipe(x1, y1, x2, y2, durationMs)
        return Envelope.ok(buildJsonObject { put("dispatched", dispatched) })
    }

    private fun invalidSwipe(args: JsonObject): Envelope =
        Envelope.err(ErrorCode.INVALID_ARGS, "swipe needs x1, y1, x2, y2 (numbers)")

    private fun tap(args: JsonObject): Envelope {
        val x = floatArg(args, "x") ?: return Envelope.err(ErrorCode.INVALID_ARGS, "tap needs numeric 'x'")
        val y = floatArg(args, "y") ?: return Envelope.err(ErrorCode.INVALID_ARGS, "tap needs numeric 'y'")
        val dispatched = actions.tap(x, y)
        return Envelope.ok(
            buildJsonObject {
                put("dispatched", dispatched)
                put("coordinate", true) // raw taps are always coordinate results (plan §7.6)
            },
        )
    }

    private fun press(args: JsonObject): Envelope {
        val key = args["key"]?.let { it as? JsonPrimitive }?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "press needs a 'key' (back, home, recents, notifications)")
        if (key !in GlobalActions.known()) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "unknown key '$key'; known: back, home, recents, notifications")
        }
        val performed = actions.global(key)
        return Envelope.ok(buildJsonObject { put("performed", performed) })
    }

    private fun hideKeyboard(args: JsonObject, packages: Set<String>?): Envelope {
        val visible = keyboardState(packages).visible
        if (!visible) return Envelope.ok(buildJsonObject { put("hidden", false); put("was_visible", false) })
        val performed = actions.global(GlobalActions.BACK)
        return Envelope.ok(buildJsonObject { put("hidden", performed); put("was_visible", true) })
    }

    // --- shared helpers ----------------------------------------------------------------------------------------

    /** Ref target from the registry, or selector target from a fresh scoped read. */
    private suspend fun resolveTarget(
        args: JsonObject,
        actPackages: Set<String>?,
        observePackages: Set<String>?,
    ): AhResult<android.view.accessibility.AccessibilityNodeInfo> {
        args["ref"]?.let { it as? JsonPrimitive }?.content?.let { ref -> return registry.resolve(ref) }
        args["target"]?.let { target ->
            (target as? JsonObject)?.get("ref")?.let { r ->
                return registry.resolve((r as JsonPrimitive).content)
            }
        }
        args["selector"]?.let {
            val found = find(buildJsonObject { put("selector", it) }, observePackages, timeoutMs = 0)
            val ref = (found.result as? JsonObject)?.get("ref")?.let { r -> (r as JsonPrimitive).content }
            if (ref != null) return registry.resolve(ref)
        }
        return AhResult.err(app.autohost.core.model.AhError.of(ErrorCode.INVALID_ARGS, "click needs 'ref' or 'selector'"))
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
