package app.autohost.core.model

/**
 * Evaluates a [Selector] against a flat snapshot. Pure and deterministic.
 *
 * Contract (shared with the Python reference in sdk-python/autohost_sim/match.py):
 * - an empty label never matches (so a blank config value cannot select everything);
 * - text matching is normalised (see [TextNormalizer]); regex runs on the raw value;
 * - resource ids match the full id or the short name after ":id/" when the label has no ':';
 * - results are in document order for `dfs`; `bfs` orders shallowest-first (then document order);
 * - when a node and one of its descendants both match, only the deepest is kept;
 * - `nth` is applied after the deepest-match rule and the ordering above.
 */
object SelectorEngine {
    fun findAll(nodes: List<UiNode>, selector: Selector): List<UiNode> {
        val matches = raw(nodes, selector)
        val ordered = if (selector.algorithm == "bfs") matches.sortedBy { it.depth * 1_000_000 + it.index } else matches
        val deepest = ordered.filter { m -> ordered.none { o -> o.index != m.index && isAncestor(nodes, m, o) } }
        val nth = selector.nth ?: return deepest
        return deepest.drop(nth).take(1)
    }

    fun findFirst(nodes: List<UiNode>, selector: Selector): UiNode? = findAll(nodes, selector).firstOrNull()

    /** Matches without the deepest-node rule, ordering or nth: used for relation anchors and nested selectors. */
    private fun raw(nodes: List<UiNode>, selector: Selector): List<UiNode> {
        val pool: List<UiNode> = selector.within?.let { w ->
            val parents = raw(nodes, w)
            if (parents.isEmpty()) emptyList() else nodes.filter { n -> parents.any { p -> isAncestor(nodes, p, n) } }
        } ?: nodes
        return pool.filter { matchesSelector(it, selector, nodes) }
    }

    private fun matchesSelector(node: UiNode, selector: Selector, all: List<UiNode>): Boolean {
        if (selector.terms.isEmpty()) return true
        return if (selector.op == "or") selector.terms.any { matchesTerm(node, it, all) }
        else selector.terms.all { matchesTerm(node, it, all) }
    }

    private fun matchesTerm(node: UiNode, term: SelectorTerm, all: List<UiNode>): Boolean {
        term.text?.let { return matches(node.text, it) }
        term.desc?.let { return matches(node.desc, it) }
        term.id?.let { return matchesId(node.id, it) }
        term.cls?.let { return matches(node.cls, it, normalise = false) }
        term.pkg?.let { return matches(node.pkg, it, normalise = false) }
        term.state?.let { st -> return st.all { (flag, want) -> node.has(flag) == want } }
        term.bounds?.let { b -> return boundsHold(node.bounds, b) }
        term.depth?.let { return node.depth == it }
        term.drawingOrder?.let { return node.drawingOrder == it }
        term.indexInParent?.let { want ->
            return node.parent >= 0 && all.filter { it.parent == node.parent }.indexOfFirst { it.index == node.index } == want
        }
        term.relation?.let { return relationHolds(node, it, all) }
        term.sel?.let { sub -> return matchesSelector(node, sub, all) }
        return false
    }

    /** `inside`: node bounds within the rect; `overlaps`: rectangles intersect. Open sides are unbounded. */
    internal fun boundsHold(node: Bounds, b: BoundsFilter): Boolean {
        val (l, t, r, bt) = listOf(b.left ?: Int.MIN_VALUE, b.top ?: Int.MIN_VALUE, b.right ?: Int.MAX_VALUE, b.bottom ?: Int.MAX_VALUE)
        return if (b.mode == "overlaps") {
            node.bounds.l < r && node.bounds.r > l && node.bounds.t < bt && node.bounds.b > t
        } else {
            node.bounds.l >= l && node.bounds.t >= t && node.bounds.r <= r && node.bounds.b <= bt
        }
    }

    private fun relationHolds(node: UiNode, rel: Relation, all: List<UiNode>): Boolean {
        if (rel.kind == "index") {
            // The node must be the n-th (0-based) match of the anchor selector in document order — the node
            // itself is a candidate here (unlike the spatial relations, which exclude it).
            val ordered = raw(all, rel.selector).sortedBy { it.index }
            return ordered.getOrNull(rel.n ?: 0)?.index == node.index
        }
        val anchors = raw(all, rel.selector).filter { it.index != node.index }
        return anchors.any { a ->
            when (rel.kind) {
                "below" -> node.bounds.t >= a.bounds.b
                "above" -> node.bounds.b <= a.bounds.t
                "left_of" -> node.bounds.r <= a.bounds.l
                "right_of" -> node.bounds.l >= a.bounds.r
                "child_of" -> isAncestor(all, a, node)
                "contains_child" -> a.parent == node.index
                "contains_descendants" -> isAncestor(all, node, a)
                else -> false
            }
        }
    }

    /** True when [ancestor] is a proper ancestor of [node]. */
    fun isAncestor(all: List<UiNode>, ancestor: UiNode, node: UiNode): Boolean {
        var p = node.parent
        while (p >= 0) {
            if (p == ancestor.index) return true
            p = all[p].parent
        }
        return false
    }

    private fun matches(value: String, m: Match, normalise: Boolean = true): Boolean {
        if (m.labels.isEmpty()) return false
        val cs = m.caseSensitive || !normalise
        val hay = if (cs) TextNormalizer.collapse(value) else TextNormalizer.normalize(value)
        return m.labels.any { label ->
            if (label.isEmpty()) return@any false
            val needle = if (cs) TextNormalizer.collapse(label) else TextNormalizer.normalize(label)
            when (m.mode) {
                "eq" -> hay == needle
                "contains" -> hay.contains(needle)
                "starts_with" -> hay.startsWith(needle)
                "ends_with" -> hay.endsWith(needle)
                "regex" -> runCatching {
                    Regex(label, if (m.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)).containsMatchIn(value)
                }.getOrDefault(false)
                else -> false
            }
        }
    }

    private fun matchesId(nodeId: String, m: Match): Boolean {
        if (matches(nodeId, m, normalise = false)) return true
        val short = nodeId.substringAfter(":id/", "")
        if (short.isEmpty()) return false
        return m.labels.any { l -> l.isNotEmpty() && !l.contains(':') && matches(short, Match(listOf(l), m.mode, true), normalise = false) }
    }
}
