package app.autohost.runtime.flow

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P6-07: the recorder's core — device interaction events in, flow steps out (plan §7.9).
 * Pure JVM and data-only so it is unit-testable without a device; the Android half (overlay,
 * accessibility event tap) lives in `:app` (P6-09) and feeds [RecordedEvent]s in.
 *
 * Selector ranking by stability (plan): resource-id > content-desc > unique text > relational
 * > bounds. Every rank is verified against the recorded screen's node list — an id that appears
 * twice is not stable, so the recorder falls through. Bounds-only selectors are the coordinate
 * fallback: kept (they replay) but flagged `needsConfirmation` and blocked behind explicit
 * user confirmation in the review UI (G6-e). Password inputs never store text: they become
 * `${secret.INPUT_n}` placeholders that the review UI binds before save.
 */
enum class RecordedKind { CLICK, LONG_CLICK, TEXT, KEY, SCROLL }

/** One visible node as the recorder sees it (semantic fields only; bounds are [l,t,r,b]). */
data class RecordedNode(
    val id: String = "",
    val text: String = "",
    val desc: String = "",
    val cls: String = "android.view.View",
    val bounds: List<Int> = listOf(0, 0, 0, 0),
    val editable: Boolean = false,
    val password: Boolean = false,
    val clickable: Boolean = false,
)

/** The screen at event time — the pre-state snapshot source and the uniqueness oracle. */
data class RecordedScreen(
    val packageName: String,
    val activity: String,
    val nodes: List<RecordedNode>,
)

data class RecordedEvent(
    val kind: RecordedKind,
    val node: RecordedNode? = null, // null for KEY/SCROLL
    val screen: RecordedScreen,
    val tsMs: Long = 0,
    val text: String? = null, // TEXT: committed text
    val key: String? = null, // KEY: back/home/enter/...
    val direction: String? = null, // SCROLL: up/down/left/right
)

data class RecordedStep(
    val command: String,
    val body: JsonObject,
    val label: String,
    val needsConfirmation: Boolean,
    val selectorRank: String,
    val preState: StepSnapshot,
    val elapsedMs: Long,
    val secretNames: List<String> = emptyList(),
)

data class RecorderResult(
    val steps: List<RecordedStep>,
    val warnings: List<String>,
    val startedAtMs: Long,
) {
    /** Renders the sidecar document (`<flow>.recording.json`, ADR-053). */
    fun sidecar(flowName: String, onMismatch: String = "stop", stateTimeoutMs: Long = 3_000): RecordingSidecar =
        RecordingSidecar(
            flowName = flowName,
            recordedAtMs = startedAtMs,
            onMismatch = onMismatch,
            stateTimeoutMs = stateTimeoutMs,
            steps = steps.map {
                RecordingStepMeta(it.label, it.preState, it.needsConfirmation, it.selectorRank, it.elapsedMs, it.secretNames)
            },
        )
}

object Recorder {

    const val SECRET_PLACEHOLDER_PREFIX = "\${secret.INPUT_"

    /** Converts a full session into steps. Events must be in chronological order. */
    fun record(events: List<RecordedEvent>): RecorderResult {
        val warnings = mutableListOf<String>()
        val steps = mutableListOf<RecordedStep>()
        val coalesced = coalesceText(events)
        var lastTs = events.firstOrNull()?.tsMs ?: 0L
        var secretIndex = 0
        var stepNo = 0
        for (event in coalesced) {
            val screen = event.screen
            val snapshot = StepSnapshot(
                packageName = screen.packageName,
                activity = screen.activity,
                keys = screen.nodes.map { StepSnapshot.nodeKey(it.cls, it.id, it.text, it.desc) },
            )
            val elapsedMs = (event.tsMs - lastTs).coerceAtLeast(0)
            lastTs = event.tsMs
            when (event.kind) {
                RecordedKind.CLICK, RecordedKind.LONG_CLICK -> {
                    val node = event.node
                    if (node == null) {
                        warnings.add("click without node info was dropped")
                        continue
                    }
                    val picked = pickSelector(node, screen)
                    if (picked == null) {
                        warnings.add("click on a node with no bounds was dropped (no selector possible)")
                        continue
                    }
                    val command = if (event.kind == RecordedKind.CLICK) "tapOn" else "longPressOn"
                    stepNo += 1
                    val label = "${recorderTag(command)}_$stepNo"
                    val body = buildJsonObject {
                        picked.selector.forEach { (k, v) -> put(k, v) }
                        if (command == "tapOn") put("waitToSettle", true)
                        put("label", label)
                    }
                    steps.add(
                        RecordedStep(command, body, label, picked.needsConfirmation, picked.rank, snapshot, elapsedMs),
                    )
                }
                RecordedKind.TEXT -> {
                    val node = event.node
                    val text = event.text.orEmpty()
                    if (node == null) {
                        warnings.add("text event without node info was dropped")
                        continue
                    }
                    val picked = pickSelector(node, screen)
                    if (picked == null) {
                        warnings.add("text event on a node with no bounds was dropped")
                        continue
                    }
                    stepNo += 1
                    val label = "input_$stepNo"
                    val secrets = mutableListOf<String>()
                    val body = buildJsonObject {
                        put("into", picked.selector)
                        if (node.password) {
                            secretIndex += 1
                            val name = "INPUT_$secretIndex"
                            put("text", "$SECRET_PLACEHOLDER_PREFIX$name}")
                            put("secret", true)
                            secrets.add(name)
                        } else {
                            put("text", text)
                        }
                        put("label", label)
                    }
                    steps.add(
                        RecordedStep("inputText", body, label, picked.needsConfirmation, picked.rank, snapshot, elapsedMs, secrets),
                    )
                }
                RecordedKind.KEY -> {
                    val key = event.key ?: continue
                    if (key !in setOf("back", "home", "enter", "recents")) {
                        warnings.add("key '$key' has no flow command and was dropped")
                        continue
                    }
                    stepNo += 1
                    val label = "press_$stepNo"
                    steps.add(
                        RecordedStep(
                            "pressKey", buildJsonObject { put("key", key); put("label", label) },
                            label, false, "none", snapshot, elapsedMs,
                        ),
                    )
                }
                RecordedKind.SCROLL -> {
                    val direction = event.direction ?: "down"
                    stepNo += 1
                    val label = "scroll_$stepNo"
                    steps.add(
                        RecordedStep(
                            "scroll", buildJsonObject { put("direction", direction); put("label", label) },
                            label, false, "none", snapshot, elapsedMs,
                        ),
                    )
                }
            }
        }
        return RecorderResult(steps, warnings, events.firstOrNull()?.tsMs ?: 0L)
    }

    private fun recorderTag(command: String): String = if (command == "longPressOn") "longpress" else "tap"

    /** Consecutive TEXT events on the same node collapse into one inputText (the last text wins). */
    internal fun coalesceText(events: List<RecordedEvent>): List<RecordedEvent> {
        val out = mutableListOf<RecordedEvent>()
        for (event in events) {
            val previous = out.lastOrNull()
            if (event.kind == RecordedKind.TEXT && previous?.kind == RecordedKind.TEXT &&
                previous.node?.id == event.node?.id && previous.node?.bounds == event.node?.bounds
            ) {
                out[out.size - 1] = event // same field still typing: keep the newest event
            } else {
                out.add(event)
            }
        }
        return out
    }

    data class Picked(val selector: JsonObject, val needsConfirmation: Boolean, val rank: String)

    /**
     * Stability-ranked selector for one node, verified against the full visible-node list.
     * Returns null only when even bounds are missing (nothing can target the node).
     */
    internal fun pickSelector(node: RecordedNode, screen: RecordedScreen): Picked? {
        val bounds = node.bounds
        if (bounds.size != 4) return null
        // 1. resource-id — stable when unique among the visible nodes.
        if (node.id.isNotBlank() && screen.nodes.count { it.id == node.id } == 1) {
            return Picked(buildJsonObject { put("id", node.id) }, needsConfirmation = false, rank = "id")
        }
        // 2. content-desc.
        if (node.desc.isNotBlank() && screen.nodes.count { it.desc == node.desc } == 1) {
            return Picked(buildJsonObject { put("desc", listOf(node.desc).toJsonArray()) }, false, "desc")
        }
        // 3. unique visible text.
        if (node.text.isNotBlank() && screen.nodes.count { it.text == node.text } == 1) {
            return Picked(buildJsonObject { put("text", listOf(node.text).toJsonArray()) }, false, "text")
        }
        // 4. relational: the only node of its class below a uniquely-labelled anchor above it.
        relational(node, screen)?.let { return it }
        // 5. bounds fallback — replays, but it is coordinates: needs confirmation (G6-e).
        return Picked(
            buildJsonObject {
                put("bounds", buildJsonObject {
                    put("left", bounds[0]); put("top", bounds[1]); put("right", bounds[2]); put("bottom", bounds[3])
                })
            },
            needsConfirmation = true,
            rank = "bounds",
        )
    }

    /**
     * Finds an anchor strictly above [node] with unique text or desc, such that exactly one
     * visible node with the same class sits below the anchor (and horizontally overlaps it).
     * Emits `{"cls": <cls>, "below": {"text": [anchor]}}` (or desc-keyed anchor).
     */
    internal fun relational(node: RecordedNode, screen: RecordedScreen): Picked? {
        if (node.cls.isBlank()) return null
        val top = node.bounds[1]
        val anchors = screen.nodes.filter { candidate ->
            val (_, ct, _, cb) = candidate.bounds
            cb <= top && (candidate.text.isNotBlank() || candidate.desc.isNotBlank())
        }
        for (anchor in anchors.sortedByDescending { it.bounds[3] }) { // nearest anchor above first
            val anchorSelector = when {
                anchor.text.isNotBlank() && screen.nodes.count { it.text == anchor.text } == 1 ->
                    buildJsonObject { put("text", listOf(anchor.text).toJsonArray()) }
                anchor.desc.isNotBlank() && screen.nodes.count { it.desc == anchor.desc } == 1 ->
                    buildJsonObject { put("desc", listOf(anchor.desc).toJsonArray()) }
                else -> continue
            }
            val (al, _, ar, _) = anchor.bounds
            val below = screen.nodes.filter { candidate ->
                val (cl, ct, cr, cb) = candidate.bounds
                candidate.cls == node.cls && ct >= anchor.bounds[3] &&
                    cl < ar && cr > al // horizontal overlap with the anchor's column
            }
            if (below.size == 1 && below[0].let { it.bounds == node.bounds }) {
                return Picked(
                    buildJsonObject {
                        put("cls", node.cls)
                        put("below", anchorSelector)
                    },
                    needsConfirmation = false,
                    rank = "relational",
                )
            }
        }
        return null
    }

    /** Renders the recorded session as a flow document (schema 1) ready for FlowParser. */
    fun toFlowDocument(result: RecorderResult, name: String): JsonObject = buildJsonObject {
        put("schema", 1)
        put("name", name)
        put("steps", kotlinx.serialization.json.JsonArray(result.steps.map { step ->
            buildJsonObject {
                put(step.command, step.body)
            }
        }))
    }
    private fun List<String>.toJsonArray() = kotlinx.serialization.json.JsonArray(map { JsonPrimitive(it) })
}
