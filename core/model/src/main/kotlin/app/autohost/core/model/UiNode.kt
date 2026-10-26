package app.autohost.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class Bounds(val l: Int, val t: Int, val r: Int, val b: Int) {
    val width: Int get() = r - l
    val height: Int get() = b - t
    val centerX: Int get() = (l + r) / 2
    val centerY: Int get() = (t + b) / 2

    fun inside(o: Bounds): Boolean = l >= o.l && t >= o.t && r <= o.r && b <= o.b

    fun toJson(): JsonArray = JsonArray(listOf(l, t, r, b).map { JsonPrimitive(it) })
}

/**
 * One node of a UI snapshot. Snapshots are flat lists in depth-first order; [parent] is the index of the parent
 * in that list (-1 for a root). Password fields have empty [text] by construction.
 */
data class UiNode(
    val index: Int,
    val parent: Int,
    val depth: Int,
    val pkg: String,
    val cls: String,
    val id: String,
    val text: String,
    val desc: String,
    val bounds: Bounds,
    val flags: Set<String>,
    /** Framework drawing order inside the parent (higher = drawn later / on top); -1 when unknown. */
    val drawingOrder: Int = -1,
) {
    fun has(flag: String): Boolean = flag in flags

    /** Stable text identifying this node, used to detect that a handle now points at a different node. */
    val signature: String get() = "$pkg|$cls|$id|$text|$desc"

    fun summary(ref: String): JsonObject = buildJsonObject {
        put("ref", ref)
        put("pkg", pkg)
        put("class", cls)
        put("id", id)
        put("text", text)
        put("desc", desc)
        put("bounds", bounds.toJson())
        put("flags", JsonArray(flags.sorted().map { JsonPrimitive(it) }))
        put("index", index)
    }
}
