package app.autohost.core.model

import java.io.File

/** Finds a file in the repo from whatever directory Gradle runs the test in (module dir or repo root). */
fun repoFile(relative: String): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        val candidate = File(dir, relative)
        if (candidate.exists()) return candidate
        dir = dir.parentFile
    }
    error("repo file not found from ${System.getProperty("user.dir")}: $relative")
}

fun node(
    index: Int,
    parent: Int = -1,
    depth: Int = 0,
    text: String = "",
    id: String = "",
    desc: String = "",
    cls: String = "android.widget.TextView",
    bounds: Bounds = Bounds(0, 0, 100, 100),
    flags: Set<String> = emptySet(),
    pkg: String = "app.example",
    drawingOrder: Int = -1,
): UiNode = UiNode(index, parent, depth, pkg, cls, id, text, desc, bounds, flags, drawingOrder)

fun textSel(vararg labels: String, mode: String = "contains", caseSensitive: Boolean = false): Selector =
    Selector(terms = listOf(SelectorTerm(text = Match(labels.toList(), mode, caseSensitive))))

fun idSel(vararg labels: String, mode: String = "eq"): Selector =
    Selector(terms = listOf(SelectorTerm(id = Match(labels.toList(), mode, true))))

fun stateSel(flag: String, value: Boolean = true): Selector =
    Selector(terms = listOf(SelectorTerm(state = mapOf(flag to value))))
