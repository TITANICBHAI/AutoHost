package app.autohost.capabilities.shell

import app.autohost.core.model.Bounds
import app.autohost.core.model.UiNode

/**
 * P9-05: dependency-free recursive parser for `uiautomator dump` XML. `android.util.Xml` is
 * avoided on purpose — the JVM test suite must exercise the parser without Robolectric, and the
 * dump format is machine-generated, well-formed, attribute-only XML, so a small pull parser is
 * honest and bounded. Unknown attributes are ignored; missing ones default empty.
 */
object UiautomatorDump {

    /** Max document size the parser accepts (a dump beyond this is treated as corrupt). */
    const val MAX_BYTES = 4 * 1024 * 1024

    class DumpParseException(detail: String) : Exception(detail)

    /** Parses a uiautomator dump into the flat depth-first UiNode list the model expects. */
    fun parse(xml: String): List<UiNode> {
        if (xml.length > MAX_BYTES) throw DumpParseException("dump exceeds $MAX_BYTES bytes")
        val parser = Parser(xml)
        parser.run()
        return parser.nodes
    }

    private class Parser(private val xml: String) {
        val nodes = ArrayList<UiNode>(256)

        private var pos = 0
        private var index = 0
        private var depth = 0
        private var parent = -1

        fun run() {
            skipProlog()
            parseLoop(topLevel = true)
            if (nodes.isEmpty()) throw DumpParseException("no nodes parsed from the dump")
        }

        /** Parses sibling elements until the matching close tag (or end of input at top level). */
        private fun parseLoop(topLevel: Boolean) {
            while (true) {
                skipWhitespace()
                if (pos >= xml.length) {
                    if (topLevel) return
                    throw DumpParseException("unbalanced tags: input ended inside a node")
                }
                if (xml.startsWith("</", pos)) {
                    skipCloseTag()
                    return
                }
                if (xml.startsWith("<!--", pos) || xml.startsWith("<?", pos) || xml.startsWith("<!", pos)) {
                    skipUntil('>')
                    continue
                }
                if (xml[pos] != '<') throw DumpParseException("expected '<' at offset $pos")
                readElement()
            }
        }

        private fun readElement() {
            val end = xml.indexOf('>', pos)
            if (end < 0) throw DumpParseException("unterminated tag at offset $pos")
            val raw = xml.substring(pos + 1, end)
            pos = end + 1
            val selfClosing = raw.endsWith("/")
            val tagBody = if (selfClosing) raw.removeSuffix("/") else raw
            val name = tagName(tagBody)

            if (name != "node") {
                // <hierarchy>/<root> wrappers carry no attributes we need; recurse into them.
                if (!selfClosing) parseLoop(topLevel = false)
                return
            }

            val attrs = attributes(tagBody)
            nodes.add(
                UiNode(
                    index = index++,
                    parent = parent,
                    depth = depth,
                    pkg = attrs["package"] ?: "",
                    cls = attrs["class"] ?: "",
                    id = attrs["resource-id"] ?: "",
                    text = attrs["text"] ?: "",
                    desc = attrs["content-desc"] ?: "",
                    bounds = parseBounds(attrs["bounds"]) ?: Bounds(0, 0, 0, 0),
                    flags = flagsOf(attrs),
                ),
            )
            if (!selfClosing) {
                val savedParent = parent
                val savedDepth = depth
                depth++
                parent = nodes.size - 1
                parseLoop(topLevel = false)
                depth = savedDepth
                parent = savedParent
            }
        }

        private fun skipCloseTag() {
            val end = xml.indexOf('>', pos)
            if (end < 0) throw DumpParseException("unterminated close tag at offset $pos")
            pos = end + 1
        }

        private fun skipProlog() {
            while (pos < xml.length && (xml.startsWith("<?", pos) || xml.startsWith("<!", pos))) {
                val end = xml.indexOf('>', pos)
                if (end < 0) break
                pos = end + 1
            }
        }

        private fun skipUntil(c: Char) {
            val end = xml.indexOf(c, pos)
            pos = if (end < 0) xml.length else end + 1
        }

        private fun skipWhitespace() {
            while (pos < xml.length && xml[pos].isWhitespace()) pos++
        }

        private fun tagName(body: String): String = body.substringBefore(' ').substringBefore('\t').trim()

        private fun attributes(body: String): Map<String, String> {
            val map = HashMap<String, String>(16)
            var i = body.indexOf(' ') + 1
            while (i in 1 until body.length) {
                while (i < body.length && (body[i].isWhitespace() || body[i] == '/')) i++
                if (i >= body.length) break
                val eq = body.indexOf('=', i)
                if (eq < 0) break
                val key = body.substring(i, eq).trim()
                var j = eq + 1
                if (j >= body.length) break
                val quote = body[j]
                if (quote != '"' && quote != '\'') break
                val close = body.indexOf(quote, j + 1)
                if (close < 0) break
                map[key] = unescape(body.substring(j + 1, close))
                i = close + 1
            }
            return map
        }

        private fun unescape(value: String): String = value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

        private fun parseBounds(raw: String?): Bounds? {
            if (raw == null) return null
            val match = Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").find(raw) ?: return null
            val (l, t, r, b) = match.destructured
            return Bounds(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        }

        private fun flagsOf(attrs: Map<String, String>): Set<String> {
            val flags = LinkedHashSet<String>()
            fun flag(name: String, key: String = name) {
                if (attrs[key] == "true") flags.add(name)
            }
            flag("clickable")
            flag("long_clickable", "long-clickable")
            flag("checked")
            flag("checkable")
            flag("editable")
            flag("scrollable")
            flag("focused")
            flag("selected")
            flag("enabled")
            flag("password")
            flag("visible")
            return flags
        }
    }
}
