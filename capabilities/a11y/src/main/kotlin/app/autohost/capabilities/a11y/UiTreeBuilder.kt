package app.autohost.capabilities.a11y

import android.view.accessibility.AccessibilityNodeInfo
import app.autohost.core.model.Bounds
import app.autohost.core.model.UiNode

/**
 * Flattens an accessibility tree into the snapshot form `:core:model` works with: a depth-first list of
 * [UiNode] plus a parallel list of [AccessibilityNodeInfo] handles (same index = same node). Every framework
 * getter is wrapped — an accessibility object can throw `RuntimeException` at any moment when its window
 * dies mid-traversal. [TraversalGuard] owns the depth cap, the node budget and cycle detection; password
 * fields never expose text.
 */
object UiTreeBuilder {

    data class Tree(
        val nodes: List<UiNode>,
        val infos: List<AccessibilityNodeInfo?>,
        val windowIds: List<Int>,
        val truncated: Boolean,
    )

    fun build(root: AccessibilityNodeInfo, pkg: String, guard: TraversalGuard = TraversalGuard()): Tree {
        guard.reset()
        val nodes = ArrayList<UiNode>()
        val infos = ArrayList<AccessibilityNodeInfo?>()
        val windowIds = ArrayList<Int>()
        walk(root, pkg, depth = 0, parentIndex = -1, nodes = nodes, infos = infos, windowIds = windowIds, guard = guard)
        return Tree(nodes, infos, windowIds, guard.truncated())
    }

    private fun walk(
        node: AccessibilityNodeInfo?,
        pkg: String,
        depth: Int,
        parentIndex: Int,
        nodes: ArrayList<UiNode>,
        infos: ArrayList<AccessibilityNodeInfo?>,
        windowIds: ArrayList<Int>,
        guard: TraversalGuard,
    ) {
        if (node == null) return
        val key = System.identityHashCode(node)
        when (val decision = guard.enter(key, depth)) {
            is TraversalGuard.Decision.Skip -> {
                guard.markTruncated()
                return
            }
            is TraversalGuard.Decision.RecordWithoutChildren -> {
                guard.markTruncated()
                return // budget/depth hit between record and descend: drop the node for consistency
            }
            TraversalGuard.Decision.Record -> Unit
        }
        try {
            val rect = safeBounds(node)
            val password = safeBool { node.isPassword }
            val uiNode = UiNode(
                index = nodes.size,
                parent = parentIndex,
                depth = depth,
                pkg = safePkg(node) ?: pkg,
                cls = safeClass(node) ?: "",
                id = safeId(node) ?: "",
                text = if (password) "" else safeText(node),
                desc = if (password) "" else safeDesc(node),
                bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
                flags = flagsOf(node, password),
                drawingOrder = safeInt({ node.drawingOrder }, -1),
            )
            nodes.add(uiNode)
            infos.add(node)
            windowIds.add(safeWindowId(node))
            val myIndex = uiNode.index

            for (i in 0 until safeChildCount(node)) {
                val child = try {
                    node.getChild(i)
                } catch (e: RuntimeException) {
                    null
                }
                if (child == null) {
                    guard.markTruncated()
                    continue
                }
                walk(child, pkg, depth + 1, myIndex, nodes, infos, windowIds, guard)
            }
        } finally {
            guard.exit(key)
        }
    }

    private fun flagsOf(node: AccessibilityNodeInfo, password: Boolean): Set<String> = buildSet {
        if (safeBool { node.isClickable }) add("clickable")
        if (safeBool { node.isCheckable }) add("checkable")
        if (safeBool { node.isChecked }) add("checked")
        if (safeBool { node.isEditable }) add("editable")
        if (safeBool { node.isScrollable }) add("scrollable")
        if (safeBool { node.isFocused }) add("focused")
        if (safeBool { node.isSelected }) add("selected")
        if (password) add("password")
        if (safeBool { node.isEnabled }) add("enabled")
        if (safeBool { node.isVisibleToUser }) add("visible")
    }

    internal fun safeText(node: AccessibilityNodeInfo): String = try {
        node.text?.toString() ?: ""
    } catch (e: RuntimeException) {
        ""
    }

    internal fun safeDesc(node: AccessibilityNodeInfo): String = try {
        node.contentDescription?.toString() ?: ""
    } catch (e: RuntimeException) {
        ""
    }

    internal fun safeId(node: AccessibilityNodeInfo): String? = try {
        node.viewIdResourceName
    } catch (e: RuntimeException) {
        null
    }

    internal fun safeClass(node: AccessibilityNodeInfo): String? = try {
        node.className?.toString()
    } catch (e: RuntimeException) {
        null
    }

    internal fun safePkg(node: AccessibilityNodeInfo): String? = try {
        node.packageName?.toString()
    } catch (e: RuntimeException) {
        null
    }

    internal fun safeChildCount(node: AccessibilityNodeInfo): Int = try {
        node.childCount
    } catch (e: RuntimeException) {
        0
    }

    internal fun safeBounds(node: AccessibilityNodeInfo): android.graphics.Rect = try {
        android.graphics.Rect().also { node.getBoundsInScreen(it) }
    } catch (e: RuntimeException) {
        android.graphics.Rect()
    }

    internal fun safeWindowId(node: AccessibilityNodeInfo): Int = try {
        node.windowId
    } catch (e: RuntimeException) {
        -1
    }

    internal fun safeInt(read: () -> Int, fallback: Int): Int = try {
        read()
    } catch (e: RuntimeException) {
        fallback
    }

    internal fun safeBool(read: () -> Boolean): Boolean = try {
        read()
    } catch (e: RuntimeException) {
        false
    }
}
