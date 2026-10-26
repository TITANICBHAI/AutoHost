package app.autohost.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire form of a selector: schemas/broker.schema.json#/$defs/Selector. Evaluated by [SelectorEngine]. */
@Serializable
data class Selector(
    val op: String = "and",
    val terms: List<SelectorTerm> = emptyList(),
    val within: Selector? = null,
    val nth: Int? = null,
    /** `dfs` (default) searches depth-first; `bfs` prefers the shallowest match (then document order). */
    val algorithm: String = "dfs",
)

@Serializable
data class Match(
    val labels: List<String>,
    val mode: String = "contains",
    @SerialName("case_sensitive") val caseSensitive: Boolean = false,
)

/** `kind` + anchor `selector`; `n` is only used by the `index` relation (0-based, document order). */
@Serializable
data class Relation(val kind: String, val selector: Selector, val n: Int? = null)

/**
 * Screen-rectangle filter. `mode` is `inside` (default: the node's bounds lie within the given rect) or
 * `overlaps` (any intersection). `null` sides are open.
 */
@Serializable
data class BoundsFilter(
    val left: Int? = null,
    val top: Int? = null,
    val right: Int? = null,
    val bottom: Int? = null,
    val mode: String = "inside",
)

/** Exactly one field is set per term. */
@Serializable
data class SelectorTerm(
    val text: Match? = null,
    val desc: Match? = null,
    val id: Match? = null,
    @SerialName("class") val cls: Match? = null,
    val pkg: Match? = null,
    val state: Map<String, Boolean>? = null,
    val bounds: BoundsFilter? = null,
    val depth: Int? = null,
    @SerialName("index_in_parent") val indexInParent: Int? = null,
    @SerialName("drawing_order") val drawingOrder: Int? = null,
    val relation: Relation? = null,
    val sel: Selector? = null,
)
