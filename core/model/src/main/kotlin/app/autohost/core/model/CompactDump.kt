package app.autohost.core.model

data class DumpFilter(val onScreen: Boolean = true, val minSize: Int = 5, val minVisibleRatio: Double = 0.01)

/**
 * Canonical text form of a snapshot (plan section 7.6). Shared golden vectors: testing/golden/compact_dump.json.
 *
 *   App: <label> (<package>)  Activity: <name>  Keyboard: visible|hidden  Focused: '<text>'
 *   1. ShortClass: "resource-id" "text or desc" [flags] (l,t,r,b)        (two spaces of indent per depth)
 */
object CompactDump {
    private const val MAX_VALUE = 80
    private val FLAG_ORDER = listOf("clickable", "checked", "editable", "scrollable", "focused", "selected")

    /** `absolute` prints raw pixels; `normalized` rescales to 0..1000 over the screen (plan §7.6). */
    enum class Coordinates { ABSOLUTE, NORMALIZED }

    fun render(
        nodes: List<UiNode>,
        label: String = "",
        pkg: String = "",
        activity: String = "",
        keyboardVisible: Boolean = false,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400,
        filter: DumpFilter? = DumpFilter(),
        coordinates: Coordinates = Coordinates.ABSOLUTE,
    ): String {
        val focused = nodes.firstOrNull { it.has("focused") }
        val focusText = focused?.let { clip(shown(it).ifEmpty { it.id }) } ?: ""
        val sb = StringBuilder()
        sb.append("App: ").append(label.ifEmpty { pkg.ifEmpty { "none" } })
            .append(" (").append(pkg.ifEmpty { "none" }).append(")  Activity: ").append(activity.ifEmpty { "none" })
            .append("  Keyboard: ").append(if (keyboardVisible) "visible" else "hidden")
            .append("  Focused: '").append(focusText).append("'")
        var index = 0
        for (n in nodes) {
            if (!keep(n, filter, screenWidth, screenHeight)) continue
            index++
            val flags = FLAG_ORDER.filter { n.has(it) }.joinToString(",")
            val (l, t, r, b) = scaled(n.bounds, screenWidth, screenHeight, coordinates)
            sb.append('\n').append("  ".repeat(n.depth))
                .append(index).append(". ").append(n.cls.substringAfterLast('.')).append(": \"")
                .append(clip(n.id)).append("\" \"").append(clip(shown(n))).append("\" [").append(flags).append("] (")
                .append(l).append(',').append(t).append(',').append(r).append(',').append(b).append(')')
        }
        return sb.toString()
    }

    private fun scaled(b: Bounds, sw: Int, sh: Int, mode: Coordinates): List<Int> =
        if (mode == Coordinates.NORMALIZED && sw > 0 && sh > 0) {
            listOf(
                (b.l * 1_000 / sw).coerceIn(0, 1000),
                (b.t * 1_000 / sh).coerceIn(0, 1000),
                (b.r * 1_000 / sw).coerceIn(0, 1000),
                (b.b * 1_000 / sh).coerceIn(0, 1000),
            )
        } else {
            listOf(b.l, b.t, b.r, b.b)
        }

    private fun shown(n: UiNode): String = if (n.has("password")) "" else n.text.ifEmpty { n.desc }

    private fun clip(value: String): String {
        val v = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return if (v.length <= MAX_VALUE) v else v.substring(0, MAX_VALUE - 1) + "\u2026"
    }

    private fun keep(n: UiNode, f: DumpFilter?, sw: Int, sh: Int): Boolean {
        if (f == null) return true
        val b = n.bounds
        if (minOf(b.width, b.height) < f.minSize) return false
        val area = maxOf(0, b.width).toLong() * maxOf(0, b.height)
        if (area == 0L) return false
        val iw = maxOf(0, minOf(b.r, sw) - maxOf(b.l, 0))
        val ih = maxOf(0, minOf(b.b, sh) - maxOf(b.t, 0))
        val ratio = (iw.toLong() * ih).toDouble() / area
        if (f.onScreen && ratio <= 0.0) return false
        return ratio >= f.minVisibleRatio
    }
}
