package app.autohost.capabilities.a11y

// Adapted from closepaw tool/action/UiChangeDetector.kt (Apache-2.0, Copyright 2026 ClosePaw Authors).
// Changes: FNV-1a over AutoHost UiNode structural fields; the 8×8 bitmap fallback is DROPPED —
// the action path has no bitmap, so an unreadable snapshot is `Unverifiable` (revisit with the
// screenshot provider); `focused` is EXCLUDED from the state fields per the ClosePaw lesson (a
// focus shift after a click is not a UI change — RM-A4); checked/selected/enabled are INCLUDED
// (the A2 lesson: a toggle that only flips `checked` must read as Changed).

import app.autohost.core.model.UiNode

/**
 * P12-03 (A2, RM-A4): the three-valued UI change detector. `Unverifiable` is a DISTINCT outcome —
 * never silently treated as Changed (the caller decides what to do with it).
 *
 * The fingerprint is FNV-1a over the nodes sorted by index, mixing the structural fields
 * (index, id, class, text, desc, bounds) AND the state fields (checked/selected/enabled).
 * `focused` is deliberately excluded: a focus shift after a click does not indicate navigation.
 */
object ChangeDetector {

    enum class Change { Changed, Unchanged, Unverifiable }

    private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L

    fun detect(before: List<UiNode>?, after: List<UiNode>?): Change {
        if (before == null || after == null) return Change.Unverifiable
        return if (fingerprint(before) == fingerprint(after)) Change.Unchanged else Change.Changed
    }

    /**
     * The state-aware fingerprint (replaces `SettlePolicy.fingerprint`'s
     * `index:id:text:desc:bounds` — A2: a toggle that only flips `checked` must read as changed).
     */
    fun fingerprint(nodes: List<UiNode>): Long {
        var hash = FNV_OFFSET_BASIS
        for (node in nodes.sortedBy { it.index }) {
            hash = mix(hash, node.index.toLong())
            hash = mix(hash, node.id.hashCode().toLong())
            hash = mix(hash, node.cls.hashCode().toLong())
            hash = mix(hash, node.text.hashCode().toLong())
            hash = mix(hash, node.desc.hashCode().toLong())
            hash = mix(hash, node.bounds.l.toLong())
            hash = mix(hash, node.bounds.t.toLong())
            hash = mix(hash, node.bounds.r.toLong())
            hash = mix(hash, node.bounds.b.toLong())
            // state-aware: these flip without touching text/bounds (the A2 toggle case)
            hash = mix(hash, node.has("checked").hashCode().toLong())
            hash = mix(hash, node.has("selected").hashCode().toLong())
            hash = mix(hash, node.has("enabled").hashCode().toLong())
            // focused is deliberately excluded (RM-A4)
        }
        return hash
    }

    private fun mix(hash: Long, value: Long): Long {
        var h = hash
        h = h xor (value and 0xFFL)
        h *= FNV_PRIME
        h = h xor ((value ushr 8) and 0xFFL)
        h *= FNV_PRIME
        h = h xor ((value ushr 16) and 0xFFL)
        h *= FNV_PRIME
        h = h xor ((value ushr 24) and 0xFFL)
        h *= FNV_PRIME
        h = h xor ((value ushr 32) and 0xFFL)
        h *= FNV_PRIME
        h = h xor ((value ushr 40) and 0xFFL)
        h *= FNV_PRIME
        h = h xor ((value ushr 48) and 0xFFL)
        h *= FNV_PRIME
        h = h xor ((value ushr 56) and 0xFFL)
        h *= FNV_PRIME
        return h
    }
}
