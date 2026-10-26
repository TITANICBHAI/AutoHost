package app.autohost.capabilities.a11y

import android.view.accessibility.AccessibilityNodeInfo
import app.autohost.core.common.AhResult
import app.autohost.core.common.Clock
import app.autohost.core.model.AhError
import app.autohost.core.model.ErrorCode

/**
 * Keeps node handles from recent snapshots addressable by ref (`s<serial>-<index>`). A ref is only good while
 * the node still matches the signature it was minted with: `refresh()` must succeed and text/bounds must be
 * unchanged, otherwise `STALE_NODE` (HANDOFF §3). TTL 60 s — UI state older than that is never trusted.
 *
 * Handles are run-scoped: [put] takes an optional scope (the run id) and [resolve] rejects a ref minted in
 * a different scope, so one run can never act on another run's handles. The handle may be null (windows that
 * vanished mid-snapshot): such refs resolve but actions must treat a null handle as their own failure.
 */
class NodeRegistry(private val clock: Clock, private val ttlMs: Long = 60_000) {

    class Entry(
        val node: UiNode,
        val info: AccessibilityNodeInfo?,
        val storedMonotonicMs: Long,
        val scope: String?,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private var serial = 0L

    @Synchronized
    fun put(node: UiNode, info: AccessibilityNodeInfo?, scope: String? = null): String {
        serial += 1
        val ref = "s$serial-${node.index}"
        entries[ref] = Entry(node, info, clock.monotonicMs(), scope)
        pruneLocked()
        return ref
    }

    /** Validates the handle: exists, in scope, not expired, refreshed, and still looks like the same node. */
    @Synchronized
    fun resolve(ref: String, scope: String? = null): AhResult<AccessibilityNodeInfo?> {
        val entry = entries[ref]
            ?: return AhResult.err(stale("unknown node ref '$ref'"))
        if (entry.scope != null && scope != null && entry.scope != scope) {
            return AhResult.err(stale("node ref '$ref' belongs to another run"))
        }
        val now = clock.monotonicMs()
        if (now - entry.storedMonotonicMs > ttlMs) {
            entries.remove(ref)
            return AhResult.err(stale("node ref '$ref' expired"))
        }
        val info = entry.info ?: return AhResult.ok(null) // no handle to validate; caller fails honestly
        val refreshed = try {
            info.refresh()
        } catch (e: RuntimeException) {
            false
        }
        if (!refreshed) {
            entries.remove(ref)
            return AhResult.err(stale("node ref '$ref' disappeared before the action"))
        }
        val currentText = UiTreeBuilder.safeText(info)
        val currentDesc = UiTreeBuilder.safeDesc(info)
        val rect = UiTreeBuilder.safeBounds(info)
        val same = currentText == entry.node.text && currentDesc == entry.node.desc &&
            rect.left == entry.node.bounds.l && rect.top == entry.node.bounds.t
        if (!same) {
            entries.remove(ref)
            return AhResult.err(stale("node ref '$ref' now points at different content"))
        }
        return AhResult.ok(info)
    }

    @Synchronized
    fun prune() = pruneLocked()

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clearScope(scope: String): Int {
        val before = entries.size
        entries.values.removeAll { it.scope == scope }
        return before - entries.size
    }

    private fun pruneLocked() {
        val now = clock.monotonicMs()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.storedMonotonicMs > ttlMs) iterator.remove()
        }
    }

    private fun stale(message: String): AhError = AhError.of(ErrorCode.STALE_NODE, message)
}
