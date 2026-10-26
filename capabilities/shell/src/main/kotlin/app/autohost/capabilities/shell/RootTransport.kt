package app.autohost.capabilities.shell

import java.util.concurrent.TimeUnit

/**
 * P9-02: the root transport (plan default 7 — last in the chain). Runs commands through `su -c`;
 * availability is probed once with `id` (memoized, cooldown) because a missing `su` binary simply
 * fails to spawn. JVM tests inject a [ProcessFactory] (real ProcessBuilder processes or fakes).
 */
class RootTransport(private val processFactory: ProcessFactory = ProcessBuilderFactory) : ShellTransport {

    interface ProcessFactory {
        fun start(command: String): Process

        /** Cheap probe command used to decide availability. */
        fun probe(): ProbeResult
    }

    data class ProbeResult(val available: Boolean, val detail: String)

    override val transportId: String = "root"

    private val lock = Any()
    private var cached: Pair<Long, TransportStatus>? = null

    override fun describe(): String = "root (su)"

    override fun status(): TransportStatus {
        val (at, value) = synchronized(lock) { cached } ?: Pair(Long.MIN_VALUE, TransportStatus.NeedsSetup("NO_ROOT"))
        if (System.nanoTime() / 1_000_000 - at < PROBE_COOLDOWN_MS) return value
        val probed = probe()
        synchronized(lock) { cached = Pair(System.nanoTime() / 1_000_000, probed) }
        return probed
    }

    override fun invalidateStatus() = synchronized(lock) { cached = null }

    private fun probe(): TransportStatus = when (val result = processFactory.probe()) {
        is ProbeResult -> if (result.available) TransportStatus.Live else TransportStatus.NeedsSetup("NO_ROOT")
    }

    override fun exec(command: String, timeoutMs: Long): ShellResult {
        if (status() !is TransportStatus.Live) {
            throw ShellTransportException(ShellTransportException.Reason.NOT_CONNECTED, "root (su) is not available on this device")
        }
        val process = try {
            processFactory.start(command)
        } catch (e: Exception) {
            throw ShellTransportException(ShellTransportException.Reason.NOT_CONNECTED, "su spawn failed: ${e.message}")
        }
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread { drain(process.inputStream, stdout) }.apply { isDaemon = true }
        val errThread = Thread { drain(process.errorStream, stderr) }.apply { isDaemon = true }
        outThread.start()
        errThread.start()
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw ShellTransportException(ShellTransportException.Reason.TIMEOUT, "root exec exceeded the deadline")
        }
        outThread.join(1_000)
        errThread.join(1_000)
        return ShellResult(stdout.toString(), stderr.toString(), exitCode = process.exitValue(), mode = "root")
    }

    private fun drain(stream: java.io.InputStream, into: StringBuilder) {
        stream.use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                into.append(String(buffer, 0, n, Charsets.UTF_8))
            }
        }
    }

    companion object {
        const val PROBE_COOLDOWN_MS: Long = 60_000
    }
}

/** Production factory: `su -c <command>` and a `su -c id` probe. */
object ProcessBuilderFactory : RootTransport.ProcessFactory {

    override fun start(command: String): Process =
        ProcessBuilder("su", "-c", command).start()

    override fun probe(): RootTransport.ProbeResult = try {
        val process = ProcessBuilder("su", "-c", "id").start()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            RootTransport.ProbeResult(false, "su probe timed out")
        } else if (process.exitValue() == 0) {
            RootTransport.ProbeResult(true, "su id ok")
        } else {
            RootTransport.ProbeResult(false, "su probe exit ${process.exitValue()}")
        }
    } catch (e: Exception) {
        RootTransport.ProbeResult(false, "su missing: ${e.javaClass.simpleName}")
    }
}
