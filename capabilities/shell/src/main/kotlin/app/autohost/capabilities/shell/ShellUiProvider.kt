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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * P9-05: the T3 alternative `device.ui` provider (plan §3.1 — "alternative provider for device.ui
 * when T1 is unavailable"). Same method surface as [app.autohost.capabilities.a11y.A11yUiProvider],
 * but reads come from `uiautomator dump` and acts from `input` commands, so:
 *
 *  - reads are whole-screen snapshots (no live event stream; `wait_for` polls dumps);
 *  - every act is honest about verification — clicks report `verified:false` unless the caller
 *    passed an `expect` selector that was then observed; coordinates are the node bounds centers;
 *  - `set_text` with `secret:true` is REFUSED ([ErrorCode.TEXT_INPUT_REJECTED]): the text would
 *    ride the shell command line where evidence/audit redaction cannot reliably reach (ADR-066) —
 *    grant `device.ime` for secrets instead;
 *  - internal commands bypass the `device.shell` task allowlist by design (they run under the
 *    `device.ui` grants); the allowlist never gates these.
 *
 * Registered AFTER the a11y provider so the broker picks a11y whenever its service is connected
 * (first-available rule, `CapabilityRegistry` registration order).
 */
class ShellUiProvider(
    private val transports: ShellTransports,
    private val screenWidth: Int = DEFAULT_SCREEN_WIDTH,
    private val screenHeight: Int = DEFAULT_SCREEN_HEIGHT,
) : CapabilityProvider {

    override val capabilityId: String = "device.ui"
    override val providerId: String = "shell.uiautomator"
    override val tier: String = "T3"

    private val lock = Any()
    private var dumpSeq = 0
    private var currentNodes: List<UiNode> = emptyList()
    private var currentRefs: Map<String, UiNode> = emptyMap()

    override fun availability(env: DeviceEnv): Availability = when (val live = transports.firstLive()) {
        null -> Availability.NeedsSetup("SHELL_TIER_NOT_PAIRED", "OPEN_SHELL_SETUP")
        else -> Availability.Available
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
        return when (method) {
            "snapshot" -> snapshot(observePackages)
            "find" -> find(args, observePackages, timeoutMs = 0)
            "wait_for" -> find(args, observePackages, timeoutMs = longArg(args, "timeout_ms", 10_000))
            "wait_for_settle" -> waitForSettle(longArg(args, "timeout_ms", 3_000), longArg(args, "stable_ms", 300))
            "is_window_updating" -> Envelope.ok(buildJsonObject { put("updating", !settledNow()) })
            "dump" -> dump(observePackages)
            "keyboard_visible" -> keyboardVisible()
            "click" -> click(ctx, args, actPackages, observePackages, longClick = false)
            "long_click" -> click(ctx, args, actPackages, observePackages, longClick = true)
            "set_text" -> setText(ctx, args, actPackages, observePackages)
            "scroll_until" -> scrollUntil(ctx, args, actPackages, observePackages)
            "swipe" -> swipe(args)
            "tap" -> tap(args)
            "press" -> press(args)
            "hide_keyboard" -> hideKeyboard(actPackages)
            else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.ui method '$method'")
        }
    }

    // --- reads ----------------------------------------------------------------------------------------------

    private fun execOrSetup(command: String, timeoutMs: Long = 10_000): ShellResult {
        val transport = transports.firstLive()
            ?: throw ShellTransportException(ShellTransportException.Reason.NOT_CONNECTED, "no shell transport is connected")
        // ShellTransportException propagates to the per-method handlers; other throwables are
        // provider bugs and surface as INTERNAL through the broker's top-level guard.
        return transport.exec(command, timeoutMs)
    }

    private fun readDump(): List<UiNode> {
        val result = execOrSetup(ShellCommands.uiautomatorDump(), SNAPSHOT_TIMEOUT_MS)
        // Anchor on the XML itself (uiautomator's banner line is unreliable across versions).
        val out = result.stdout
        val xmlStart = out.indexOf("<?xml").let { if (it >= 0) it else out.indexOf("<hierarchy") }
        val xml = if (xmlStart >= 0) out.substring(xmlStart) else out.trim()
        if (!xml.contains("<hierarchy") && !xml.contains("<node")) {
            throw DumpEmpty("uiautomator produced no XML${if (result.stderr.isNotBlank()) ": ${result.stderr.take(200)}" else ""}")
        }
        val nodes = UiautomatorDump.parse(xml)
        synchronized(lock) {
            dumpSeq++
            currentNodes = nodes
            currentRefs = nodes.associate { node -> refFor(node) to node }
        }
        return nodes
    }

    private fun refFor(node: UiNode): String = "u$dumpSeq.${node.index}"

    private fun snapshot(packages: Set<String>?): Envelope = try {
        val nodes = scoped(packages) { readDump() }
        val pkg = nodes.firstOrNull()?.pkg
        val compact = CompactDump.render(
            nodes = nodes,
            pkg = pkg ?: "",
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
        Envelope.ok(
            buildJsonObject {
                put("ts", System.currentTimeMillis())
                put("package", pkg)
                put("keyboard", keyboardState())
                put("node_count", nodes.size)
                put("empty", nodes.isEmpty())
                put("degraded", false)
                putJsonNodes(nodes)
                put("compact", compact)
            },
        )
    } catch (e: ShellTransportException) {
        transportError(e)
    } catch (e: DumpEmpty) {
        Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, e.message ?: "uiautomator dump failed", reason = "UI_DUMP_FAILED")
    } catch (e: UiautomatorDump.DumpParseException) {
        Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "uiautomator dump could not be parsed: ${e.message}", reason = "UI_DUMP_FAILED")
    }

    private fun find(args: JsonObject, packages: Set<String>?, timeoutMs: Long): Envelope {
        val selector = parseSelector(args)
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "find/wait_for need a 'selector' argument")
        val deadline = System.nanoTime() / 1_000_000 + timeoutMs
        while (true) {
            try {
                val nodes = scoped(packages) { readDump() }
                val matched = SelectorEngine.findAll(nodes, selector)
                if (matched.isNotEmpty()) {
                    val node = matched.first()
                    return Envelope.ok(
                        buildJsonObject {
                            put("node", node.summary(refFor(node)))
                        },
                    )
                }
            } catch (e: ShellTransportException) {
                return transportError(e)
            } catch (e: DumpEmpty) {
                return Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, e.message ?: "uiautomator dump failed", reason = "UI_DUMP_FAILED")
            } catch (e: UiautomatorDump.DumpParseException) {
                return Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "uiautomator dump could not be parsed: ${e.message}", reason = "UI_DUMP_FAILED")
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

    private fun waitForSettle(timeoutMs: Long, stableMs: Long): Envelope {
        val deadline = System.nanoTime() / 1_000_000 + timeoutMs
        var last = signatures()
        while (true) {
            try {
                Thread.sleep(stableMs.coerceIn(50, 2_000))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Envelope.err(ErrorCode.CANCELLED, "wait_for_settle was interrupted")
            }
            val now = try {
                signatures()
            } catch (e: ShellTransportException) {
                return transportError(e)
            }
            if (now == last) return Envelope.ok(buildJsonObject { put("settled", true) })
            last = now
            if (System.nanoTime() / 1_000_000 >= deadline) {
                return Envelope.ok(buildJsonObject { put("settled", false) })
            }
        }
    }

    private fun settledNow(): Boolean = try {
        val first = signatures()
        Thread.sleep(150)
        first == signatures()
    } catch (_: Exception) {
        false
    }

    private fun signatures(): List<String> = readDump().map { it.signature }

    private fun dump(packages: Set<String>?): Envelope = try {
        val nodes = scoped(packages) { readDump() }
        Envelope.ok(
            buildJsonObject {
                put(
                    "text",
                    CompactDump.render(nodes = nodes, screenWidth = screenWidth, screenHeight = screenHeight),
                )
            },
        )
    } catch (e: ShellTransportException) {
        transportError(e)
    } catch (e: DumpEmpty) {
        Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, e.message ?: "uiautomator dump failed", reason = "UI_DUMP_FAILED")
    } catch (e: UiautomatorDump.DumpParseException) {
        Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "uiautomator dump could not be parsed: ${e.message}", reason = "UI_DUMP_FAILED")
    }

    private fun keyboardVisible(): Envelope = try {
        val result = execOrSetup(ShellCommands.dumpsysKeyboard(), 5_000)
        Envelope.ok(buildJsonObject { put("visible", result.stdout.contains("mInputShown=true")) })
    } catch (e: ShellTransportException) {
        transportError(e)
    }

    private fun keyboardState(): Boolean = try {
        execOrSetup(ShellCommands.dumpsysKeyboard(), 5_000).stdout.contains("mInputShown=true")
    } catch (_: Exception) {
        false
    }

    // --- acts -------------------------------------------------------------------------------------------------

    private fun click(
        ctx: CallContext,
        args: JsonObject,
        actPackages: Set<String>?,
        observePackages: Set<String>?,
        longClick: Boolean,
    ): Envelope {
        val node = resolveTarget(args, observePackages)
            ?: return Envelope.err(ErrorCode.NOT_FOUND, "click target did not resolve to a node")
        val violation = scopeViolation(actPackages, node)
        if (violation != null) {
            return Envelope.err(ErrorCode.SCOPE_VIOLATION, "target package \"${node.pkg}\" is outside this run's device.ui.act scope")
        }
        val bounds = node.bounds
        if (bounds.width <= 0 || bounds.height <= 0) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "target node has an empty bounds box")
        }
        return try {
            if (longClick) {
                execOrSetup(
                    ShellCommands.inputSwipe(bounds.centerX, bounds.centerY, bounds.centerX, bounds.centerY, 1_000),
                    ACT_TIMEOUT_MS,
                )
            } else {
                execOrSetup(ShellCommands.inputTap(bounds.centerX, bounds.centerY), ACT_TIMEOUT_MS)
            }
            val verified = verifyExpect(args, observePackages)
            Envelope.ok(
                buildJsonObject {
                    put("method", "shell.input")
                    put("verified", verified)
                },
            )
        } catch (e: ShellTransportException) {
            transportError(e)
        }
    }

    private fun setText(ctx: CallContext, args: JsonObject, actPackages: Set<String>?, observePackages: Set<String>?): Envelope {
        val secret = boolArg(args, "secret")
        if (secret) {
            return Envelope.err(
                ErrorCode.TEXT_INPUT_REJECTED,
                "shell input cannot keep text out of the process command line; grant device.ime for secret text",
                reason = "SHELL_TEXT_NOT_SECRET_SAFE",
            )
        }
        val text = (args["text"] as? JsonPrimitive)?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"text\" is required")
        val node = resolveTarget(args, observePackages)
            ?: return Envelope.err(ErrorCode.NOT_FOUND, "set_text target did not resolve to a node")
        val violation = scopeViolation(actPackages, node)
        if (violation != null) {
            return Envelope.err(ErrorCode.SCOPE_VIOLATION, "target package \"${node.pkg}\" is outside this run's device.ui.act scope")
        }
        if (text.length > MAX_TEXT) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "text exceeds the shell input length cap ($MAX_TEXT)")
        }
        return try {
            // Focus first, clear the existing value, then commit (best effort; verified when asked).
            execOrSetup(ShellCommands.inputTap(node.bounds.centerX, node.bounds.centerY), ACT_TIMEOUT_MS)
            runCatching { execOrSetup(ShellCommands.keyevent(KEY_MOVE_END), ACT_TIMEOUT_MS) }
            repeat(CLEAR_BACKSPACES) { runCatching { execOrSetup(ShellCommands.keyevent(KEY_DEL), ACT_TIMEOUT_MS) } }
            execOrSetup(ShellCommands.inputText(text), ACT_TIMEOUT_MS)
            val verify = boolArg(args, "verify")
            val outcome = if (verify) {
                val after = scoped(observePackages) { readDump() }.firstOrNull { it.signature == node.signature }
                when {
                    after != null && after.text == text -> "Verified"
                    after != null -> "Rejected"
                    else -> "AcceptedUnverified"
                }
            } else {
                "AcceptedUnverified"
            }
            Envelope.ok(
                buildJsonObject {
                    put("outcome", outcome)
                    put("strategy", "shell.input")
                },
            )
        } catch (e: ShellTransportException) {
            transportError(e)
        }
    }

    private fun scrollUntil(
        ctx: CallContext,
        args: JsonObject,
        actPackages: Set<String>?,
        observePackages: Set<String>?,
    ): Envelope {
        val selector = parseSelector(args)
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "scroll_until needs a 'selector' argument")
        val direction = (args["direction"] as? JsonPrimitive)?.content ?: "down"
        if (direction !in DIRECTIONS) {
            return Envelope.err(ErrorCode.INVALID_ARGS, "direction must be one of ${DIRECTIONS.joinToString("|")}")
        }
        val maxSwipes = ((args["max_swipes"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 10).coerceIn(1, 50)
        return try {
            repeat(maxSwipes) {
                val nodes = scoped(observePackages) { readDump() }
                val matched = SelectorEngine.findAll(nodes, selector).firstOrNull()
                if (matched != null) {
                    return Envelope.ok(buildJsonObject { put("node", matched.summary(refFor(matched))) })
                }
                val (x1, y1, x2, y2) = swipeVectors.getValue(direction)
                execOrSetup(
                    ShellCommands.inputSwipe(x1, y1, x2, y2, SCROLL_MS),
                    ACT_TIMEOUT_MS,
                )
                Thread.sleep(SCROLL_SETTLE_MS)
            }
            val nodes = scoped(observePackages) { readDump() }
            val matched = SelectorEngine.findAll(nodes, selector).firstOrNull()
            if (matched != null) {
                Envelope.ok(buildJsonObject { put("node", matched.summary(refFor(matched))) })
            } else {
                Envelope.ok(buildJsonObject {})
            }
        } catch (e: ShellTransportException) {
            transportError(e)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Envelope.err(ErrorCode.CANCELLED, "scroll_until was interrupted")
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
        val duration = ((args["duration_ms"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 300)
        return try {
            execOrSetup(ShellCommands.inputSwipe(x1, y1, x2, y2, duration), ACT_TIMEOUT_MS)
            Envelope.ok(buildJsonObject { put("method", "shell.input") })
        } catch (e: ShellTransportException) {
            transportError(e)
        }
    }

    private fun tap(args: JsonObject): Envelope {
        val x = (args["x"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "tap needs x,y")
        val y = (args["y"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "tap needs x,y")
        return try {
            execOrSetup(ShellCommands.inputTap(x, y), ACT_TIMEOUT_MS)
            Envelope.ok(buildJsonObject { put("method", "shell.input") })
        } catch (e: ShellTransportException) {
            transportError(e)
        }
    }

    private fun press(args: JsonObject): Envelope {
        val key = (args["key"] as? JsonPrimitive)?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"key\" is required")
        val code = KEYEVENTS[key]
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "unknown key '$key'; supported: ${KEYEVENTS.keys.sorted().joinToString("|")}")
        return try {
            execOrSetup(ShellCommands.keyevent(code), ACT_TIMEOUT_MS)
            Envelope.ok(buildJsonObject { put("method", "shell.input") })
        } catch (e: ShellTransportException) {
            transportError(e)
        }
    }

    private fun hideKeyboard(actPackages: Set<String>?): Envelope = try {
        execOrSetup(ShellCommands.keyevent(KEY_ESCAPE), ACT_TIMEOUT_MS)
        Envelope.ok(buildJsonObject { put("hidden", true); put("verified", false) })
    } catch (e: ShellTransportException) {
        transportError(e)
    }

    // --- helpers ----------------------------------------------------------------------------------------------

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
            val nodes = scoped(observePackages) { readDump() }
            SelectorEngine.findAll(nodes, selector).isNotEmpty()
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

    private fun transportError(e: ShellTransportException): Envelope = when (e.reason) {
        ShellTransportException.Reason.TIMEOUT ->
            Envelope.err(ErrorCode.TIMEOUT, e.message ?: "shell transport timed out")
        ShellTransportException.Reason.NOT_CONNECTED ->
            Envelope.err(
                ErrorCode.NEEDS_SETUP,
                e.message ?: "no shell transport is connected",
                reason = "SHELL_TIER_NOT_PAIRED",
                fixAction = "OPEN_SHELL_SETUP",
            )
        else ->
            Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, e.message ?: "shell transport failed", reason = "UI_DUMP_FAILED")
    }

    private fun longArg(args: JsonObject, name: String, default: Long): Long =
        (args[name] as? JsonPrimitive)?.content?.toLongOrNull() ?: default

    private fun boolArg(args: JsonObject, name: String): Boolean =
        (args[name] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonNodes(nodes: List<UiNode>) {
        put(
            "nodes",
            kotlinx.serialization.json.JsonArray(nodes.mapIndexed { i, node -> node.summary(refFor(node)) }),
        )
    }

    private class DumpEmpty(detail: String) : Exception(detail)

    companion object {
        const val DEFAULT_SCREEN_WIDTH = 1080
        const val DEFAULT_SCREEN_HEIGHT = 2400
        const val SNAPSHOT_TIMEOUT_MS = 15_000L
        const val ACT_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 400L
        const val SCROLL_MS = 400
        const val SCROLL_SETTLE_MS = 600L
        const val MAX_TEXT = 2_000
        const val CLEAR_BACKSPACES = 100
        const val KEY_DEL = 67
        const val KEY_MOVE_END = 123
        const val KEY_ESCAPE = 111

        val KEYEVENTS = mapOf(
            "back" to 4,
            "home" to 3,
            "recents" to 187,
            "enter" to 66,
            "delete" to 67,
            "volume_up" to 24,
            "volume_down" to 25,
            "power" to 26,
            "tab" to 61,
            "escape" to 111,
            "dpad_up" to 19,
            "dpad_down" to 20,
            "dpad_left" to 21,
            "dpad_right" to 22,
            "dpad_center" to 23,
            "search" to 84,
            "camera" to 27,
            "focus" to 80,
            "menu" to 82,
        )

        val DIRECTIONS = setOf("down", "up", "left", "right")

        /** Screen-fraction swipe vectors for scroll_until (relative to a 1000-unit canvas). */
        val swipeVectors = mapOf(
            "down" to listOf(540, 1700, 540, 700),
            "up" to listOf(540, 700, 540, 1700),
            "left" to listOf(900, 1200, 180, 1200),
            "right" to listOf(180, 1200, 900, 1200),
        )
    }
}
