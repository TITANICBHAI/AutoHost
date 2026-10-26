package app.autohost.capabilities.a11y

/**
 * Pure traversal policy consulted by [UiTreeBuilder] on every node (plan §5.7). Holds the two failure modes
 * that killed early UI dumps: unbounded depth and cyclic references ("a child that references an ancestor is
 * skipped"). The builder wraps every framework getter; the guard decides *whether to descend*.
 *
 * The guard is deliberately framework-free: the builder identifies nodes by a stable key (identity hash) and
 * reports depth, so the whole policy is unit-testable on the JVM.
 */
class TraversalGuard(
    private val maxDepth: Int = MAX_DEPTH,
    private val maxNodes: Int = MAX_NODES,
) {
    enum class Verdict { DESCEND, RECORD_ONLY, SKIP }

    /** Decision for one node: record it, record without descending, or skip it entirely. */
    sealed class Decision {
        object Record : Decision()
        data class RecordWithoutChildren(val reason: String) : Decision()
        data class Skip(val reason: String) : Decision()
    }

    private val onPath = HashSet<Int>()
    private var visited = 0

    /** Called before a node is recorded. [key] must be the node's identity along the *active path*. */
    @Synchronized
    fun enter(key: Int, depth: Int): Decision = when {
        depth > maxDepth -> Decision.Skip("DEPTH_CAP")
        key in onPath -> Decision.Skip("CYCLE") // child references an ancestor: record nothing, descend nowhere
        visited >= maxNodes -> Decision.Skip("NODE_BUDGET")
        else -> {
            visited++
            onPath.add(key)
            Decision.Record
        }
    }

    /** Marks the node's child scan as cut short (child getter failed or returned null). */
    @Synchronized
    fun markTruncated() {
        truncated = true
    }

    /** Called when leaving a node's subtree (after its children were walked). */
    @Synchronized
    fun exit(key: Int) {
        onPath.remove(key)
    }

    @Synchronized
    fun truncated(): Boolean = truncated

    @Synchronized
    fun reset() {
        onPath.clear()
        visited = 0
        truncated = false
    }

    private var truncated = false

    companion object {
        const val MAX_DEPTH = 128
        const val MAX_NODES = 4_000
    }
}
