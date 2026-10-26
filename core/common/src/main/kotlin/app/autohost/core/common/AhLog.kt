package app.autohost.core.common

import kotlinx.serialization.json.JsonObject

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** One structured log line. Never carries secrets — redaction is applied centrally before this point. */
data class LogEntry(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val data: JsonObject? = null,
)

/**
 * Where log lines go. Implementations stamp the timestamp (they own a [Clock]) and forward to the platform
 * logger, a run's live log, a file, or all of them. Must never throw and never block for long.
 */
fun interface LogSink {
    fun log(entry: LogEntry)
}

/**
 * The only logging front door (CONVENTIONS.md). Install a platform sink at process start (`AutoHostApp` for main,
 * `BaseTaskWorkerService` for workers); tests install a recording sink. The default sink discards — a library that
 * logs to nowhere must still be safe.
 */
object AhLog {
    @Volatile
    private var sink: LogSink = LogSink { }

    fun install(target: LogSink) {
        sink = target
    }

    fun log(level: LogLevel, tag: String, message: String, data: JsonObject? = null) {
        runCatching { sink.log(LogEntry(level, tag, message, data)) } // logging must never crash the caller
    }

    fun d(tag: String, message: String, data: JsonObject? = null) = log(LogLevel.DEBUG, tag, message, data)
    fun i(tag: String, message: String, data: JsonObject? = null) = log(LogLevel.INFO, tag, message, data)
    fun w(tag: String, message: String, data: JsonObject? = null) = log(LogLevel.WARN, tag, message, data)
    fun e(tag: String, message: String, data: JsonObject? = null) = log(LogLevel.ERROR, tag, message, data)
}

/** A sink that records entries in memory (tests and worker log buffering). Timestamps via the given clock. */
class RecordingLogSink(private val clock: Clock, private val capacity: Int = 500) : LogSink {
    private val entries = ArrayDeque<LogEntry>(capacity)

    @Synchronized
    override fun log(entry: LogEntry) {
        if (entries.size >= capacity) entries.removeFirst()
        entries.addLast(entry)
    }

    @Synchronized
    fun snapshot(): List<LogEntry> = entries.toList()
}
