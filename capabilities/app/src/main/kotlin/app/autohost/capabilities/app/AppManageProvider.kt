package app.autohost.capabilities.app

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import app.autohost.capabilities.shell.ShellCommands
import app.autohost.capabilities.shell.ShellTransport
import app.autohost.capabilities.shell.ShellTransportException
import app.autohost.capabilities.shell.ShellTransports
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P9-09: the `device.app.manage` capability (plan §6.1 W4 — T3, DANGEROUS). The `app_manage`
 * insecure-feature switch is enforced upstream by the P8-13 interceptor; the per-run
 * `device.app.manage` grant is checked here. Methods:
 *
 *  - `force_stop` {package} → the verified chain (plan: "verified force-stop"): shell
 *    `am force-stop` first (a real exit code over shell,v2 — VERIFIED), then the T1/T0 fallbacks
 *    from the stop chain (BEST_EFFORT/FAILED honestly).
 *  - `clear_state` {package, confirm} → `pm clear` (VERIFIED on exit 0 + "Success").
 *  - `grant_permission` / `revoke_permission` {package, permission} → `pm grant/revoke`
 *    (applied on exit 0; no readback claim — honest `verified:false`).
 *  - `install` {path, confirm, mode?} → `pm install` over the shell uid; `mode:"installer"`
 *    routes the APK through the system installer UI with the a11y auto-accept watcher
 *    ([InstallUi] seam) and reports WAITING_USER when the confirm dialog never resolves.
 *  - `uninstall` {package, confirm} → `pm uninstall`.
 *
 * The destructive methods demand an explicit `confirm:true` (AppManagePolicy) — an accidental
 * `pm clear` must never be one forgotten flag away.
 */
class AppManageProvider(
    private val transports: ShellTransports?,
    private val killer: BackgroundKiller? = null,
    private val stopUi: AppStopUi? = null,
    private val installer: InstallUi? = null,
) : CapabilityProvider {

    /** Installer-UI path (app wiring): fires the view intent + the auto-accept watcher flow. */
    fun interface InstallUi {
        suspend fun installViaUi(apkDevicePath: String): String // INSTALLED_CONFIRMED | WAITING_USER | FAILED
    }

    override val capabilityId: String = "device.app.manage"
    override val providerId: String = "shell.appmanage"
    override val tier: String = "T3"

    override fun availability(env: DeviceEnv): Availability {
        if (installer != null && stopUi != null) return Availability.Available
        return when (transports?.firstLive()) {
            null -> Availability.NeedsSetup("SHELL_TIER_NOT_PAIRED", "OPEN_SHELL_SETUP")
            else -> Availability.Available
        }
    }

    override fun permissionFor(method: String, args: JsonObject): String? =
        if (method in METHODS) "device.app.manage" else null

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope {
        if (ctx.grants["device.app.manage"] == null) {
            return Envelope.err(
                ErrorCode.PERMISSION_DENIED,
                "the run has no device.app.manage grant",
                fixAction = "OPEN_PERMISSION_REVIEW",
            )
        }
        return try {
            when (method) {
                "force_stop" -> forceStop(args)
                "clear_state" -> clearState(args)
                "grant_permission" -> permission(args, revoke = false)
                "revoke_permission" -> permission(args, revoke = true)
                "install" -> install(args)
                "uninstall" -> uninstall(args)
                else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown device.app.manage method '$method'")
            }
        } catch (e: ShellTransportException) {
            when (e.reason) {
                ShellTransportException.Reason.TIMEOUT -> Envelope.err(ErrorCode.TIMEOUT, e.message ?: "shell timed out")
                ShellTransportException.Reason.NOT_CONNECTED -> Envelope.err(
                    ErrorCode.NEEDS_SETUP,
                    e.message ?: "no shell transport is connected",
                    reason = "SHELL_TIER_NOT_PAIRED",
                    fixAction = "OPEN_SHELL_SETUP",
                )
                else -> Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, e.message ?: "shell transport failed")
            }
        }
    }

    private suspend fun forceStop(args: JsonObject): Envelope {
        val pkg = packageArg(args) ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"package\" is required")
        AppManagePolicy.packageVerdict(pkg)?.let {
            return Envelope.err(ErrorCode.INVALID_ARGS, it)
        }
        // 1) Shell: `am force-stop` — a real exit code, the only verified path.
        val shell = transports?.firstLive()
        if (shell != null) {
            val result = shell.exec(ShellCommands.forceStop(pkg), FORCE_STOP_TIMEOUT_MS)
            val ok = result.exitCode == null || result.exitCode == 0
            return Envelope.ok(buildJsonObject {
                put("result", if (ok) VERIFIED else FAILED)
                put("method", "shell")
                put("exit_code", result.exitCode)
            })
        }
        // 2) T1 App-info UI path (the P3-20 machinery, same seams).
        val ui = stopUi
        if (ui != null) {
            val applied = runCatching { ui.forceStopViaUi(pkg) }.getOrDefault(false)
            return Envelope.ok(buildJsonObject {
                put("result", if (applied) BEST_EFFORT else FAILED)
                put("method", "ui")
                put("verified", false)
            })
        }
        // 3) T0 background kill, honestly best-effort.
        val killed = killer?.kill(pkg) ?: false
        return Envelope.ok(buildJsonObject {
            put("result", if (killed) BEST_EFFORT else FAILED)
            put("method", "kill_background")
            put("verified", false)
        })
    }

    private fun clearState(args: JsonObject): Envelope {
        val pkg = packageArg(args) ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"package\" is required")
        AppManagePolicy.packageVerdict(pkg)?.let { return Envelope.err(ErrorCode.INVALID_ARGS, it) }
        confirm(args)?.let { return it }
        return shellOrSetup { live ->
            val result = live.exec(ShellCommands.pmClear(pkg), CLEAR_TIMEOUT_MS)
            val ok = result.exitCode == 0 && result.stdout.contains("Success", ignoreCase = true)
            if (ok) {
                Envelope.ok(buildJsonObject { put("result", VERIFIED); put("method", "pm_clear") })
            } else {
                Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "pm clear failed", reason = "APP_MANAGE_FAILED")
            }
        }
    }

    private fun permission(args: JsonObject, revoke: Boolean): Envelope {
        val pkg = packageArg(args) ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"package\" is required")
        val permission = (args["permission"] as? JsonPrimitive)?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"permission\" is required")
        AppManagePolicy.packageVerdict(pkg)?.let { return Envelope.err(ErrorCode.INVALID_ARGS, it) }
        AppManagePolicy.permissionVerdict(permission)?.let { return Envelope.err(ErrorCode.INVALID_ARGS, it) }
        return shellOrSetup { live ->
            val command = if (revoke) ShellCommands.pmRevoke(pkg, permission) else ShellCommands.pmGrant(pkg, permission)
            val result = live.exec(command, ACT_TIMEOUT_MS)
            if (result.exitCode == 0) {
                Envelope.ok(buildJsonObject {
                    put("result", VERIFIED)
                    put("method", if (revoke) "pm_revoke" else "pm_grant")
                    put("verified", false) // no readback claim; Gate-9 records the platform behaviour
                })
            } else {
                Envelope.err(
                    ErrorCode.CAPABILITY_UNAVAILABLE,
                    "pm ${if (revoke) "revoke" else "grant"} failed: ${result.stderr.take(200)}",
                    reason = "APP_MANAGE_FAILED",
                )
            }
        }
    }

    private suspend fun install(args: JsonObject): Envelope {
        val path = (args["path"] as? JsonPrimitive)?.content
            ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"path\" is required")
        AppManagePolicy.apkPathVerdict(path)?.let { return Envelope.err(ErrorCode.INVALID_ARGS, it) }
        confirm(args)?.let { return it }
        val mode = (args["mode"] as? JsonPrimitive)?.content ?: "pm"
        if (mode == "installer") {
            val ui = installer ?: return Envelope.err(
                ErrorCode.CAPABILITY_UNAVAILABLE,
                "the installer UI path needs the accessibility service",
                reason = "A11Y_SERVICE_DISABLED",
                fixAction = "OPEN_ACCESSIBILITY_SETTINGS",
            )
            return when (val outcome = ui.installViaUi(path)) {
                "INSTALLED_CONFIRMED" -> Envelope.ok(buildJsonObject { put("result", VERIFIED); put("method", "installer") })
                "WAITING_USER" -> Envelope.ok(buildJsonObject {
                    put("result", "WAITING_USER")
                    put("method", "installer")
                    put("verified", false)
                })
                else -> Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "installer path failed", reason = "APP_MANAGE_FAILED")
            }
        }
        if (mode != "pm") {
            return Envelope.err(ErrorCode.INVALID_ARGS, "mode must be pm|installer")
        }
        return shellOrSetup { live ->
            // Size first (`stat -c %s`), then the streamed install. Two execs, both internal commands.
            val stat = live.exec("stat -c %s $path", ACT_TIMEOUT_MS)
            val size = stat.stdout.trim().toLongOrNull()
                ?: return@shellOrSetup Envelope.err(ErrorCode.NOT_FOUND, "the apk path is not readable by the shell uid", reason = "APK_NOT_FOUND")
            if (size <= 0 || size > ShellCommands.MAX_APK_BYTES) {
                return@shellOrSetup Envelope.err(ErrorCode.INVALID_ARGS, "apk size out of range")
            }
            val result = live.exec(ShellCommands.pmInstall(path, size), INSTALL_TIMEOUT_MS)
            val ok = result.exitCode == 0 && result.stdout.contains("Success", ignoreCase = true)
            if (ok) {
                Envelope.ok(buildJsonObject { put("result", VERIFIED); put("method", "pm") })
            } else {
                Envelope.err(
                    ErrorCode.CAPABILITY_UNAVAILABLE,
                    "pm install failed: ${result.stderr.take(200)}",
                    reason = "APP_MANAGE_FAILED",
                )
            }
        }
    }

    private fun uninstall(args: JsonObject): Envelope {
        val pkg = packageArg(args) ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"package\" is required")
        AppManagePolicy.packageVerdict(pkg)?.let { return Envelope.err(ErrorCode.INVALID_ARGS, it) }
        confirm(args)?.let { return it }
        return shellOrSetup { live ->
            val result = live.exec(ShellCommands.pmUninstall(pkg), UNINSTALL_TIMEOUT_MS)
            when {
                result.exitCode == 0 -> Envelope.ok(buildJsonObject { put("result", VERIFIED); put("method", "pm_uninstall") })
                result.stdout.contains("not installed", ignoreCase = true) ||
                    result.stderr.contains("not installed", ignoreCase = true) ->
                    Envelope.err(ErrorCode.NOT_FOUND, "package '$pkg' is not installed", reason = "APP_NOT_FOUND")
                else -> Envelope.err(
                    ErrorCode.CAPABILITY_UNAVAILABLE,
                    "pm uninstall failed: ${result.stderr.take(200)}",
                    reason = "APP_MANAGE_FAILED",
                )
            }
        }
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private fun shellOrSetup(block: (ShellTransport) -> Envelope): Envelope {
        val live = transports?.firstLive()
            ?: return Envelope.err(
                ErrorCode.NEEDS_SETUP,
                "no shell transport is connected",
                reason = "SHELL_TIER_NOT_PAIRED",
                fixAction = "OPEN_SHELL_SETUP",
            )
        return block(live)
    }

    private fun packageArg(args: JsonObject): String? =
        (args["package"] as? JsonPrimitive)?.content

    private fun confirm(args: JsonObject): Envelope? =
        if ((args["confirm"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true) {
            null
        } else {
            Envelope.err(ErrorCode.SCOPE_VIOLATION, AppManagePolicy.CONFIRM_DETAIL, fixAction = "OPEN_PERMISSION_REVIEW")
        }

    companion object {
        val METHODS = setOf("force_stop", "clear_state", "grant_permission", "revoke_permission", "install", "uninstall")
        const val VERIFIED = "VERIFIED"
        const val BEST_EFFORT = "BEST_EFFORT"
        const val FAILED = "FAILED"
        const val FORCE_STOP_TIMEOUT_MS = 10_000L
        const val CLEAR_TIMEOUT_MS = 20_000L
        const val UNINSTALL_TIMEOUT_MS = 30_000L
        const val INSTALL_TIMEOUT_MS = 120_000L
        const val ACT_TIMEOUT_MS = 10_000L
    }
}
