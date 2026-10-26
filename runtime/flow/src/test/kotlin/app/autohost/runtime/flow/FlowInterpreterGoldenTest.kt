package app.autohost.runtime.flow

import app.autohost.core.model.Selector
import app.autohost.core.model.SelectorEngine
import app.autohost.core.model.UiNode
import app.autohost.core.model.Bounds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Python reference (sdk-python/autohost_ref/flows.py) produced testing/golden/flow_vectors.json;
 * this Kotlin interpreter must agree with it case by case — outcome, exact broker call log and
 * event sequence (same contract as ScheduleEngineGoldenTest). VectorDevice below implements the
 * data-only device model documented in tools/gen_flow_vectors.py, mirroring the simulator
 * (autohost_sim/device.py): same screens model, same launch/click/wait/prompt behaviour.
 */
class FlowInterpreterGoldenTest {
    private val vectors: JsonObject by lazy { Json.parseToJsonElement(repoFile("testing/golden/flow_vectors.json").readText()).jsonObject }

    @Test fun `the vector file holds the documented contract`() {
        val cases = vectors.getValue("cases").jsonArray
        assertTrue("no golden cases found", cases.size >= 44)
    }

    @Test fun `every run case matches the python reference`() {
        for (case in vectors.getValue("cases").jsonArray) {
            val obj = case.jsonObject
            if (obj.getValue("kind").jsonPrimitive.content != "run") continue
            val name = obj.getValue("name").jsonPrimitive.content
            replayRunCase(name, obj)
        }
    }

    @Test fun `every parse error case fails with the same message`() {
        for (case in vectors.getValue("cases").jsonArray) {
            val obj = case.jsonObject
            if (obj.getValue("kind").jsonPrimitive.content != "parse_error") continue
            val name = obj.getValue("name").jsonPrimitive.content
            val expected = obj.getValue("expected").jsonObject.getValue("error").jsonPrimitive.content
            val error = runCatching { FlowParser.fromJson(obj.getValue("flow").jsonObject) }
                .exceptionOrNull()
            assertTrue("case $name: expected a parse error", error is FlowParseException)
            assertEquals("parse_error case $name", expected, error!!.message)
        }
    }

    // --- replay --------------------------------------------------------------------------

    private fun replayRunCase(name: String, case: JsonObject) {
        val device = VectorDevice(case.getValue("device").jsonObject)
        val failFirst = (case["device"]!!.jsonObject["fail_first"] as? JsonObject)
            ?.mapKeys { (k, _) -> k.substringBeforeLast('.') to k.substringAfterLast('.') }
            ?.mapValues { (_, v) ->
                (v as JsonArray).map { spec ->
                    BrokerCallException(
                        spec.jsonObject.getValue("code").jsonPrimitive.content,
                        spec.jsonObject.getValue("message").jsonPrimitive.content,
                        spec.jsonObject.getValue("retryable").jsonPrimitive.content == "true",
                    )
                }.toMutableList()
            } ?: emptyMap()
        val broker = RecordingBroker(device, failFirst)
        val events = mutableListOf<JsonObject>()
        val subflows = (case["subflows"] as? JsonObject)?.mapValues { (_, doc) -> FlowParser.fromJson(doc.jsonObject) } ?: emptyMap()
        val ex = FlowExec(
            broker,
            emit = { events.add(it) },
            checkCancel = { },
            sleep = { ms -> device.sleep(ms) },
            apiLevel = case["api_level"]?.jsonPrimitive?.content?.toInt() ?: 29,
            subflows = subflows,
        )
        val outcome = FlowInterpreter.runFlow(
            FlowParser.fromJson(case.getValue("flow").jsonObject),
            ex,
            config = case["config"]?.jsonObject ?: JsonObject(emptyMap()),
            profile = case["profile"]?.jsonObject ?: JsonObject(emptyMap()),
            env = case["env"]?.jsonObject ?: JsonObject(emptyMap()),
            secrets = (case["secrets"] as? JsonObject)?.mapValues { (_, v) -> v.jsonPrimitive.content } ?: emptyMap(),
            run = case["run"]?.jsonObject ?: buildJsonObject { put("attempt", 1) },
        )
        val expected = case.getValue("expected").jsonObject
        assertEquals("case $name: outcome", expected.getValue("outcome").jsonObject, outcome.toJson())
        val actualCalls = buildJsonArray {
            for ((capability, method, args) in broker.calls) {
                add(buildJsonArray {
                    add(JsonPrimitive(capability))
                    add(JsonPrimitive(method))
                    add(args)
                })
            }
        }
        assertEquals("case $name: broker call log", expected.getValue("calls").jsonArray, actualCalls)
        assertEquals("case $name: event sequence", expected.getValue("events").jsonArray, buildJsonArray { events.forEach { add(it) } })
    }

    private class RecordingBroker(
        private val device: VectorDevice,
        failFirst: Map<Pair<String, String>, MutableList<BrokerCallException>>,
    ) : FlowBroker {
        val calls = mutableListOf<Triple<String, String, JsonObject>>()
        private val failFirst = failFirst

        override suspend fun call(capability: String, method: String, args: JsonObject): JsonObject {
            calls.add(Triple(capability, method, args))
            failFirst[capability to method]?.removeFirstOrNull()?.let { throw it }
            return try {
                device.dispatch(capability, method, args)
            } catch (error: VectorError) {
                throw BrokerCallException(error.code, error.message ?: "", error.retryable)
            }
        }
    }

    private class VectorError(val code: String, message: String, val retryable: Boolean = false) :
        Exception(message.ifEmpty { code })

    /**
     * Data-only fake device mirroring autohost_sim.FakeDevice for the dispatch methods the
     * vectors exercise. Screens keep their JSON form; node text edits go into an overlay so
     * set_text persists across rebuilds (like the simulator's raw mutation).
     */
    private class VectorDevice(device: JsonObject) {
        private val screens: Map<String, JsonObject> =
            (device.getValue("screens").jsonObject).mapValues { (_, v) -> v.jsonObject }
        private val launchMap = (device["launch_map"]?.jsonObject ?: JsonObject(emptyMap()))
        private val installed: Set<String> =
            (device["installed"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet()
                ?: launchMap.keys
        private val promptAnswers = (device["prompt_answers"]?.jsonObject ?: JsonObject(emptyMap()))
        private val screenshotEnabled = device["screenshot"]?.jsonPrimitive?.content != "false"
        var current: String? = device["start"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        private val history = mutableListOf<String>()
        private var swipes = 0
        private val textOverlay = mutableMapOf<String, String>()
        private val notifications = mutableListOf<JsonObject>()
        private val screenshots = mutableListOf<JsonObject>()
        private var clipboard: String? = null
        var nowMs = 0L

        fun sleep(ms: Long) {
            nowMs += ms
        }

        private fun screenPackage(): String? =
            current?.let { screens[it]?.get("package")?.takeIf { p -> p !is JsonNull }?.jsonPrimitive?.content }

        private fun rawNodes(): List<Pair<Int, JsonObject>> {
            val spec = screens[current] ?: return emptyList()
            val visible = (spec["nodes"] as? JsonArray ?: JsonArray(emptyList())).mapIndexed { i, n -> i to n.jsonObject }
            val hidden = spec["hidden_nodes"] as? JsonArray
            val reveal = spec["reveal_after"]?.jsonPrimitive?.content?.toIntOrNull()
            val extra = if (hidden != null && reveal != null && swipes >= reveal) {
                hidden.mapIndexed { i, n -> (visible.size + i) to n.jsonObject }
            } else {
                emptyList()
            }
            return visible + extra
        }

        private fun flag(raw: JsonObject, name: String): Boolean = when (name) {
            "enabled" -> raw["enabled"]?.jsonPrimitive?.content != "false"
            else -> raw[name]?.jsonPrimitive?.content == "true"
        }

        private fun uiNodes(): List<UiNode> = rawNodes().map { (i, raw) ->
            val bounds = (raw["bounds"] as? JsonArray)?.map { it.jsonPrimitive.content.toInt() }
                ?: listOf(0, 120 * i, 1080, 120 * i + 100)
            val flags = buildSet {
                for (name in listOf("clickable", "checked", "editable", "scrollable", "focused", "selected")) {
                    if (flag(raw, name)) add(name)
                }
                if (flag(raw, "enabled")) add("enabled")
            }
            UiNode(
                index = i, parent = -1, depth = 0,
                pkg = screenPackage() ?: "",
                cls = raw["cls"]?.jsonPrimitive?.takeIf { it is JsonPrimitive && it.isString }?.content
                    ?: "android.view.View",
                id = raw["id"]?.jsonPrimitive?.content ?: "",
                text = textOverlay["$current#$i"]
                    ?: raw["text"]?.jsonPrimitive?.takeIf { it is JsonPrimitive && it.isString }?.content ?: "",
                desc = raw["desc"]?.jsonPrimitive?.takeIf { it is JsonPrimitive && it.isString }?.content ?: "",
                bounds = Bounds(bounds[0], bounds[1], bounds[2], bounds[3]),
                flags = flags,
            )
        }

        private fun select(wire: JsonObject): List<UiNode> {
            val selector = Json.decodeFromString(Selector.serializer(), wire.toString())
            return SelectorEngine.findAll(uiNodes(), selector)
        }

        private fun summary(node: UiNode): JsonObject = buildJsonObject {
            put("ref", "${node.index}")
            put("pkg", node.pkg)
            put("id", node.id)
            put("text", node.text)
            put("desc", node.desc)
            put("index", node.index)
        }

        private fun goto(name: String, push: Boolean = true, reset: Boolean = false) {
            if (name !in screens) throw VectorError("INTERNAL", "sim screen '$name' does not exist")
            if (reset) history.clear() else if (push && current != null) history.add(current!!)
            current = name
            swipes = 0
        }

        private fun resolveSelector(target: JsonObject): UiNode {
            val wire = target.getValue("selector").jsonObject
            val found = select(wire)
            if (found.isEmpty()) throw VectorError("NOT_FOUND", "no element matches the selector")
            return found[0]
        }

        private fun activate(node: UiNode) {
            val spec = screens[current] ?: return
            val onClick = spec["on_click"] as? JsonObject ?: return
            val byId = onClick[node.id] as? JsonPrimitive
            val byText = onClick[node.text] as? JsonPrimitive
            val target = (byId ?: byText)?.content ?: return
            goto(target, push = true)
        }

        fun dispatch(capability: String, method: String, args: JsonObject): JsonObject {
            return when (capability) {
                "device.ui" -> deviceUi(method, args)
                "device.app" -> deviceApp(method, args)
                "device.screenshot" -> {
                    if (!screenshotEnabled) {
                        throw VectorError("CAPABILITY_UNAVAILABLE", "screenshots are not available on this device")
                    }
                    val path = "/sim/evidence/shot_${screenshots.size}.png"
                    screenshots.add(buildJsonObject { })
                    buildJsonObject {
                        put("path", path); put("width", 1080); put("height", 2400); put("provider", "sim")
                    }
                }
                "device.screen" -> if (method == "is_on") buildJsonObject { put("on", true) }
                else buildJsonObject { put("unlocked", true); put("secure", false) }
                "device.ime" -> when (method) {
                    "commit_text" -> buildJsonObject { put("outcome", "VERIFIED"); put("strategy", "IME") }
                    "clipboard_get" -> buildJsonObject { put("text", clipboard ?: JsonNull) }
                    else -> {
                        clipboard = args.getValue("text").jsonPrimitive.content
                        buildJsonObject { put("ok", true) }
                    }
                }
                "notify.post" -> {
                    notifications.add(args)
                    buildJsonObject { put("id", notifications.size) }
                }
                "ui.prompt" -> when (method) {
                    "toast" -> buildJsonObject { put("ok", true) }
                    else -> {
                        val title = args.getValue("title").jsonPrimitive.content
                        val answer = promptAnswers[title]?.jsonPrimitive?.content
                        if (answer == null) {
                            buildJsonObject { put("answered", false); put("reason", "timeout") }
                        } else {
                            buildJsonObject {
                                put("answered", true)
                                put("answer", answer)
                                put("confirmed", answer == "yes")
                            }
                        }
                    }
                }
                else -> throw VectorError("CAPABILITY_UNAVAILABLE", "$capability is not simulated")
            }
        }

        private fun deviceUi(method: String, args: JsonObject): JsonObject {
            return when (method) {
                "find" -> {
                    val limit = (args["limit"] as? JsonPrimitive)?.content?.toInt() ?: 20
                    buildJsonObject {
                        put("nodes", buildJsonArray {
                            select(args.getValue("selector").jsonObject).take(limit).forEach { add(summary(it)) }
                        })
                    }
                }
                "wait_for" -> waitFor(args)
                "scroll_until" -> scrollUntil(args)
                "wait_for_settle" -> buildJsonObject { put("settled", true); put("waited_ms", 0) }
                "is_window_updating" -> buildJsonObject { put("updating", false) }
                "keyboard_visible" -> buildJsonObject { put("visible", false) }
                "hide_keyboard" -> buildJsonObject { put("ok", true) }
                "dump" -> buildJsonObject { put("text", "screen=${current ?: "none"}") }
                "click", "long_click" -> click(args, longPress = method == "long_click")
                "set_text" -> setText(args)
                "press" -> press(args)
                "swipe" -> {
                    sleep(100)
                    buildJsonObject { put("ok", true) }
                }
                "tap" -> {
                    sleep(100)
                    buildJsonObject { put("method", "COORDINATE") }
                }
                else -> throw VectorError("INVALID_ARGS", "device.ui.$method is not simulated")
            }
        }

        private fun waitFor(args: JsonObject): JsonObject {
            val timeout = (args["timeout_ms"] as? JsonPrimitive)?.content?.toLong() ?: 0L
            val polls = maxOf(1, timeout / 250)
            repeat(polls.toInt()) {
                val found = select(args.getValue("selector").jsonObject)
                if (found.isNotEmpty()) return buildJsonObject { put("node", summary(found[0])) }
                sleep(250)
            }
            return buildJsonObject { put("node", JsonNull) }
        }

        private fun scrollUntil(args: JsonObject): JsonObject {
            val maxSwipes = (args["max_swipes"] as? JsonPrimitive)?.content?.toInt() ?: 10
            while (true) {
                val found = select(args.getValue("selector").jsonObject)
                if (found.isNotEmpty()) {
                    return buildJsonObject { put("found", true); put("node", summary(found[0])) }
                }
                if (swipes >= maxSwipes) {
                    return buildJsonObject { put("found", false); put("node", JsonNull) }
                }
                swipes += 1
                sleep(200)
            }
        }

        private fun click(args: JsonObject, longPress: Boolean): JsonObject {
            val node = resolveSelector(args.getValue("target").jsonObject)
            val before = current
            val methodName = if (node.has("clickable")) "NODE_ACTION" else "COORDINATE"
            activate(node)
            var changed = current != before
            if (!longPress && args["retry_if_no_change"]?.jsonPrimitive?.content == "true" && !changed) {
                activate(node)
                changed = current != before
            }
            if (longPress) {
                return buildJsonObject { put("method", methodName) }
            }
            val verified = changed && methodName == "NODE_ACTION"
            return buildJsonObject { put("method", methodName); put("verified", verified) }
        }

        private fun setText(args: JsonObject): JsonObject {
            val node = resolveSelector(args.getValue("target").jsonObject)
            if (!node.has("editable")) throw VectorError("TEXT_INPUT_REJECTED", "field is not editable")
            textOverlay["$current#${node.index}"] = args.getValue("text").jsonPrimitive.content
            return buildJsonObject { put("outcome", "VERIFIED"); put("strategy", "SET_TEXT") }
        }

        private fun press(args: JsonObject): JsonObject {
            val key = args.getValue("key").jsonPrimitive.content
            if (key == "back" && history.isNotEmpty()) {
                current = history.removeAt(history.size - 1)
                return buildJsonObject { put("ok", true) }
            }
            val consumable = setOf("home", "recents", "enter", "notifications", "quick_settings", "lock")
            return buildJsonObject { put("ok", key in consumable) }
        }

        private fun deviceApp(method: String, args: JsonObject): JsonObject {
            return when (method) {
                "query" -> buildJsonObject {
                    put("installed", args.getValue("package").jsonPrimitive.content in installed)
                }
                "list" -> buildJsonObject { put("packages", buildJsonArray { }) }
                "foreground" -> buildJsonObject {
                    put("package", screenPackage() ?: JsonNull)
                    put("activity", current ?: JsonNull)
                }
                "wait_foreground" -> {
                    val pkg = args.getValue("package").jsonPrimitive.content
                    val timeout = (args["timeout_ms"] as? JsonPrimitive)?.content?.toLong() ?: 0L
                    repeat(maxOf(1, timeout / 250).toInt()) {
                        if (screenPackage() == pkg) return buildJsonObject { put("ok", true) }
                        sleep(250)
                    }
                    throw VectorError("TIMEOUT", "$pkg did not reach the foreground", retryable = true)
                }
                "launch" -> {
                    val pkg = args.getValue("package").jsonPrimitive.content
                    if (pkg !in launchMap || pkg !in installed) {
                        throw VectorError("NOT_FOUND", "$pkg is not installed")
                    }
                    goto(launchMap.getValue(pkg).jsonPrimitive.content, reset = true)
                    buildJsonObject { put("result", "LAUNCHED_VERIFIED"); put("strategy", "sim") }
                }
                "stop" -> {
                    if (screenPackage() == args.getValue("package").jsonPrimitive.content) {
                        current = null
                        history.clear()
                    }
                    buildJsonObject { put("result", "VERIFIED"); put("method", "sim") }
                }
                else -> throw VectorError("INVALID_ARGS", "device.app.$method is not simulated")
            }
        }
    }

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("repo file not found from ${System.getProperty("user.dir")}: $relative")
    }
}
