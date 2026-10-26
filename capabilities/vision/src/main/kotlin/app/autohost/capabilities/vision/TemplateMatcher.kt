package app.autohost.capabilities.vision

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * P10-05 (ADR-072): a dependency-free template matcher — zero-mean normalized cross-correlation
 * (NCC) over grayscale, coarse-stride scan then a refine pass around the best coarse hit.
 *
 * Pure JVM (no android.*): golden vectors for the JVM tests are generated from deterministic
 * gradient images, and the same class runs on device over an ARGB int[] down-converted by the
 * host. NCC is invariant to affine brightness shifts, which is exactly the robustness a
 * screenshot needle needs; it is NOT rotation/scale invariant — that limitation is documented
 * in the SDK docs ("vision results are hints").
 *
 * Inputs are grayscale 0..255 arrays, row-major, `width` wide.
 */
object TemplateMatcher {

    data class Match(
        val found: Boolean,
        val score: Double,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )

    const val NO_MATCH_SCORE = 0.0

    fun match(
        haystack: IntArray,
        haystackWidth: Int,
        haystackHeight: Int,
        needle: IntArray,
        needleWidth: Int,
        needleHeight: Int,
        stride: Int,
        threshold: Double,
    ): Match {
        require(haystackWidth > 0 && haystackHeight > 0) { "haystack is empty" }
        require(needleWidth > 0 && needleHeight > 0) { "needle is empty" }
        require(needleWidth <= haystackWidth && needleHeight <= haystackHeight) { "needle larger than haystack" }
        require(stride >= 1) { "stride must be >= 1" }
        if (needleWidth * needleHeight < 4) {
            // Too few pixels for a meaningful NCC — refuse rather than guess.
            return Match(false, NO_MATCH_SCORE, 0, 0, needleWidth, needleHeight)
        }

        val needlePrep = normalized(needle, needleWidth * needleHeight)
        val maxScore = doubleArrayOf(NO_MATCH_SCORE)
        val best = intArrayOf(0, 0)

        // Coarse scan.
        var y = 0
        while (y + needleHeight <= haystackHeight) {
            var x = 0
            while (x + needleWidth <= haystackWidth) {
                val score = nccAt(haystack, haystackWidth, x, y, needle, needleWidth, needleHeight, needlePrep)
                if (score > maxScore[0]) {
                    maxScore[0] = score
                    best[0] = x
                    best[1] = y
                }
                x += stride
            }
            y += stride
        }

        // Refine pass: +/- stride window around the coarse best, step 1.
        val refineFromX = max(0, best[0] - stride + 1)
        val refineToX = min(haystackWidth - needleWidth, best[0] + stride - 1)
        val refineFromY = max(0, best[1] - stride + 1)
        val refineToY = min(haystackHeight - needleHeight, best[1] + stride - 1)
        var ry = refineFromY
        while (ry <= refineToY) {
            var rx = refineFromX
            while (rx <= refineToX) {
                val score = nccAt(haystack, haystackWidth, rx, ry, needle, needleWidth, needleHeight, needlePrep)
                if (score > maxScore[0]) {
                    maxScore[0] = score
                    best[0] = rx
                    best[1] = ry
                }
                rx += 1
            }
            ry += 1
        }

        val found = maxScore[0] >= threshold
        return Match(found, maxScore[0], best[0], best[1], needleWidth, needleHeight)
    }

    /** Zero-mean NCC of the haystack window at (x, y) against the pre-normalized needle. */
    private fun nccAt(
        haystack: IntArray,
        haystackWidth: Int,
        x0: Int,
        y0: Int,
        needle: IntArray,
        needleWidth: Int,
        needleHeight: Int,
        needlePrep: NeedlePrep,
    ): Double {
        var mean = 0.0
        var n = 0
        for (dy in 0 until needleHeight) {
            val row = (y0 + dy) * haystackWidth + x0
            for (dx in 0 until needleWidth) {
                mean += grayscale(haystack[row + dx])
                n++
            }
        }
        mean /= n
        var dot = 0.0
        var norm = 0.0
        var i = 0
        for (dy in 0 until needleHeight) {
            val row = (y0 + dy) * haystackWidth + x0
            for (dx in 0 until needleWidth) {
                val v = grayscale(haystack[row + dx]) - mean
                dot += v * needlePrep.norm[i]
                norm += v * v
                i++
            }
        }
        val denom = sqrt(norm) * needlePrep.magnitude
        if (denom <= 0.0) return NO_MATCH_SCORE
        return (dot / denom).coerceIn(-1.0, 1.0)
    }

    class NeedlePrep internal constructor(val norm: DoubleArray, val magnitude: Double)

    /** Zero-means and L2-normalizes the needle once per match call (thread-safe: per-call value). */
    private fun normalized(needle: IntArray, count: Int): NeedlePrep {
        var mean = 0.0
        for (i in 0 until count) mean += grayscale(needle[i])
        mean /= count
        val out = DoubleArray(count)
        var mag = 0.0
        for (i in 0 until count) {
            val v = grayscale(needle[i]) - mean
            out[i] = v
            mag += v * v
        }
        return NeedlePrep(out, sqrt(mag))
    }

    /** ITU-R BT.601 luma from an ARGB int. */
    fun grayscale(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }
}
