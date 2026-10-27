package app.autohost.shell

import app.autohost.capabilities.shell.ShellResult
import app.autohost.capabilities.shell.ShizukuTransport

/**
 * P9-02: the Shizuku-backed [ShizukuTransport.Exec] — the only file that touches the Shizuku API
 * (`dev.rikka.shizuku:api`, Apache-2.0, ADR-064), isolated here so the capability module and its
 * tests stay dependency-free.
 *
 * Availability = `Shizuku.pingBinder()` (the server is running) AND the permission granted. Exec
 * uses Shizuku's deprecated process facade reflectively because API 13.1.5 made it private; this
 * keeps the dependency isolated and produces an explicit failure if that compatibility surface is removed.
 * [app.autohost.capabilities.shell.RootTransport].
 *
 * First-build note: confirm the `newProcess(String[], String[], String?)` facade against the
 * pinned 13.1.5 API; if it moved, swap this file's internals — the transport seam does not change.
 */
class ShizukuProcessExec : ShizukuTransport.Exec {

    override fun isReady(): Boolean = runCatching {
        rikka.shizuku.Shizuku.pingBinder() &&
            rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    override fun run(command: String, timeoutMs: Long): ShellResult {
        val process = try {
            val method = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        } catch (e: ReflectiveOperationException) {
            throw app.autohost.capabilities.shell.ShellTransportException(
                app.autohost.capabilities.shell.ShellTransportException.Reason.DEVICE_CLOSED,
                "Shizuku process execution is unavailable: ${e.message}",
            )
        }
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread { drain(process.inputStream, stdout) }.apply { isDaemon = true }
        val errThread = Thread { drain(process.errorStream, stderr) }.apply { isDaemon = true }
        outThread.start()
        errThread.start()
        val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw app.autohost.capabilities.shell.ShellTransportException(
                app.autohost.capabilities.shell.ShellTransportException.Reason.TIMEOUT,
                "shizuku exec exceeded the deadline",
            )
        }
        outThread.join(1_000)
        errThread.join(1_000)
        return ShellResult(stdout.toString(), stderr.toString(), exitCode = process.exitValue(), mode = "shizuku")
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
}
