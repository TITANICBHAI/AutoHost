package app.autohost.core.model

/** P4-04: busy policy (plan §11.9) — what happens when the target task/run cannot start now. */
enum class BusyPolicy {
    QUEUE,
    DROP,
    REPLACE;

    companion object {
        fun fromJson(value: String?): BusyPolicy = when (value) {
            null -> DROP
            "queue" -> QUEUE
            "drop" -> DROP
            "replace" -> REPLACE
            else -> throw IllegalArgumentException("busy_policy must be queue|drop|replace")
        }
    }
}
