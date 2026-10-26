package app.autohost.shell

import app.autohost.capabilities.shell.ShellResult
import app.autohost.capabilities.shell.ShellTransport
import app.autohost.capabilities.shell.ShellTransports
import app.autohost.capabilities.shell.TransportStatus
import app.autohost.capabilities.shell.UiAutomationSessionClient

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * P9-08 (app layer): the controller's lifecycle decisions — exclusivity refusals, spawn probe
 * retries and the a11y-reconnect close — driven through fake seams (no Android framework).
 */
class UiAutomationControllerTest {

    private class FakeTransport : ShellTransport {
        var execs = 0
        override val transportId: String = "fake"
        override fun describe(): String = "fake"
        override fun status(): TransportStatus = TransportStatus.Live
        override fun invalidateStatus() {}
        override fun exec(command: String, timeoutMs: Long): ShellResult {
            execs += 1
            return ShellResult("ok", "", 0, "shell_v2")
        }
    }

    /** An in-memory bidirectional connection the "runner" side answers from a script. */
    private class MemoryConnection : UiAutomationSessionClient.Connection {
        val fromRunner = ArrayDeque<String>()
        val toRunner = ArrayDeque<String>()
        var serving = false
        var closed = false

        override fun sendLine(line: String) {
            toRunner.addLast(line)
            // The fake runner answers ping only after "serving" flips true (the spawn beat).
            fromRunner.addLast(if (serving) "{\"ok\":true,\"pong\":true}" else "{\"ok\":false,\"error\":\"RUNNER_ERROR\"}")
        }

        override fun readLine(): String? = fromRunner.removeFirstOrNull()

        override fun close() {
            closed = true
        }
    }

    @Test fun `start refuses while a11y is connected`() {
        val transport = FakeTransport()
        val controller = UiAutomationController(
            transports = ShellTransports(listOf(transport)),
            socketFactory = { _, _ -> throw IllegalStateException("must never connect") },
            a11yConnected = { true },
            experimentalEnabled = { true },
        )
        val outcome = controller.start()
        assertTrue(outcome is UiAutomationController.StartOutcome.Refused)
        assertEquals("A11Y_SERVICE_ACTIVE", (outcome as UiAutomationController.StartOutcome.Refused).reason)
        assertEquals(0, transport.execs) // nothing spawned
        assertEquals(null, controller.session())
    }

    @Test fun `start refuses while the experimental toggle is off`() {
        val controller = UiAutomationController(
            transports = ShellTransports(listOf(FakeTransport())),
            socketFactory = { _, _ -> throw IllegalStateException("must never connect") },
            a11yConnected = { false },
            experimentalEnabled = { false },
        )
        assertEquals("UI_AUTOMATION_EXPERIMENTAL_OFF", (controller.start() as UiAutomationController.StartOutcome.Refused).reason)
    }

    @Test fun `start probes until the runner serves then reports live`() {
        val transport = FakeTransport()
        val connection = MemoryConnection()
        val controller = UiAutomationController(
            transports = ShellTransports(listOf(transport)),
            socketFactory = { _, _ -> connection },
            a11yConnected = { false },
            experimentalEnabled = { true },
        )
        // The runner comes up after two failed probes.
        val timer = Thread {
            Thread.sleep(200)
            connection.serving = true
        }
        timer.start()
        val outcome = controller.start()
        assertEquals(UiAutomationController.StartOutcome.STARTED, outcome)
        assertTrue(transport.execs >= 1)
        assertEquals(controller.session(), controller.session())
        timer.join()
        controller.stop("test-done")
        assertTrue(connection.closed)
        assertEquals(null, controller.session())
    }

    @Test fun `spawn failure with a dead runner is refused honestly`() {
        val transport = FakeTransport()
        val controller = UiAutomationController(
            transports = ShellTransports(listOf(transport)),
            socketFactory = { _, _ -> throw java.net.ConnectException("refused") },
            a11yConnected = { false },
            experimentalEnabled = { true },
            port = 1,
        )
        val outcome = controller.start()
        assertTrue(outcome is UiAutomationController.StartOutcome.Refused)
        assertTrue((outcome as UiAutomationController.StartOutcome.Refused).reason.startsWith("RUNNER_NOT_SERVING"))
        controller.stop("never-started") // no-op, must not throw
    }

    @Test fun `a11y reconnect closes the live session (G9-e)`() {
        val connection = MemoryConnection()
        connection.serving = true
        val controller = UiAutomationController(
            transports = ShellTransports(listOf(FakeTransport())),
            socketFactory = { _, _ -> connection },
            a11yConnected = { false },
            experimentalEnabled = { true },
        )
        assertEquals(UiAutomationController.StartOutcome.STARTED, controller.start())
        controller.onA11yEvent(a11yConnectedNow = true)
        assertEquals(null, controller.session())
        // And a reconnect event with no session is a no-op.
        controller.onA11yEvent(a11yConnectedNow = false)
        assertEquals(null, controller.session())
    }
}
