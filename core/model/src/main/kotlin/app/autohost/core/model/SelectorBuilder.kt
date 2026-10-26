package app.autohost.core.model

import kotlinx.serialization.json.Json

/**
 * P7-11 (inspector): builds the smallest selector that re-finds one node of a snapshot. Preference
 * order mirrors the flow recorder's authoring advice: a full resource id is the most stable anchor,
 * then visible text, then content description, and only as a last resort class + index-in-parent.
 * The result round-trips through [SelectorEngine] — `SelectorEngine.findAll(snapshot, built)` must
 * return exactly the original node.
 */
object SelectorBuilder {

    /** The selector plus the human-readable "why" (used by the inspector's copy toast). */
    data class Built(val selector: Selector, val basis: String, val json: String)

    fun forNode(node: UiNode, all: List<UiNode>): Built {
        if (node.id.isNotBlank()) {
            val s = Selector(
                op = "and",
                terms = listOf(SelectorTerm(id = Match(labels = listOf(node.id), mode = "eq"))),
            )
            return Built(s, "resource-id", render(s))
        }
        if (node.text.isNotBlank()) {
            val s = Selector(op = "and", terms = listOf(SelectorTerm(text = Match(labels = listOf(node.text), mode = "eq"))))
            if (unique(s, node, all)) return Built(s, "text", render(s))
        }
        if (node.desc.isNotBlank()) {
            val s = Selector(op = "and", terms = listOf(SelectorTerm(desc = Match(labels = listOf(node.desc), mode = "eq"))))
            if (unique(s, node, all)) return Built(s, "content-desc", render(s))
        }
        // Last resort: class + position among the parent's children (the engine's index_in_parent
        // counts ALL siblings, so compute the same way). A text-anchored parent keeps the selector
        // working when sibling order shifts above the target subtree.
        if (node.parent >= 0) {
            val siblings = all.filter { it.parent == node.parent }
            val idx = siblings.indexOfFirst { it.index == node.index }.coerceAtLeast(0)
            val parentText: String? = all.getOrNull(node.parent)?.takeIf { it.text.isNotBlank() }?.text
            val s = if (parentText != null) {
                Selector(
                    op = "and",
                    terms = listOf(
                        SelectorTerm(cls = Match(labels = listOf(node.cls), mode = "eq")),
                        SelectorTerm(indexInParent = idx),
                        SelectorTerm(
                            relation = Relation(
                                kind = "child_of",
                                selector = Selector(terms = listOf(SelectorTerm(text = Match(labels = listOf(parentText), mode = "eq")))),
                            ),
                        ),
                    ),
                )
            } else {
                Selector(
                    op = "and",
                    terms = listOf(
                        SelectorTerm(cls = Match(labels = listOf(node.cls), mode = "eq")),
                        SelectorTerm(indexInParent = idx),
                    ),
                )
            }
            return Built(s, "class+index", render(s))
        }
        // Root node: package + class is the honest best effort.
        val s = Selector(
            op = "and",
            terms = listOf(
                SelectorTerm(pkg = Match(labels = listOf(node.pkg), mode = "eq")),
                SelectorTerm(cls = Match(labels = listOf(node.cls), mode = "eq")),
            ),
        )
        return Built(s, "pkg+class", render(s))
    }

    private fun unique(s: Selector, node: UiNode, all: List<UiNode>): Boolean {
        val matches = SelectorEngine.findRawMatches(all, s)
        return matches.size == 1 && matches.first().index == node.index
    }

    private fun render(selector: Selector): String = Json.encodeToString(Selector.serializer(), selector)
}
