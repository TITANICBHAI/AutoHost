package app.autohost.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** P4-02: schedule limits (plan §11.2). Any limit that is null is unlimited; `inf` on the Python side. */
@Serializable
data class ScheduleLimits(
    @SerialName("max_total_runs") val maxTotalRuns: Long? = null,
    @SerialName("max_runs_per_day") val maxRunsPerDay: Int? = null,
    @SerialName("end_at") val endAt: String? = null,
    @SerialName("stop_after_consecutive_failures") val stopAfterConsecutiveFailures: Int? = null,
) {
    fun validate(): List<String> = buildList {
        if (maxTotalRuns != null && maxTotalRuns < 1) add("max_total_runs must be >= 1")
        if (maxRunsPerDay != null && maxRunsPerDay < 1) add("max_runs_per_day must be >= 1")
        if (endAt != null && runCatching { parseIsoToMs(endAt) }.isFailure) add("end_at must be an ISO-8601 timestamp with offset")
        if (stopAfterConsecutiveFailures != null && stopAfterConsecutiveFailures < 1) {
            add("stop_after_consecutive_failures must be >= 1")
        }
    }
}
