package app.autohost.capabilities.app

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.common.Clock
import app.autohost.core.common.ForegroundSource
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Launches a target app's launcher activity. The app wires this through the accessibility service context. */
fun interface ActivityLauncher {
    fun launch(packageName: String): Boolean
}

/** Background-process killer (ActivityManager.killBackgroundProcesses; wired from the app). */
fun interface BackgroundKiller {
    fun kill(packageName: String): Boolean
}

/**
 * The App-info UI stop path (open app details → Force stop → confirm), driven over the a11y capability.
 * Implemented in the app wiring (AppGraph) so `:capabilities:app` stays independent of `:capabilities:a11y`.
 */
interface AppStopUi {
    suspend fun forceStopViaUi(packageName: String): Boolean
}

/**
 * The `device.app` provider (HANDOFF §3): query, list, foreground, wait_foreground, launch, stop (P3-20).
 * Launch verifies via the foreground tracker and returns the spec shape `{result, strategy}`
 * (`launchResults` in schemas/errors.json); the stop method runs the [StopStrategies] chain.
 */
class AppProvider(
    private val packages: PackageReader,
    private val launcher: ActivityLauncher?,
    private val foreground: ForegroundSource,
    private val clock: Clock,
    private val killer: BackgroundKiller? = null,
    private val stopUi: AppStopUi? = null,
    private val pollIntervalMs: Long = 50,
) : CapabilityProvider {

    override val capabilityId: String = "device.app"
    override val providerId: String = "host.app.wave1"
    override val tier: String = "T0"

    override fun availability(env: DeviceEnv): Availability = Availability.Available

    override fun permissionFor(method: String, args: kotlinx.serialization.json.JsonObject): String? = when (method) {
        "query", "list", "foreground", "wait_foreground" -> "device.app.query"
        "launch" -> "device.app.launch"
        "stop" -> "device.app.stop"
        else -> null
    }

    override suspend fun invoke(ctx: CallContext, method: String, args: kotlinx.serialization.json.JsonObject): Envelope {
        return when (method) {
            "query" -> query(args)
            "list" -> list(args)
            "foreground" -> foregroundInfo()
            "wait_foreground" -> waitForeground(args)
            "launch" -> launch(args)
            "stop" -> stop(args)
            else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.app method '$method'")
        }
    }

    private fun query(args: kotlinx.serialization.json.JsonObject): Envelope {
        val pkg = args["package"]?.let { it as? JsonPrimitive }?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "query needs a 'package' argument")
        val info = packages.query(pkg)
        return Envelope.ok(
            buildJsonObject {
                put("installed", info != null)
                if (info != null) {
                    put("version_name", info.versionName)
                    info.versionCode?.let { put("version_code", it) }
                }
            },
        )
    }

    private fun list(args: kotlinx.serialization.json.JsonObject): Envelope {
        val filter = args["filter"]?.let { it as? JsonPrimitive }?.content
        val apps = packages.list(filter)
        return Envelope.ok(
            buildJsonObject {
                put(
                    "packages",
                    kotlinx.serialization.json.JsonArray(
                        apps.map { app ->
                            buildJsonObject {
                                put("package", app.pkg)
                                put("label", app.label)
                            }
                        },
                    ),
                )
            },
        )
    }

    private fun foregroundInfo(): Envelope {
        val current = foreground.current()
            ?: return Envelope.err(ErrorCode.NOT_FOUND, "nothing tracked in the foreground yet", reason = "NO_FOREGROUND")
        return Envelope.ok(
            buildJsonObject {
                put("package", current.pkg)
                current.activity?.let { put("activity", it) }
            },
        )
    }

    private suspend fun waitForeground(args: kotlinx.serialization.json.JsonObject): Envelope {
        val pkg = args["package"]?.let { it as? JsonPrimitive }?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "wait_foreground needs a 'package' argument")
        val timeoutMs = (args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 10_000
        val deadline = clock.monotonicMs() + timeoutMs
        while (true) {
            val current = foreground.current()
            if (current != null && current.pkg == pkg) {
                return Envelope.ok(buildJsonObject { put("foreground", true) })
            }
            if (clock.monotonicMs() >= deadline) {
                return Envelope.err(
                    ErrorCode.TIMEOUT,
                    "app '$pkg' did not come to the foreground in time",
                    reason = "FOREGROUND_TIMEOUT",
                )
            }
            delay(pollIntervalMs)
        }
    }

    private suspend fun launch(args: kotlinx.serialization.json.JsonObject): Envelope {
        val pkg = args["package"]?.let { it as? JsonPrimitive }?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "launch needs a 'package' argument")
        val activityLauncher = launcher ?: return Envelope.err(
            ErrorCode.NEEDS_SETUP,
            "Launching apps uses the accessibility service context; enable it first.",
            reason = "A11Y_SERVICE_DISABLED",
            fixAction = "OPEN_ACCESSIBILITY_SETTINGS",
        )
        val started = try {
            activityLauncher.launch(pkg)
        } catch (e: Exception) {
            false
        }
        if (!started) {
            return Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "could not start an activity for '$pkg'", reason = "LAUNCH_FAILED")
        }
        val verified = waitForeground(
            buildJsonObject {
                put("package", pkg)
                put("timeout_ms", LAUNCH_VERIFY_TIMEOUT_MS)
            },
        ).ok
        // Spec shape (capability-specs.w1.json `launchResults`): LAUNCHED_VERIFIED | LAUNCHED_UNVERIFIED | FAILED.
        return Envelope.ok(
            buildJsonObject {
                put("result", if (verified) "LAUNCHED_VERIFIED" else "LAUNCHED_UNVERIFIED")
                put("strategy", "a11y_context")
            },
        )
    }

    /**
     * The stop chain (P3-20): best-effort background kill, then the App-info UI path when wired. Verified
     * only when the target actually left the foreground; otherwise BEST_EFFORT / FAILED — never assumed.
     */
    private suspend fun stop(args: kotlinx.serialization.json.JsonObject): Envelope {
        val pkg = args["package"]?.let { it as? JsonPrimitive }?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "stop needs a 'package' argument")
        val requested = args["strategy"]?.let { it as? JsonPrimitive }?.content ?: "auto"
        if (requested == "shell") {
            return Envelope.err(
                ErrorCode.CAPABILITY_UNAVAILABLE,
                "the shell stop strategy arrives with the shell tier (Phase 9)",
                reason = "SHELL_TIER_NOT_PAIRED",
            )
        }
        val uiAvailable = stopUi != null
        // VERIFIED means the target *was* the tracked foreground and left it; a background app that we
        // cannot observe is only ever BEST_EFFORT (ADR-037).
        val wasForeground = foreground.current()?.pkg == pkg
        val attempts = ArrayList<StopStrategies.Attempt>()
        var loopGuard = 0
        while (loopGuard++ < 4) {
            when (val decision = StopStrategies.next(attempts, requested, uiAvailable, foregroundLeft = wasForeground && leftForeground(pkg))) {
                is StopStrategies.Decision.Next -> {
                    val applied = when (decision.method) {
                        StopStrategies.Method.KILL_BACKGROUND -> killer?.kill(pkg) ?: false
                        StopStrategies.Method.UI_FORCE_STOP -> stopUi?.forceStopViaUi(pkg) ?: false
                    }
                    attempts.add(StopStrategies.Attempt(decision.method, applied))
                    delay(STOP_SETTLE_MS) // let window-state events reach the foreground tracker
                }
                is StopStrategies.Decision.Done -> {
                    if (decision.result == StopStrategies.FAILED) {
                        return Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "the app could not be stopped", reason = "STOP_FAILED")
                    }
                    return Envelope.ok(
                        buildJsonObject {
                            put("result", decision.result)
                            put("method", decision.method?.name?.lowercase() ?: "none")
                        },
                    )
                }
            }
        }
        return Envelope.err(ErrorCode.INTERNAL, "stop chain did not converge")
    }

    private fun leftForeground(pkg: String): Boolean = foreground.current()?.pkg != pkg // target no longer foreground

    companion object {
        const val LAUNCH_VERIFY_TIMEOUT_MS = 5_000L
        const val STOP_SETTLE_MS = 400L
    }
}
