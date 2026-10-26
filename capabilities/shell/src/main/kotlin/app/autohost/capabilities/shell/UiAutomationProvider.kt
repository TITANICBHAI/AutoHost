package app.autohost.capabilities.shell

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.common.AhJson
import app.autohost.core.model.Availability
import app.autohost.core.model.CompactDump
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import app.autohost.core.model.Selector
import app.autohost.core.model.SelectorEngine
import app.autohost.core.model.UiNode
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * P9-08: the experimental `device.ui` provider over a UiAutomation connection (plan §5.4). Same
 * 15-method surface as the a11y and shell.uiautomator providers; registered BETWEEN them — a11y
 * wins whenever its service is connected (first-available rule), this provider only answers while
 * an exclusive session is live, and `shell.uiautomator` remains the last resort.
 *
 * Honest differences from the other two links:
 *  - acts are REAL accessibility actions (ACTION_CLICK / ACTION_SET_TEXT / gestures) — `verified`
 *    reflects what the runner actually performed, not an `expect` re-check;
 *  - `set_text` with `secret:true` is ALLOWED here: the text rides the loopback socket into our
 *    own instrumentation process (never a shell command line) and goes through ACTION_SET_TEXT
 *    (ADR-068) — this is the only shell-tier-adjacent path that is secret-safe;
 *  - the session is exclusive with the accessibility service ([UiAutomationExclusivity]) — the
 *    controller closes it the moment the service reconnects, so the two can never coexist.
 *
 * The provider never starts the session itself: the app-layer controller owns the lifecycle; the
 * provider only consumes whatever live session is injected via [session].
 */
class UiAutomationProvider(
    private val session: () -> UiAutomationSessionClient?,
    private val screenWidth: Int = DEFAULT_SCREEN_WIDTH,
    private val screenHeight: Int = DEFAULT_SCREEN_HEIGHT,
) : CapabilityProvider {

    override val capabilityId: String = "device.ui"
    override val providerId: String = "ui.automation"
    override val tier: String = "T3"

    private val lock = Any()
    private var dumpSeq = 0
    private var currentNodes: List<UiNode> = emptyList()
    private var currentRefs: Map<String, UiNode> = emptyMap()

    private fun client(): UiAutomationSessionClient =
        session() ?: throw SessionDown("no live UiAutomation session")

    override fun availability(env: DeviceEnv): Availability =
        if (session()?.ping() == true) {
            Availability.Available
        } else {
            // Not a NeedsSetup: the fallback links (a11y, shell dump) own the setup UX; an
            // unavailable session just means this link is out of the chain.
            Availability.Unsupported("UI_AUTOMATION_SESSION_DOWN")
        }

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
        return try {
            when (method) {
                "snapshot" -> snapshot(observePackages)
                "find" -> find(args, observePackages, timeoutMs = 0)
                "wait_for" -> find(args, observePackages, timeoutMs = longArg(args, "timeout_ms", 10_000))
                "wait_for_settle" -> waitForSettle(longArg(args, "timeout_ms", 3_000), longArg(args, "stable_ms", 300))
                "is_window_updating" -> Envelope.ok(buildJsonObject { put("updating", !settledNow()) })
                "dump" -> dump(observePackages)
                "keyboard_visible" -> keyboardVisible()
                "click" -> click(args, actPackages, observePackages, longClick = false)
                "long_click" -> click(args, actPackages, observePackages, longClick = true)
                "set_text" -> setText(args, actPackages, observePackages)
                "scroll_until" -> scrollUntil(args, actPackages, observePackages)
                "swipe" -> swipe(args)
                "tap" -> tap(args)
                "press" -> press(args)
                "hide_keyboard" -> hideKeyboard()
                else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.ui method '$method'")
            }
        } catch (e: SessionDown) {
            Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, e.message ?: "session down", reason = "UI_AUTOMATION_SESSION_DOWN")
        } catch (e: UiAutomationSessionClient.RpcError) {
            when (e.code) {
                "NODE_NOT_FOUND" -> Envelope.err(ErrorCode.NOT_FOUND, "the node was not found in the live tree", reason = e.code)
                "SESSION_CLOSED" -> Envelope.err(
                    ErrorCode.CAPABILITY_UNAVAILABLE,
                    "the UiAutomation session closed (accessibility reconnected?)",
                    reason = "UI_AUTOMATION_SESSION_DOWN",
                )
                else -> Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, e.message ?: "runner error", reason = e.code)
            }
        }
    }

    // --- reads ----------------------------------------------------------------------------------------------

    private fun readDump(): List<UiNode> {
        val xml = client().dump()
        val nodes = UiautomatorDump.parse(xml)
        synchronized(lock) {
            dumpSeq++
            currentNodes = nodes
            currentRefs = nodes.associate { node -> refFor(node) to node }
        }
        return nodes
    }

    private fun refFor(node: UiNode): String = "ua$dumpSeq.${node.index}"

    private fun snapshot(packages: Set<String>?): Envelope {
        val nodes = scoped(packages) { readDump() }
        val pkg = nodes.firstOrNull()?.pkg
        return Envelope.ok(
            buildJsonObject {
                put("ts", System.currentTimeMillis())
                put("package", pkg)
                put("node_count", nodes.size)
                put("empty", nodes.isEmpty())
                put("degraded", false)
                put("provider", providerId)
                put(
                    "nodes",
                    kotlinx.serialization.json.JsonArray(nodes.mapIndexed { i, node -> node.summary(refFor(node)) }),
                )
                put(
                    "compact",
                    CompactDump.render(nodes = nodes, pkg = pkg ?: "", screenWidth = screenWidth, screenHeight = screenHeight),
                )
            },
        )
    }

    private fun find(args: JsonObject, packages: Set<String>?, timeoutMs: Long): Envelope {
        val selector = parseSelector(args)
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "find/wait_for need a 'selector' argument")
        val deadline = System.nanoTime() / 1_000_000 + timeoutMs
        while (true) {
            val nodes = scoped(packages) { readDump() }
            val matched = SelectorEngine.findAll(nodes, selector).firstOrNull()
            if (matched != null) {
                return Envelope.ok(buildJsonObject { put("node", matched.summary(refFor(matched))) })
            }
            if (System.nanoTime() / 1_000_000 >= deadline) {
                return Envelope.ok(buildJsonObject {})
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Envelope.err(ErrorCode.CANCELLED, "wait_for was interrupted")
            }
        }
    }

    private suspend fun waitForSettle(timeoutMs: Long, stableMs: Long): Envelope {
        val deadline = System.nanoTime() / 1_000_000 + timeoutMs
        var last = signatures()
        while (true) {
            delay(stableMs.coerceIn(50, 2_000))
            val now = signatures()
            if (now == last) return Envelope.ok(buildJsonObject { put("settled", true) })
            last = now
            if (System.nanoTime() / 1_000_000 >= deadline) {
                return Envelope.ok(buildJsonObject { put("settled", false) })
            }
        }
    }

    private suspend fun settledNow(): Boolean = try {
        val first = signatures()
        delay(150)
        first == signatures()
    } catch (_: Exception) {
        false
    }

    private fun signatures(): List<String> = readDump().map { it.signature }

    private fun dump(packages: Set<String>?): Envelope {
        val nodes = scoped(packages) { readDump() }
        return Envelope.ok(
            buildJsonObject {
                put(
                    "text",
                    CompactDump.render(nodes = nodes, screenWidth = screenWidth, screenHeight = screenHeight),
                )
            },
        )
    }

    private fun keyboardVisible(): Envelope = Envelope.ok(buildJsonObject {
        // The UiAutomation tree does not carry a reliable IME-visibility flag on all builds; the
        // honest answer reports what the tree shows (no focused editable ⇒ hidden).
        val nodes = readDump()
        put("visible", nodes.any { it.has("focused") && it.has("editable") })
        put("verified", false)
    })

    // --- acts ------------------------------------------------------------------------------------------------

    private fun click(args: JsonObject, actPackages: Set<String>?, observePackages: Set<String>?, longClick: Boolean): Envelope {
        val node = resolveTarget(args, observePackages)
            ?: return Envelope.err(ErrorCode.NOT_FOUND, "click target did not resolve to a node")
        scopeViolation(actPackages, node)?.let {
            return Envelope.err(ErrorCode.SCOPE_VIOLATION, "target package \"$it\" is outside this run's device.ui.act scope")
        }
        val performed = if (longClick) {
            // ACTION_LONG_CLICK on the node; the runner reports what it actually performed.
            client().click(node.index) && client().gesture(
                node.bounds.centerX, node.bounds.centerY, node.bounds.centerX, node.bounds.centerY, 800,
            )
        } else {
            client().click(node.index)
        }
        val expectVerified = verifyExpect(args, observePackages)
        return Envelope.ok(buildJsonObject {
            put("method", "ui.automation")
            put("performed", performed)
            put("verified", performed && (expectVerified || args["expect"] == null))
        })
    }

    private fun setText(args: JsonObject, actPackages: Set<String>?, observePackages: Set<String>?): Envelope {
        val text = (args["text"] as? JsonPrimitive)?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"text\" is required")
        val node = resolveTarget(args, observePackages)
            ?: return Envelope.err(ErrorCode.NOT_FOUND, "set_text target did not resolve to a node")
        scopeViolation(actPackages, node)?.let {
            return Envelope.err(ErrorCode.SCOPE_VIOLATION, "target package \"$it\" is outside this run's device.ui.act scope")
        }
        if (text.length > MAX_TEXT) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "text exceeds the length cap ($MAX_TEXT)")
        }
        // secret:true is ALLOWED on this provider (ADR-068): the text rides the loopback socket
        // into the app's own instrumentation process, never a shell command line.
        val performed = client().setText(node.index, text)
        val verify = boolArg(args, "verify")
        val outcome = when {
            !performed -> "Rejected"
            verify -> {
                val after = readDump().firstOrNull { it.signature == node.signature }
                when {
                    after != null && after.text == text -> "Verified"
                    after != null -> "Rejected"
                    else -> "AcceptedUnverified"
                }
            }
            else -> "AcceptedUnverified"
        }
        return Envelope.ok(buildJsonObject {
            put("outcome", outcome)
            put("strategy", "ui.automation")
            put("secret", boolArg(args, "secret"))
        })
    }

    private fun scrollUntil(args: JsonObject, actPackages: Set<String>?, observePackages: Set<String>?): Envelope {
        val selector = parseSelector(args)
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "scroll_until needs a 'selector' argument")
        val direction = (args["direction"] as? JsonPrimitive)?.content ?: "down"
        if (direction !in DIRECTIONS) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "direction must be one of ${DIRECTIONS.joinToString("|")}")
        }
        val maxSwipes = ((args["max_swipes"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 10).coerceIn(1, 50)
        repeat(maxSwipes) {
            val nodes = scoped(observePackages) { readDump() }
            val matched = SelectorEngine.findAll(nodes, selector).firstOrNull()
            if (matched != null) {
                return Envelope.ok(buildJsonObject { put("node", matched.summary(refFor(matched))) })
            }
            val (x1, y1, x2, y2) = swipeVectors.getValue(direction)
            client().gesture(x1, y1, x2, y2, SCROLL_MS)
            Thread.sleep(SCROLL_SETTLE_MS)
        }
        val nodes = scoped(observePackages) { readDump() }
        val matched = SelectorEngine.findAll(nodes, selector).firstOrNull()
        return if (matched != null) {
            Envelope.ok(buildJsonObject { put("node", matched.summary(refFor(matched))) })
        } else {
            Envelope.ok(buildJsonObject {})
        }
    }

    private fun swipe(args: JsonObject): Envelope {
        val x1 = (args["x1"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "swipe needs x1,y1,x2,y2")
        val y1 = (args["y1"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "swipe needs x1,y1,x2,y2")
        val x2 = (args["x2"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "swipe needs x1,y1,x2,y2")
        val y2 = (args["y2"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "swipe needs x1,y1,x2,y2")
        val ms = ((args["duration_ms"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 300)
        val performed = client().gesture(x1, y1, x2, y2, ms)
        return Envelope.ok(buildJsonObject { put("method", "ui.automation"); put("performed", performed) })
    }

    private fun tap(args: JsonObject): Envelope {
        val x = (args["x"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "tap needs x,y")
        val y = (args["y"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "tap needs x,y")
        val performed = client().gesture(x, y, x, y, 60)
        return Envelope.ok(buildJsonObject { put("method", "ui.automation"); put("performed", performed) })
    }

    private fun press(args: JsonObject): Envelope {
        val key = (args["key"] as? JsonPrimitive)?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"key\" is required")
        val code = KEYEVENTS[key]
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "unknown key '$key'; supported: ${KEYEVENTS.keys.sorted().joinToString("|")}")
        val performed = client().key(code)
        return Envelope.ok(buildJsonObject { put("method", "ui.automation"); put("performed", performed) })
    }

    private fun hideKeyboard(): Envelope {
        val performed = client().key(KEY_ESCAPE)
        return Envelope.ok(buildJsonObject { put("hidden", performed); put("verified", performed) })
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private fun scoped(packages: Set<String>?, block: () -> List<UiNode>): List<UiNode> {
        val nodes = block()
        if (packages == null) return nodes
        return nodes.filter { it.pkg in packages }
    }

    private fun decodeSelector(json: kotlinx.serialization.json.JsonElement): Selector? = try {
        AhJson.json.decodeFromJsonElement(Selector.serializer(), json.jsonObject)
    } catch (_: Exception) {
        null
    }

    private fun resolveTarget(args: JsonObject, observePackages: Set<String>?): UiNode? {
        val target = args["target"] as? JsonObject ?: return null
        val ref = target["ref"] as? JsonPrimitive
        if (ref != null) {
            return synchronized(lock) { currentRefs[ref.content] }
        }
        val selectorJson = target["selector"] ?: return null
        val selector = decodeSelector(selectorJson) ?: return null
        val nodes = scoped(observePackages) { readDump() }
        return SelectorEngine.findAll(nodes, selector).firstOrNull()
    }

    private fun verifyExpect(args: JsonObject, observePackages: Set<String>?): Boolean {
        val expect = args["expect"] ?: return false
        val selector = decodeSelector(expect) ?: return false
        return try {
            SelectorEngine.findAll(readDump(), selector).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun scopeViolation(actPackages: Set<String>?, node: UiNode): String? {
        if (actPackages == null) return null
        if (node.pkg !in actPackages) return node.pkg
        return null
    }

    private fun parseSelector(args: JsonObject): Selector? {
        val json = args["selector"] ?: return null
        return decodeSelector(json)
    }

    private fun longArg(args: JsonObject, name: String, default: Long): Long =
        (args[name] as? JsonPrimitive)?.content?.toLongOrNull() ?: default

    private fun boolArg(args: JsonObject, name: String): Boolean =
        (args[name] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

    private class SessionDown(detail: String) : Exception(detail)

    companion object {
        const val DEFAULT_SCREEN_WIDTH = 1080
        const val DEFAULT_SCREEN_HEIGHT = 2400
        const val POLL_INTERVAL_MS = 400L
        const val SCROLL_MS = 400
        const val SCROLL_SETTLE_MS = 600L
        const val MAX_TEXT = 2_000
        const val KEY_ESCAPE = 111

        val KEYEVENTS = ShellUiProvider.KEYEVENTS
        val DIRECTIONS = setOf("down", "up", "left", "right")
        val swipeVectors = ShellUiProvider.swipeVectors
    }
}
