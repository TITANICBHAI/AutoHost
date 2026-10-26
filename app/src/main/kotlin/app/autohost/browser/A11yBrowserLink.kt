package app.autohost.browser

import android.accessibilityservice.AccessibilityService
import app.autohost.capabilities.a11y.A11yBridge
import app.autohost.capabilities.a11y.NodeActions
import app.autohost.capabilities.browser.HostAct

/**
 * P10-03 (ADR-070): the a11y-backed [app.autohost.capabilities.browser.A11yBrowserProvider.Link].
 * Binds the browser module's seam to the a11y reader/actions that already exist — no new a11y
 * surface, no duplicate selectors engine:
 *
 *  - text search walks the latest reader snapshot's flat node list (case-insensitive `contains`);
 *  - clicks go through [NodeActions.click] (the same click chain `device.ui.click` uses);
 *  - typing routes through ACTION_SET_TEXT on the focused/first-editable node — honest that it
 *    is a node action, not keystrokes (`input_mode:"a11y"`);
 *  - visible text is the page/text lines of the snapshot, capped.
 */
class A11yBrowserLink(private val clock: app.autohost.core.common.Clock) :
    app.autohost.capabilities.browser.A11yBrowserProvider.Link {

    private fun service(): AccessibilityService? = A11yBridge.service.value

    override fun waitForeground(browserPackage: String?, timeoutMs: Long): Boolean {
        val deadline = clock.nowMs() + timeoutMs
        while (clock.nowMs() < deadline) {
            val service = service() ?: return false
            val pkg = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
            if (pkg != null && (browserPackage == null || pkg == browserPackage)) return true
            runCatching { Thread.sleep(150) }
        }
        return false
    }

    override fun clickText(text: String, timeoutMs: Long): HostAct {
        val service = service() ?: return HostAct(false, false, "a11y")
        val deadline = clock.nowMs() + timeoutMs
        while (clock.nowMs() < deadline) {
            val snapshot = app.autohost.capabilities.a11y.SimpleA11yReader({ service }, clock).read(null)
            val index = snapshot.nodes.indexOfFirst { node ->
                node.text?.contains(text, ignoreCase = true) == true ||
                    node.contentDesc?.contains(text, ignoreCase = true) == true
            }
            if (index >= 0) {
                val info = snapshot.infoAt(index) ?: return HostAct(false, true, "a11y")
                val outcome = NodeActions.click(service, info)
                return HostAct(outcome == NodeActions.ClickOutcome.CLICKED, true, "a11y")
            }
            runCatching { Thread.sleep(150) }
        }
        return HostAct(false, false, "a11y")
    }

    override fun typeIntoFocused(text: String, timeoutMs: Long): HostAct {
        val service = service() ?: return HostAct(false, false, "a11y")
        val snapshot = app.autohost.capabilities.a11y.SimpleA11yReader({ service }, clock).read(null)
        val index = snapshot.nodes.indexOfFirst { it.editable == true }
        if (index < 0) return HostAct(false, false, "a11y")
        val info = snapshot.infoAt(index) ?: return HostAct(false, true, "a11y")
        val applied = NodeActions.setText(info, text)
        return HostAct(applied, true, "a11y")
    }

    override fun visibleText(maxChars: Int): String? {
        val service = service() ?: return null
        val snapshot = app.autohost.capabilities.a11y.SimpleA11yReader({ service }, clock).read(null)
        val lines = snapshot.nodes.mapNotNull { it.text?.trim() }.filter { it.isNotEmpty() }
        val joined = StringBuilder()
        for (line in lines) {
            if (joined.length + line.length + 1 > maxChars) break
            if (joined.isNotEmpty()) joined.append('\n')
            joined.append(line)
        }
        return joined.toString().ifEmpty { null }
    }

    override fun currentPackage(): String? = runCatching { service()?.rootInActiveWindow?.packageName?.toString() }.getOrNull()

    override fun pressBack(): Boolean = service()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true
}
