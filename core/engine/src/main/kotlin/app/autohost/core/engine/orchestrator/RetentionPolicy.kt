package app.autohost.core.engine.orchestrator

import java.io.File

/**
 * P5-08 (plan §13.1): evidence + run-directory retention. The selection is pure — callers hand in
 * per-directory stats, the policy answers which directories to delete. Defaults follow plan §21:
 * 14 days, 200 runs, 512 MB of evidence.
 *
 * Rules applied in order (a directory dies when any rule says so):
 *  1. older than [maxAgeDays];
 *  2. beyond the [maxRuns] newest;
 *  3. the oldest directories first until the total size fits [maxBytes].
 */
data class RunDirStat(val path: String, val lastModifiedMs: Long, val sizeBytes: Long)

data class RetentionRules(
    val maxAgeDays: Int = 14,
    val maxRuns: Int = 200,
    val maxBytes: Long = 512L * 1024 * 1024,
)

object RetentionPolicy {

    fun selectForDeletion(entries: List<RunDirStat>, rules: RetentionRules, nowMs: Long): Set<String> {
        if (entries.isEmpty()) return emptySet()
        val doomed = HashSet<String>()
        val ageCutoff = nowMs - rules.maxAgeDays * 24L * 60 * 60 * 1000

        val byAge = entries.filter { it.lastModifiedMs < ageCutoff }
        doomed += byAge.map { it.path }

        val survivors = entries.filter { it.path !in doomed }.sortedByDescending { it.lastModifiedMs }
        if (rules.maxRuns > 0) {
            doomed += survivors.drop(rules.maxRuns).map { it.path }
        }

        var kept = survivors.filter { it.path !in doomed }
        var total = kept.sumOf { it.sizeBytes }
        if (rules.maxBytes > 0) {
            for (candidate in kept.sortedBy { it.lastModifiedMs }) { // oldest first
                if (total <= rules.maxBytes) break
                doomed += candidate.path
                total -= candidate.sizeBytes
            }
        }
        return doomed
    }

    /** Stats for the immediate children of [root] (each child = one run directory). */
    fun stats(root: File): List<RunDirStat> =
        root.listFiles()?.filter { it.isDirectory }?.map { dir ->
            RunDirStat(dir.absolutePath, dir.lastModified(), dir.walkBytes())
        } ?: emptyList()

    private fun File.walkBytes(): Long = walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Applies the verdict; returns the deleted count. Missing paths are ignored. */
    fun apply(entries: List<RunDirStat>, doomed: Set<String>): Int {
        var deleted = 0
        for (entry in entries) {
            if (entry.path in doomed) {
                if (File(entry.path).deleteRecursively()) deleted++
            }
        }
        return deleted
    }
}
