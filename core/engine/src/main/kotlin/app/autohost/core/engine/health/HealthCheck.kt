package app.autohost.core.engine.health

/**
 * P2-14: the Setup & Health framework (plan §13.2). Every row in the UI and every `health.*` Dev API /
 * `autohost doctor` result is a [HealthCheck]. Checks are pure: they read a [HealthEnv] snapshot the Android
 * layer fills in, so the whole registry runs (and is tested) on the JVM.
 *
 * The UI and `doctor` group results as: **Needs fixing now** (required, no autofix) → **Can fix automatically**
 * → **Optional**. CLI exit code 127 when group one is non-empty.
 */

/** Snapshot of the device state relevant to health checks. Built once per check run by the Android layer. */
data class HealthEnv(
    val sdkInt: Int,
    val flavor: String = "core",
    val accessibilityEnabled: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val accessibilityBlockedByPolicy: Boolean = false,
    val notificationsPermission: Boolean = false,
    val notificationListenerConnected: Boolean = false,
    val exactAlarmsAllowed: Boolean = false,
    val batteryExemption: Boolean = false,
    val displayOverApps: Boolean = false,
    val usageAccess: Boolean = false,
    val writeSecureSettings: Boolean = false,
    val secureSettingsCanSelfHeal: Boolean = false,
    val shellTier: ShellTier = ShellTier.NONE,
    val devApiEnabled: Boolean = false,
    val imeSelected: Boolean = false,
    val screenGuardActive: Boolean = false,
    val insecureSwitches: Map<String, Boolean> = emptyMap(),
    val workerAlive: Boolean = false,
) {
    enum class ShellTier { NONE, ADB_TCP, WIRELESS, SHIZUKU, ROOT }
}

sealed interface CheckResult {
    data object Ok : CheckResult
    data class Issue(
        val reasonCode: String,
        val message: String,
        val fixAction: String? = null,
        val autofix: Boolean = false,
    ) : CheckResult
}

/** A fix action that runs on the Android side; the pure registry only knows whether one exists. */
fun interface CheckFix {
    fun apply(env: HealthEnv): FixOutcome
}

enum class FixStatus { DONE, NEEDS_USER, FAILED }

data class FixOutcome(val status: FixStatus, val message: String)

abstract class HealthCheck(
    val id: String,
    val title: String,
    val required: Boolean,
    val description: String = "",
) {
    abstract fun appliesTo(env: HealthEnv): Boolean
    abstract fun diagnose(env: HealthEnv): CheckResult

    /** Android-side fix; null = manual only. */
    open val fix: CheckFix? = null

    fun hasAutofix(): Boolean = fix != null

    data class Outcome(val check: HealthCheck, val result: CheckResult)
}

/**
 * Registry + grouping. Checks self-filter by [HealthCheck.appliesTo] (API level, flavor), so the same
 * registry drives the Setup screen, `autohost doctor` and the Dev API `health.run`.
 */
class HealthRegistry {

    private val checks = LinkedHashMap<String, HealthCheck>()

    fun register(check: HealthCheck) {
        require(checks[check.id] == null) { "duplicate health check id ${check.id}" }
        checks[check.id] = check
    }

    fun all(): List<HealthCheck> = checks.values.toList()

    /** All applicable checks with their results, in registry order. */
    fun runAll(env: HealthEnv): List<HealthCheck.Outcome> =
        checks.values.filter { it.appliesTo(env) }.map { HealthCheck.Outcome(it, it.diagnose(env)) }

    /** Grouped for UI/CLI (plan §13.2). */
    data class Grouped(
        val needsFix: List<HealthCheck.Outcome>,
        val autofixable: List<HealthCheck.Outcome>,
        val optional: List<HealthCheck.Outcome>,
        val healthy: List<HealthCheck.Outcome>,
    ) {
        val hasMajorIssues: Boolean get() = needsFix.isNotEmpty()
    }

    fun runGrouped(env: HealthEnv): Grouped {
        val outcomes = runAll(env)
        val needsFix = ArrayList<HealthCheck.Outcome>()
        val autofixable = ArrayList<HealthCheck.Outcome>()
        val optional = ArrayList<HealthCheck.Outcome>()
        val healthy = ArrayList<HealthCheck.Outcome>()
        for (outcome in outcomes) {
            val issue = outcome.result as? CheckResult.Issue
            when {
                outcome.result == CheckResult.Ok -> healthy += outcome
                issue != null && issue.autofix && outcome.check.fix != null -> autofixable += outcome
                outcome.check.required -> needsFix += outcome
                else -> optional += outcome
            }
        }
        return Grouped(needsFix, autofixable, optional, healthy)
    }
}

/** The initial check set (plan §13.2); Android layer extends this with OEM-specific rows. */
object InitialChecks {

    fun registerAll(registry: HealthRegistry) {
        registry.register(object : HealthCheck(
            id = "accessibility",
            title = "Accessibility service",
            required = true,
            description = "AutoHost needs the accessibility service to observe and act on other apps' UI.",
        ) {
            override fun appliesTo(env: HealthEnv) = true
            override fun diagnose(env: HealthEnv): CheckResult = when {
                env.accessibilityBlockedByPolicy -> CheckResult.Issue(
                    "A11Y_BLOCKED_ADVANCED_PROTECTION",
                    "Accessibility is blocked by system policy (Advanced Protection / restricted settings).",
                    fixAction = "OPEN_RESTRICTED_SETTINGS_HELP",
                )
                !env.accessibilityEnabled -> CheckResult.Issue(
                    "A11Y_SERVICE_DISABLED",
                    "The AutoHost accessibility service is off. Turn it on to run tasks that use the UI.",
                    fixAction = "OPEN_ACCESSIBILITY_SETTINGS",
                )
                !env.accessibilityConnected -> CheckResult.Issue(
                    "A11Y_SERVICE_NOT_CONNECTED",
                    "The service is enabled but not connected yet — toggle it once or reboot.",
                    fixAction = "OPEN_ACCESSIBILITY_SETTINGS",
                )
                else -> CheckResult.Ok
            }
        })

        registry.register(object : HealthCheck(
            id = "notifications",
            title = "Notifications permission",
            required = true,
            description = "Shows run status and the Stop button for every active run.",
        ) {
            override fun appliesTo(env: HealthEnv) = env.sdkInt >= 33
            override fun diagnose(env: HealthEnv): CheckResult =
                if (env.notificationsPermission) CheckResult.Ok
                else CheckResult.Issue(
                    "NOTIFICATIONS_DENIED",
                    "Without notifications you will not see active runs or be able to stop them from the shade.",
                    fixAction = "OPEN_NOTIFICATION_SETTINGS",
                )
        })

        registry.register(object : HealthCheck(
            id = "notification_listener",
            title = "Notification access",
            required = false,
            description = "Needed only for tasks that listen to other apps' notifications.",
        ) {
            override fun appliesTo(env: HealthEnv) = true
            override fun diagnose(env: HealthEnv): CheckResult =
                if (env.notificationListenerConnected) CheckResult.Ok
                else CheckResult.Issue(
                    "NOTIFICATION_LISTENER_OFF",
                    "Optional: grant notification access to let tasks react to notifications.",
                    fixAction = "OPEN_NOTIFICATION_LISTENER_SETTINGS",
                )
        })

        registry.register(object : HealthCheck(
            id = "exact_alarms",
            title = "Exact alarms",
            required = false,
            description = "Keeps schedules on time; without it timing may drift by minutes.",
        ) {
            override fun appliesTo(env: HealthEnv) = env.sdkInt >= 31
            override fun diagnose(env: HealthEnv): CheckResult =
                if (env.exactAlarmsAllowed) CheckResult.Ok
                else CheckResult.Issue(
                    "EXACT_ALARM_DENIED",
                    "Schedules will use WorkManager and may run late. Allow exact alarms for precise timing.",
                    fixAction = "OPEN_EXACT_ALARM_SETTINGS",
                )
        })

        registry.register(object : HealthCheck(
            id = "battery",
            title = "Battery exemption",
            required = false,
            description = "Prevents the OEM from killing background runs.",
        ) {
            override fun appliesTo(env: HealthEnv) = true
            override fun diagnose(env: HealthEnv): CheckResult =
                if (env.batteryExemption) CheckResult.Ok
                else CheckResult.Issue(
                    "BATTERY_RESTRICTIONS_ON",
                    "Battery optimization may pause background runs. Unrestricted is recommended for scheduled tasks.",
                    fixAction = "OPEN_BATTERY_SETTINGS",
                )
        })

        registry.register(object : HealthCheck(
            id = "write_secure_settings",
            title = "WRITE_SECURE_SETTINGS grant",
            required = false,
            description = "Enables self-healing (re-enabling accessibility, selecting the keyboard) after reboots.",
        ) {
            override fun appliesTo(env: HealthEnv) = true
            override fun diagnose(env: HealthEnv): CheckResult = when {
                env.writeSecureSettings && env.secureSettingsCanSelfHeal -> CheckResult.Ok
                env.writeSecureSettings -> CheckResult.Issue(
                    "SECURE_SETTINGS_INCOMPLETE",
                    "Grant present but self-heal is not fully configured; run the grant helper again.",
                    fixAction = "RUN_SECURE_SETTINGS_HELPER",
                )
                else -> CheckResult.Issue(
                    "SECURE_SETTINGS_NOT_GRANTED",
                    "Optional one-time PC command enables automatic recovery after reboots.",
                    fixAction = "SHOW_SECURE_SETTINGS_COMMAND",
                )
            }
        })

        registry.register(object : HealthCheck(
            id = "shell_tier",
            title = "Shell tier",
            required = false,
            description = "PC-assisted ADB pairing unlocks shell-level capabilities on this device.",
        ) {
            override fun appliesTo(env: HealthEnv) = true
            override fun diagnose(env: HealthEnv): CheckResult =
                if (env.shellTier != HealthEnv.ShellTier.NONE) CheckResult.Ok
                else CheckResult.Issue(
                    "SHELL_TIER_NOT_PAIRED",
                    "Optional: pair over ADB (PC-assisted per boot on Android 10) for shell capabilities.",
                    fixAction = "OPEN_SHELL_PAIRING_GUIDE",
                )
        })

        registry.register(object : HealthCheck(
            id = "dev_api",
            title = "Dev API",
            required = false,
            description = "Lets a PC push, run and inspect tasks while authoring. Off by default.",
        ) {
            override fun appliesTo(env: HealthEnv) = true
            override fun diagnose(env: HealthEnv): CheckResult =
                if (env.devApiEnabled) CheckResult.Ok
                else CheckResult.Issue(
                    "DEV_API_OFF",
                    "Optional: enable in Settings → Developer to author tasks from a PC.",
                    fixAction = "OPEN_DEV_API_SETTINGS",
                )
        })

        registry.register(object : HealthCheck(
            id = "worker",
            title = "Python runtime",
            required = true,
            description = "The worker process that executes Python tasks.",
        ) {
            override fun appliesTo(env: HealthEnv) = true
            override fun diagnose(env: HealthEnv): CheckResult =
                if (env.workerAlive) CheckResult.Ok
                else CheckResult.Issue(
                    "WORKER_NOT_STARTED",
                    "The Python runtime has not started yet; it starts with the first run.",
                    fixAction = "START_TEST_RUN",
                )
        })
    }
}
