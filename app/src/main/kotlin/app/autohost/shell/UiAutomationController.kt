package app.autohost.shell

import app.autohost.capabilities.shell.ShellTransports
import app.autohost.capabilities.shell.UiAutomationExclusivity
import app.autohost.capabilities.shell.UiAutomationSessionClient
import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import kotlinx.serialization.json.put

/**
 * P9-08: the UiAutomation session controller — the only place that starts or stops an exclusive
 * session (ADR-068). The lifecycle is deliberately small:
 *
 *  1. `start()` refuses while the accessibility service is connected or the experimental toggle
 *     is off ([UiAutomationExclusivity.canStart]);
 *  2. the runner is spawned through the first live shell transport (`am instrument`, no `-w`);
 *  3. the client connects to the loopback port and the session is live;
 *  4. `onA11yEvent` closes the session the moment the accessibility service reconnects — the two
 *     providers can never be active together (G9-e).
 *
 * Everything device-touching is UNVERIFIED-on-hardware until Gate-9 (plan S4): whether the a11y
 * service is really suppressed on the phone, how long `am instrument` takes to serve, OEM quirks.
 */
class UiAutomationController(
    private val transports: ShellTransports,
    private val socketFactory: UiAutomationSessionClient.SocketFactory,
    private val a11yConnected: () -> Boolean,
    private val experimentalEnabled: () -> Boolean,
    private val port: Int = UiAutomationRunner.DEFAULT_PORT,
) {

    @Volatile
    private var client: UiAutomationSessionClient? = null

    /** The provider's session accessor — null while no exclusive session is live. */
    fun session(): UiAutomationSessionClient? = client

    @Synchronized
    fun start(): StartOutcome {
        if (client != null) return StartOutcome.ALREADY_LIVE
        val decision = UiAutomationExclusivity.canStart(a11yConnected(), experimentalEnabled())
        if (decision is UiAutomationExclusivity.StartDecision.Refused) {
            return StartOutcome.Refused(decision.reason)
        }
        val transport = transports.firstLive()
            ?: return StartOutcome.Refused("SHELL_TIER_NOT_PAIRED")
        return try {
            transport.exec(UiAutomationRunner.startCommand(port), SPAWN_TIMEOUT_MS)
            // The runner needs a beat to open the server socket; probe with a bounded retry.
            var lastError: Exception? = null
            repeat(10) {
                try {
                    val connection = socketFactory.open("127.0.0.1", port)
                    val candidate = UiAutomationSessionClient(connection)
                    if (candidate.ping()) {
                        client = candidate
                        AhLog.i(TAG, "ui automation session live", AhJson.meta { put("port", port) })
                        return StartOutcome.STARTED
                    }
                    candidate.close()
                } catch (e: Exception) {
                    lastError = e
                }
                Thread.sleep(150)
            }
            StartOutcome.Refused("RUNNER_NOT_SERVING${lastError?.let { ": ${it.message}" } ?: ""}")
        } catch (e: Exception) {
            StartOutcome.Refused("SPAWN_FAILED${e.message?.let { ": $it" } ?: ""}")
        }
    }

    @Synchronized
    fun stop(reason: String) {
        val existing = client ?: return
        existing.quit()
        client = null
        AhLog.i(TAG, "ui automation session closed", AhJson.meta { put("reason", reason) })
    }

    /** G9-e hook: the a11y bridge calls this on connect events; a reconnect closes the session. */
    fun onA11yEvent(a11yConnectedNow: Boolean) {
        val decision = UiAutomationExclusivity.onA11yEvent(sessionLive = client != null, a11yConnected = a11yConnectedNow)
        if (decision is UiAutomationExclusivity.A11yEventDecision.CloseSession) {
            stop(decision.reason)
        }
    }

    sealed class StartOutcome {
        data object STARTED : StartOutcome()
        data object ALREADY_LIVE : StartOutcome()
        data class Refused(val reason: String) : StartOutcome()
    }

    companion object {
        private const val TAG = "uiautomation"
        const val SPAWN_TIMEOUT_MS = 10_000L
    }
}
