package app.autohost.runtime.agent

import app.autohost.core.common.AhError
import app.autohost.core.common.AhResult
import app.autohost.core.model.Bounds
import app.autohost.core.model.CompactDump
import app.autohost.core.model.DumpFilter
import app.autohost.core.model.UiNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * P13-10 (AGENT_RUNTIME §7.3): the observation the model sees. `device.ui.snapshot`'s `compact`
 * dump prints a re-numbered line index that is NOT a node `ref`, so the model cannot address
 * nodes from it. `AgentView` renders from `nodes[]` instead:
 *
 * ```
 * SCREEN gen=7 app=com.android.settings keyboard=hidden shown=34/87 truncated=no
 * [1] Switch "id" "Do Not Disturb" {clickable,checked} (40,812,1040,940)
 * ```
 *
 * The `[n]` ids are LOCAL to one observation generation; [ViewIndex] maps `n → (ref, pkg,
 * flags, bounds)`. A call that addresses a node id from an older generation is rejected
 * `STALE_OBSERVATION` — no guessing, no hidden retry (§7.3).
 *
 * The keep rule (on-screen, min size 5, visible ratio ≥ 0.01) is `CompactDump`'s, exposed
 * additively in `core:model` — NOT duplicated here. The screen size is injected by the caller
 * (the A4 lesson: never a hardcoded 1080×2400).
 */
class AgentView(
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val maxNodes: Int = 120,
    private val maxChars: Int = 12_000,
) {

    data class Entry(
        val ref: String,
        val pkg: String,
        val flags: Set<String>,
        val bounds: Bounds,
        /** The shown text/desc (the label the user sees) — approval text and label hints use it. */
        val label: String = "",
    )

    /** `n → (ref, pkg, flags, bounds)` for ONE observation generation. */
    data class ViewIndex(val generation: Int, private val entries: Map<Int, Entry>) {
        val size: Int get() = entries.size
        operator fun get(n: Int): Entry? = entries[n]
    }

    data class Rendered(
        val text: String,
        val index: ViewIndex,
        val shown: Int,
        val total: Int,
        val truncated: Boolean,
    )

    fun render(snapshot: JsonObject, generation: Int): Rendered {
        val pkg = snapshot.str("package") ?: ""
        val keyboard = if (snapshot.str("keyboard") == "visible") "visible" else "hidden"
        val nodes = snapshot.nodes()
        val kept = nodes.filter { keep(it) }
        val truncatedByCount = kept.size > maxNodes
        val entries = LinkedHashMap<Int, Entry>()
        val lines = ArrayList<String>()
        for (node in kept) {
            if (entries.size >= maxNodes) break
            val n = entries.size + 1
            entries[n] = entryOf(node)
            lines.add(renderLine(n, node))
        }
        val header = "SCREEN gen=$generation app=${pkg.ifEmpty { "none" }} keyboard=$keyboard " +
            "shown=${entries.size}/${nodes.size} truncated=${if (truncatedByCount) "yes" else "no"}"
        var text = listOf(header).plus(lines).joinToString("\n")
        var charTruncated = false
        if (text.length > maxChars) {
            text = text.take(maxChars)
            charTruncated = true
        }
        return Rendered(
            text = text,
            index = ViewIndex(generation, entries),
            shown = entries.size,
            total = nodes.size,
            truncated = truncatedByCount || charTruncated,
        )
    }

    /**
     * The state-aware observation fingerprint for [StuckDetector]: one line per kept node,
     * `cls|id|text|flags(minus focused)|bounds` — the RM-A4/A2 rule (checked/selected/enabled
     * state included, `focused` excluded: focus flickers on its own and is not a UI change).
     */
    fun fingerprint(snapshot: JsonObject): String =
        snapshot.nodes().filter { keep(it) }.map { node ->
            listOf(
                node.str("class").orEmpty(),
                node.str("id").orEmpty(),
                node.str("text").orEmpty().ifEmpty { node.str("desc").orEmpty() },
                flagsOf(node).filter { it != "focused" }.sorted().joinToString(","),
                boundsOf(node).let { "${it.l},${it.t},${it.r},${it.b}" },
            ).joinToString("|")
        }.sorted().joinToString("\n")

    // -- internals -------------------------------------------------------------------------------

    private fun keep(node: JsonObject): Boolean = CompactDump.keep(
        node.toUiNode(),
        DumpFilter(),
        screenWidth,
        screenHeight,
    )

    private fun entryOf(node: JsonObject): Entry {
        val password = "password" in flagsOf(node)
        return Entry(
            ref = node.str("ref").orEmpty(),
            pkg = node.str("pkg").orEmpty(),
            flags = flagsOf(node).toSet(),
            bounds = boundsOf(node),
            label = if (password) "" else node.str("text").orEmpty().ifEmpty { node.str("desc").orEmpty() },
        )
    }

    private fun renderLine(n: Int, node: JsonObject): String {
        val cls = node.str("class").orEmpty().substringAfterLast('.')
        val id = node.str("id").orEmpty()
        val password = "password" in flagsOf(node)
        val value = if (password) "{password}" else node.str("text").orEmpty().ifEmpty { node.str("desc").orEmpty() }
        val flags = flagsOf(node)
        val flagOrder = listOf("clickable", "checked", "editable", "scrollable", "focused", "selected")
        val ordered = flagOrder.filter { it in flags } + (flags - flagOrder.toSet()).sorted()
        val b = boundsOf(node)
        return "[$n] $cls ${clip(id)} ${clip(value)} {${ordered.joinToString(",")}} " +
            "(${b.l},${b.t},${b.r},${b.b})"
    }

    private fun JsonObject.nodes(): List<JsonObject> =
        (this["nodes"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    private fun flagsOf(node: JsonObject): List<String> =
        (node["flags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

    private fun boundsOf(node: JsonObject): Bounds {
        val arr = (node["bounds"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        return if (arr != null && arr.size == 4) Bounds(arr[0], arr[1], arr[2], arr[3]) else Bounds(0, 0, 0, 0)
    }

    /** Builds the `UiNode` the shared keep rule needs, from a snapshot node summary. */
    private fun JsonObject.toUiNode(): UiNode = UiNode(
        index = str("index")?.toIntOrNull() ?: 0,
        parent = -1,
        depth = 0,
        pkg = str("pkg").orEmpty(),
        cls = str("class").orEmpty(),
        id = str("id").orEmpty(),
        text = str("text").orEmpty(),
        desc = str("desc").orEmpty(),
        bounds = boundsOf(this),
        flags = flagsOf(this).toSet(),
    )

    private fun clip(value: String): String {
        val v = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return if (v.length <= MAX_VALUE) "\"$v\"" else "\"${v.take(MAX_VALUE - 1)}\u2026\""
    }

    companion object {
        private const val MAX_VALUE = 80

        /**
         * Resolves a tool call's node id against the CURRENT generation. An id that existed in the
         * PREVIOUS generation but not now → `STALE_OBSERVATION` (the screen moved under the model);
         * an id that never existed → `INVALID_ARGS`. Both fail closed. Stateless — the pipeline
         * (P13-11) resolves through it without an [AgentView] instance.
         */
        fun resolveForCall(current: ViewIndex, previous: ViewIndex?, n: Int): AhResult<Entry> {
            current[n]?.let { return AhResult.ok(it) }
            if (previous != null && previous[n] != null) {
                return AhResult.err(
                    AhError.of(
                        app.autohost.core.model.ErrorCode.INVALID_ARGS,
                        "node [$n] was in generation ${previous.generation} but not in the current " +
                            "observation (generation ${current.generation}) — re-observe and use the new ids",
                        reason = "STALE_OBSERVATION",
                    ),
                )
            }
            return AhResult.err(
                AhError.of(
                    app.autohost.core.model.ErrorCode.INVALID_ARGS,
                    "unknown node id [$n] for the current observation (generation ${current.generation}, " +
                        "ids are local to one screen)",
                ),
            )
        }
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
