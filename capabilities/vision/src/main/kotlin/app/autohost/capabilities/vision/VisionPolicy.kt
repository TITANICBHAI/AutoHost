package app.autohost.capabilities.vision

/**
 * P10-05: pure vision policy (ADR-072) — argument validation shared by all `vision.*` methods.
 *
 * Tier T4 rule (plan §6.1: "Hints, not proof"): every provider result carries `hint: true` and
 * callers must treat vision output as an advisory signal, never as verified UI state. Redaction
 * note: OCR text may contain secrets that happen to be on screen — results are run-scoped and
 * are NEVER auto-attached to evidence by the provider (the script decides, as with logcat).
 */
object VisionPolicy {

    const val MAX_REGION_DIM = 8_000          // per axis; full screenshots stay far below this
    const val MAX_PIXELS = 8_000 * 8_000 / 2  // 32 Mpx total guard
    const val MAX_NEEDLE_BYTES = 512 * 1024   // 512 KB needle PNG cap
    const val DEFAULT_STRIDE = 4
    const val MIN_THRESHOLD = 0.5             // below this NCC is noise; refuse nonsense configs
    const val DEFAULT_THRESHOLD = 0.92

    data class Region(val left: Int, val top: Int, val width: Int, val height: Int)

    sealed class RegionVerdict {
        data class Ok(val region: Region) : RegionVerdict()
        data class Bad(val detail: String) : RegionVerdict()
    }

    /** Null region = full source image; otherwise clip-validated against the source bounds. */
    fun regionVerdict(region: Region?, sourceWidth: Int, sourceHeight: Int): RegionVerdict {
        if (region == null) return RegionVerdict.Ok(Region(0, 0, sourceWidth, sourceHeight))
        if (region.width <= 0 || region.height <= 0) return RegionVerdict.Bad("region width/height must be positive")
        if (region.left < 0 || region.top < 0) return RegionVerdict.Bad("region left/top must be non-negative")
        if (region.left + region.width > sourceWidth || region.top + region.height > sourceHeight) {
            return RegionVerdict.Bad("region exceeds the source image bounds (${sourceWidth}x$sourceHeight)")
        }
        if (region.width > MAX_REGION_DIM || region.height > MAX_REGION_DIM) {
            return RegionVerdict.Bad("region exceeds ${MAX_REGION_DIM}px per axis")
        }
        return RegionVerdict.Ok(region)
    }

    fun thresholdVerdict(threshold: Double?): Double {
        val t = threshold ?: DEFAULT_THRESHOLD
        if (t !in MIN_THRESHOLD..1.0) return -1.0
        return t
    }

    fun strideVerdict(stride: Int?): Int {
        val s = stride ?: DEFAULT_STRIDE
        if (s < 1 || s > 32) return -1
        return s
    }
}
