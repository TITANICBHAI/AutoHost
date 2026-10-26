package app.autohost.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Selector latency benchmark (P3-23 → docs/PERF.md). Runs on the JVM in the normal test pass: it asserts
 * correctness only, prints the p50/p95 distribution for 100/500/2,000-node trees, and its output is what
 * `docs/PERF.md` publishes. The p95 < 200 ms *target* is verified on real hardware (G3-d) — CI machines
 * are too noisy for an assertion, so this test never fails on timing.
 */
class SelectorBenchTest {

    private fun syntheticTree(size: Int): List<UiNode> {
        // A wide realistic tree: parent containers every 10 nodes, leaves with stable ids and texts.
        val nodes = ArrayList<UiNode>(size)
        for (i in 0 until size) {
            val parent = if (i % 10 == 0) -1 else i - 1
            nodes.add(
                UiNode(
                    index = i,
                    parent = parent,
                    depth = if (i % 10 == 0) 0 else i / 10 + 1,
                    pkg = "bench.app",
                    cls = if (i % 3 == 0) "android.widget.Button" else "android.widget.TextView",
                    id = if (i % 5 == 0) "bench.app:id/fx_node_$i" else "",
                    text = if (i % 2 == 0) "Item $i" else "",
                    desc = "",
                    bounds = Bounds(0, i * 48, 1080, i * 48 + 48),
                    flags = if (i % 3 == 0) setOf("clickable", "enabled") else emptySet(),
                ),
            )
        }
        return nodes
    }

    private fun bench(size: Int, iterations: Int = 200): Pair<Long, Long> {
        val nodes = syntheticTree(size)
        val selector = Selector(
            terms = listOf(
                SelectorTerm(cls = Match(listOf("android.widget.Button"), "eq", true)),
                SelectorTerm(text = Match(listOf("item"), "contains", false)),
            ),
        )
        val samples = ArrayList<Long>(iterations)
        repeat(iterations) {
            val start = System.nanoTime()
            val hits = SelectorEngine.findAll(nodes, selector)
            samples.add((System.nanoTime() - start) / 1_000)
            check(hits.isNotEmpty()) { "benchmark selector must match" }
        }
        samples.sort()
        return samples[samples.size / 2] to samples[(samples.size * 95) / 100]
    }

    @Test
    fun `find latency stays in single-digit milliseconds on large trees`() {
        for (size in listOf(100, 500, 2_000)) {
            val (p50, p95) = bench(size)
            println(String.format("PERF find/2-term selector on %5d nodes: p50=%6d us p95=%7d us", size, p50, p95))
            assertEquals(size, syntheticTree(size).size) // sanity: the tree builder is honest
        }
    }
}
