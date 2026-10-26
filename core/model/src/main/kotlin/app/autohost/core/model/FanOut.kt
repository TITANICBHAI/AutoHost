package app.autohost.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * P4-03: per-profile fan-out (plan §11.2). `sequential` and `parallel` share the due time (the
 * engine serialises UI-acting runs through the global UI lock); `staggered` offsets each profile
 * by i * stagger_seconds. Parallel is only legal for tasks that do not act on the UI — enforced
 * by the preflight, not here.
 */
@Serializable
data class FanOut(
    val mode: String = "sequential", // sequential | staggered | parallel
    @SerialName("stagger_seconds") val staggerSeconds: Int = 0,
) {
    fun validate(): List<String> = buildList {
        if (mode !in setOf("sequential", "staggered", "parallel")) add("fanout mode must be sequential|staggered|parallel")
        if (staggerSeconds < 0) add("fanout stagger_seconds must be >= 0")
    }
}
