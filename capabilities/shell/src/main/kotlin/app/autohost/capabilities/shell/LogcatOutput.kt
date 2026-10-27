package app.autohost.capabilities.shell

/**
 * P9-10: pure parser for `logcat -d -v epoch` output. The epoch format's stamp is
 * `<secs>.<msecs> <LEVEL>/<tag>( <pid>): <message>` (the pid column appears since L; on some
 * builds the pid is missing, which is handled honestly as null). Parsing stays dependency-free
 * so JVM tests drive it against recorded vectors.
 */
object LogcatOutput {

    data class Entry(
        val tsMs: Long?,
        val level: String,
        val tag: String,
        val pid: Int?,
        val message: String,
    )

    private val STAMP = Regex(
        "^(\\d{9,13})\\.(\\d{3})\\s+([VDIWEF])/([^()\\s]+)\\(\\s*(\\d+)?\\)?:\\s?(.*)$",
    )

    /**
     * Parses a snapshot; unparseable lines (header banners, blank lines, partial stamps from a
     * truncated buffer) are skipped — a snapshot is best effort and the line count is honest.
     */
    fun parse(stdout: String): List<Entry> {
        if (stdout.isBlank()) return emptyList()
        return stdout.lineSequence().mapNotNull { line ->
            val m = STAMP.find(line.trim()) ?: return@mapNotNull null
            val secs = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val ms = m.groupValues[2].toLongOrNull() ?: 0
            // 13 digits are already millis; 10 digits are seconds.
            val ts = if (secs > 9_999_999_999) secs else secs * 1000 + ms
            Entry(
                tsMs = ts,
                level = m.groupValues[3],
                tag = m.groupValues[4],
                pid = m.groupValues[5].toIntOrNull(),
                message = m.groupValues[6],
            )
        }.toList()
    }

    /** Post-filter applied by the provider (text/pid filters the device-side `-t` cannot express). */
    fun filter(
        entries: List<Entry>,
        levels: Set<String>? = null,
        minLevel: String? = null,
        pid: Int? = null,
        contains: String? = null,
    ): List<Entry> {
        val order = "VDIWEF".mapIndexed { i, c -> c.toString() to i }.toMap()
        val minIdx = minLevel?.uppercase()?.let { order[it] }
        return entries.filter { e ->
            if (minIdx != null && (order[e.level] ?: -1) < minIdx) return@filter false
            if (levels != null && e.level.uppercase() !in levels) return@filter false
            if (pid != null && e.pid != pid) return@filter false
            if (contains != null && !e.message.contains(contains, ignoreCase = true) &&
                !e.tag.contains(contains, ignoreCase = true)
            ) {
                return@filter false
            }
            true
        }
    }
}
