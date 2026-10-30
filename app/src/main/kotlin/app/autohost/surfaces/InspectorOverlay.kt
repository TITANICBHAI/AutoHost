package app.autohost.surfaces

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.autohost.capabilities.a11y.A11yBridge
import app.autohost.core.model.UiNode

/**
 * P7-11: the inspector overlay (plan §10.3/§5.6 — "draw node bounds/ids/indices over any app; tap
 * a node to copy a selector"). One full-screen `TYPE_ACCESSIBILITY_OVERLAY` view (attached through
 * the a11y service's window manager, no extra permission):
 *
 * - the canvas draws every node's bounds (labelled with the short resource id or a text snippet);
 * - a tap picks the topmost (smallest-area, deepest) node under the point, builds the selector via
 *   `SelectorBuilder` and copies it to the clipboard;
 * - the bottom strip shows the inspected package, the last copied selector and Exit.
 *
 * While inspecting, the overlay absorbs touches — that is the explicit inspector mode (the phone
 * is being examined, not used). Scope: only nodes whose package matches the currently inspected
 * package are drawn/picked; AutoHost itself is never drawn on.
 */
class InspectorOverlay(
    private val nodesProvider: () -> List<UiNode>,
    private val onExit: () -> Unit,
) {

    private var attached: ViewGroup? = null
    private var canvasView: NodeCanvasView? = null
    private var infoRef: TextView? = null

    /** The node currently under inspection highlight (last tapped); null initially. */
    @Volatile
    var lastPicked: UiNode? = null
        private set

    fun show() {
        if (attached != null) return
        val service = A11yBridge.service.value ?: return
        val wm = try {
            service.getSystemService(WindowManager::class.java) ?: return
        } catch (e: RuntimeException) {
            return
        }
        val root = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        val canvas = NodeCanvasView(service).apply {
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) pick(event.x, event.y)
                true
            }
        }
        val strip = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xE6101010.toInt())
            setPadding(16, 8, 16, 8)
            gravity = Gravity.CENTER_VERTICAL
        }
        val info = TextView(service).apply {
            text = initialInfo()
            setTextColor(Color.WHITE)
            textSize = 11f
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val copy = Button(service).apply {
            text = "Copy"
            textSize = 11f
            setOnClickListener { lastPicked?.let { copySelector(it) } }
        }
        val exit = Button(service).apply {
            text = "Exit"
            textSize = 11f
            setOnClickListener { onExit() }
        }
        strip.addView(info)
        strip.addView(copy)
        strip.addView(exit)
        root.addView(
            canvas,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        attached = root
        canvasView = canvas
        infoRef = info
        try {
            wm.addView(root, params)
            canvas.invalidate()
        } catch (e: RuntimeException) {
            attached = null
            canvasView = null
            infoRef = null
        }
    }

    /** Re-reads the snapshot and redraws; returns true when the overlay is live. */
    fun refresh(): Boolean {
        val canvas = canvasView ?: return false
        canvas.setNodes(nodesProvider())
        // Crash-fix 3.4: invalidate() must run on the main thread; the refresh loop runs on a background dispatcher.
        MainThread.post { canvas.invalidate() }
        return true
    }

    fun updateInfo(text: String) {
        val info = infoRef ?: return
        // Crash-fix 3.4: setting text on an attached TextView must run on the main thread.
        MainThread.post { info.text = text }
    }

    fun hide() {
        val root = attached ?: return
        attached = null
        canvasView = null
        infoRef = null
        val service = A11yBridge.service.value ?: return
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        try {
            wm.removeView(root)
        } catch (e: RuntimeException) {
            // already gone
        }
    }

    val showing: Boolean get() = attached != null

    private fun initialInfo(): String = "inspecting — tap a node"

    private fun pick(x: Float, y: Float) {
        val candidates = nodesProvider().filter {
            x >= it.bounds.l && x <= it.bounds.r && y >= it.bounds.t && y <= it.bounds.b
        }
        // The tap belongs to the deepest (then smallest) node — the leaf the user actually means.
        val picked = candidates.minWithOrNull(
            compareBy({ it.depth }, { it.bounds.width * it.bounds.height }),
        ) ?: return
        lastPicked = picked
        canvasView?.let { canvas ->
            canvas.highlight(picked)
            runCatching { canvas.invalidate() }
        }
        copySelector(picked)
    }

    private fun copySelector(node: UiNode) {
        val built = app.autohost.core.model.SelectorBuilder.forNode(node, nodesProvider())
        val context = A11yBridge.service.value ?: return
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("autohost-selector", built.json))
        }
        updateInfo("[${built.basis}] ${built.json.take(SEL_PREVIEW)}")
    }

    /**
     * The bounds painter: one view, one canvas pass — a View per node would not survive real
     * trees. Nodes are drawn shallow-to-deep so the deepest (most specific) bounds sit on top.
     */
    class NodeCanvasView(context: Context) : android.view.View(context) {

        private var nodes: List<UiNode> = emptyList()
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0xFF22D3EE.toInt()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x1422D3EE
        }
        private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xCC101010.toInt()
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
        }

        fun setNodes(value: List<UiNode>) {
            nodes = value.sortedBy { it.depth }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (node in nodes) {
                val w = node.bounds.width
                val h = node.bounds.height
                if (w <= 0 || h <= 0) continue
                canvas.drawRect(
                    node.bounds.l.toFloat(),
                    node.bounds.t.toFloat(),
                    node.bounds.r.toFloat(),
                    node.bounds.b.toFloat(),
                    if (node.bounds == lastPickedBounds) fill else stroke,
                )
                val text = node.label()
                if (text.isNotEmpty()) {
                    val top = node.bounds.t.toFloat().coerceAtLeast(0f)
                    val textWidth = label.measureText(text)
                    canvas.drawRect(
                        node.bounds.l.toFloat(),
                        top,
                        node.bounds.l + textWidth + 12f,
                        top + 30f,
                        labelBg,
                    )
                    canvas.drawText(text, node.bounds.l + 6f, top + 22f, label)
                }
            }
        }

        private var lastPickedBounds: app.autohost.core.model.Bounds? = null

        fun highlight(node: UiNode?) {
            lastPickedBounds = node?.bounds
        }

        private fun UiNode.label(): String = when {
            id.isNotBlank() -> id.substringAfterLast('/')
            text.isNotBlank() -> text.take(16)
            desc.isNotBlank() -> desc.take(16)
            else -> ""
        }
    }

    private companion object {
        const val SEL_PREVIEW = 120
    }
}
