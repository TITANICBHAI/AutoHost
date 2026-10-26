package app.autohost.capabilities.a11y

/**
 * Chooses which window roots a snapshot reads, and how to talk about an empty result (plan §5.7):
 *
 * - roots come from the **active window plus every higher-layer application window** (dialogs, popups),
 *   de-duplicated by window id;
 * - the active root alone is used only when the window list cannot be read;
 * - `NO_ACTIVE_WINDOW` (typed error) means the service sees no windows at all — a real freeze or the
 *   service being mid-teardown;
 * - `EMPTY_TREE` means a root exists but exposes no semantic nodes (Flutter, games, WebView canvases) —
 *   *not an error*; snapshots carry it as `empty_reason` so scripts can branch without exceptions.
 *
 * Pure decision logic: [Input]/[Decision] are plain data, so the JVM tests need no Android classes.
 */
object RootResolver {

    /** Everything the resolver needs to know about one window, highest layer first. */
    data class WindowMeta(
        val id: Int,
        val layer: Int,
        val type: String, // "application", "input_method", "system", "accessibility_overlay", …
        val pkg: String?,
        val active: Boolean,
        val focused: Boolean,
        val hasRoot: Boolean,
    )

    data class Input(
        val windows: List<WindowMeta>,
        /** Package of the root read directly via `rootInActiveWindow` (the fallback path), if any. */
        val activeRootPkg: String?,
    )

    sealed class Decision {
        /** Read these windows (already de-duplicated, highest layer first, keyboard excluded). */
        data class Read(val windows: List<WindowMeta>, val primaryPackage: String?) : Decision()

        /** No windows at all: fail closed with NO_ACTIVE_WINDOW. */
        object NoActiveWindow : Decision() {
            override fun toString(): String = "NoActiveWindow"
        }

        /** Windows exist but none exposes a readable root: EMPTY_TREE, not an error. */
        data class EmptyTree(val reason: String) : Decision()
    }

    fun resolve(input: Input): Decision {
        val candidates = input.windows
            .filter { it.hasRoot }
            .filter { it.type != "input_method" } // the keyboard is state (KeyboardState), not snapshot content
            .distinctBy { it.id }
            .sortedByDescending { it.layer }
        if (candidates.isEmpty()) {
            // Window list unreadable or empty: fall back to the active root only (plan §5.7).
            return input.activeRootPkg?.let { pkg ->
                val synthetic = WindowMeta(
                    id = -1, layer = 0, type = "application", pkg = pkg,
                    active = true, focused = false, hasRoot = true,
                )
                Decision.Read(listOf(synthetic), pkg)
            } ?: Decision.NoActiveWindow
        }
        val primary = candidates.firstOrNull { it.active } ?: candidates.first()
        return Decision.Read(candidates, primary.pkg)
    }

    /** Classifies the outcome *after* reading: a window that yields zero nodes is EMPTY_TREE, not an error. */
    fun classifyAfterRead(readWindows: Int, nodeCount: Int): String? = when {
        readWindows == 0 -> "NO_READABLE_ROOT"
        nodeCount == 0 -> "EMPTY_TREE"
        else -> null
    }
}
