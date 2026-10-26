package app.autohost.capabilities.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.autohost.core.common.Clock
import app.autohost.core.model.UiNode

/** One scoped snapshot: the flat node list plus parallel framework handles and window metadata. */
data class A11ySnapshot(
    val tsMs: Long,
    val pkg: String?,
    val keyboardVisible: Boolean,
    val nodes: List<UiNode>,
    val infos: List<AccessibilityNodeInfo?>,
    val windowIds: List<Int> = emptyList(),
    /** Window metadata, highest layer first — input for RootResolver, PopupGuard and ForegroundResolver. */
    val windows: List<RootResolver.WindowMeta> = emptyList(),
    /** Non-null when the tree is empty for a *named, non-error* reason (EMPTY_TREE, NO_READABLE_ROOT). */
    val emptyReason: String? = null,
    val truncated: Boolean = false,
) {
    fun infoAt(index: Int): AccessibilityNodeInfo? = infos.getOrNull(index)

    fun windowIdAt(index: Int): Int = windowIds.getOrNull(index) ?: -1
}

/**
 * Reads the visible UI, filtered by granted packages (A11yScope, HANDOFF §3). Roots come from
 * [RootResolver] (active window + higher-layer application windows); password nodes come back with empty
 * text by construction.
 */
interface A11yReader {
    fun read(packages: Set<String>?): A11ySnapshot

    fun keyboardVisible(packages: Set<String>?): Boolean

    /** Window metadata for guards; empty when the service is disconnected. */
    fun windows(): List<RootResolver.WindowMeta>
}

/** The hardened reader: per-window roots via [RootResolver], keyboard and occluder metadata included. */
class SimpleA11yReader(
    private val service: () -> AccessibilityService?,
    private val clock: Clock,
) : A11yReader {

    override fun read(packages: Set<String>?): A11ySnapshot {
        val active = service() ?: return A11ySnapshot(
            tsMs = clock.nowMs(), pkg = null, keyboardVisible = false,
            nodes = emptyList(), infos = emptyList(), windowIds = emptyList(),
            windows = emptyList(), emptyReason = "NO_ACTIVE_WINDOW", truncated = false,
        )
        val metas = windowMetas(active)
        val activeRoot = try {
            active.rootInActiveWindow
        } catch (e: RuntimeException) {
            null
        }
        val activeRootPkg = activeRoot?.let { UiTreeBuilder.safePkg(it) }
        val decision = RootResolver.resolve(RootResolver.Input(metas, activeRootPkg))
        if (decision is RootResolver.Decision.NoActiveWindow) {
            return A11ySnapshot(
                tsMs = clock.nowMs(), pkg = null, keyboardVisible = keyboardVisible(packages),
                nodes = emptyList(), infos = emptyList(), windowIds = emptyList(),
                windows = metas, emptyReason = "NO_ACTIVE_WINDOW", truncated = false,
            )
        }
        val readable = (decision as RootResolver.Decision.Read).windows
        var keyboardVisible = false
        var truncated = false
        val nodes = ArrayList<UiNode>()
        val infos = ArrayList<AccessibilityNodeInfo?>()
        val windowIds = ArrayList<Int>()
        var primaryPkg: String? = decision.primaryPackage
        var windowsRead = 0

        for (window in readable) {
            val root = if (window.id >= 0) {
                metas.find { it.id == window.id }?.let { rootOf(active, it.id) }
            } else {
                activeRoot
            } ?: continue
            val windowPkg = UiTreeBuilder.safePkg(root) ?: window.pkg ?: continue
            if (packages != null && windowPkg !in packages) continue
            if (primaryPkg == null) primaryPkg = windowPkg
            val tree = UiTreeBuilder.build(root, windowPkg)
            truncated = truncated || tree.truncated
            nodes.addAll(tree.nodes)
            infos.addAll(tree.infos)
            windowIds.addAll(tree.windowIds)
            windowsRead++
        }
        val emptyReason = RootResolver.classifyAfterRead(windowsRead, nodes.size)
        return A11ySnapshot(
            tsMs = clock.nowMs(),
            pkg = primaryPkg,
            keyboardVisible = keyboardVisible || keyboardVisible(packages),
            nodes = nodes,
            infos = infos,
            windowIds = windowIds,
            windows = metas,
            emptyReason = emptyReason,
            truncated = truncated,
        )
    }

    override fun keyboardVisible(packages: Set<String>?): Boolean {
        val active = service() ?: return false
        return try {
            active.windows.orEmpty().filterNotNull().any {
                it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }
        } catch (e: RuntimeException) {
            false
        }
    }

    override fun windows(): List<RootResolver.WindowMeta> {
        val active = service() ?: return emptyList()
        return windowMetas(active)
    }

    private fun rootOf(service: AccessibilityService, windowId: Int): AccessibilityNodeInfo? {
        val window = try {
            service.windows.orEmpty().filterNotNull().firstOrNull { it.id == windowId }
        } catch (e: RuntimeException) {
            null
        } ?: return null
        return try {
            window.root
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun windowMetas(active: AccessibilityService): List<RootResolver.WindowMeta> {
        val windows: List<AccessibilityWindowInfo> = try {
            active.windows.orEmpty().filterNotNull()
        } catch (e: RuntimeException) {
            emptyList()
        }
        val metas = windows.map { w ->
            val root = try {
                w.root
            } catch (e: RuntimeException) {
                null
            }
            RootResolver.WindowMeta(
                id = try {
                    w.id
                } catch (e: RuntimeException) {
                    -1
                },
                layer = UiTreeBuilder.safeInt({ w.layer }, 0),
                type = windowTypeName(UiTreeBuilder.safeInt({ w.type }, -1)),
                pkg = root?.let { UiTreeBuilder.safePkg(it) },
                active = UiTreeBuilder.safeBool { w.isActive },
                focused = UiTreeBuilder.safeBool { w.isFocused },
                hasRoot = root != null,
            )
        }
        return metas.sortedByDescending { it.layer }
    }

    private fun windowTypeName(type: Int): String = when (type) {
        1 -> "application"
        2 -> "input_method"
        3 -> "system"
        4 -> "accessibility_overlay"
        5 -> "split_screen_divider"
        6 -> "magnification_overlay"
        else -> "unknown"
    }
}
