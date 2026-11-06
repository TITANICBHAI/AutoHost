package app.autohost.runtime.agent

import app.autohost.core.common.AhResult
import app.autohost.core.model.AhError
import app.autohost.core.model.ErrorCode
import app.autohost.core.model.TaskManifest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * P13-13 (AGENT_RUNTIME §12): the START preflight — every failure is a typed error with a fix
 * action and **starts no run**. The checks run in the §12 order; the first failure wins:
 *
 *  1. AI features enabled                       → `AI_DISABLED` (OPEN_AI_SETTINGS)
 *  2. provider profile + key present            → `AI_NOT_CONFIGURED` (OPEN_AI_SETTINGS)
 *  3. URL policy passes for the profile         → `AI_NOT_CONFIGURED` (OPEN_AI_SETTINGS)
 *  4. accessibility service connected           → `A11Y_SERVICE_DISABLED` (OPEN_ACCESSIBILITY_SETTINGS)
 *  5. screen state acceptable                   → `SCREEN_LOCKED`
 *  6. every target installed and not BLOCKED    → `APP_NOT_INSTALLED` / `OBSERVATION_BLOCKED`
 *  7. grants package-scoped — the four device.* grants must resolve RESTRICTED even in dev mode
 *     (the synthesized manifest's explicit scopes survive `putIfAbsent`) → `AI_GRANTS_TOO_BROAD`
 *     (OPEN_PERMISSION_REVIEW)
 *  8. budgets valid (a run with no effective budget refuses to start) → `BUDGET_INVALID`
 *  9. `config.agent` valid (goal, ≥ 1 target, supervised, tool narrowing ⊆ catalog) → `INVALID_ARGS`
 * 10. no other UI-exclusive run active          → `UI_RUN_ACTIVE`
 *
 * The ANDROID facts (a11y, screen, installed apps, another-run) arrive through [Facts] — the
 * :app starter gathers them; this class stays pure JVM and the §12 TABLE is what the tests pin.
 */
class AgentPreflight(private val facts: Facts) {

    data class Facts(
        val config: AgentConfig,
        val manifest: TaskManifest,
        val grants: Map<String, JsonObject>,
        val sensitivity: AppSensitivity,
        val catalogTools: Set<String>,
        // the :app-gathered facts
        val aiEnabled: Boolean,
        val profileReady: Boolean,
        val urlOk: Boolean,
        val a11yConnected: Boolean,
        val screenOk: Boolean,
        val installedPackages: Set<String>,
        val uiRunActive: Boolean,
    )

    fun check(): AhResult<Unit> {
        // 1–3: the AI stack
        if (!facts.aiEnabled) {
            return refused(ErrorCode.NEEDS_SETUP, "AI_DISABLED", "AI features are turned off", "OPEN_AI_SETTINGS")
        }
        if (!facts.profileReady) {
            return refused(ErrorCode.NEEDS_SETUP, "AI_NOT_CONFIGURED", "no AI provider profile or key", "OPEN_AI_SETTINGS")
        }
        if (!facts.urlOk) {
            return refused(ErrorCode.NEEDS_SETUP, "AI_NOT_CONFIGURED", "the provider URL was rejected by the URL policy", "OPEN_AI_SETTINGS")
        }
        // 4–5: the device state
        if (!facts.a11yConnected) {
            return refused(
                ErrorCode.NEEDS_SETUP, "A11Y_SERVICE_DISABLED",
                "the accessibility service is not connected", "OPEN_ACCESSIBILITY_SETTINGS",
            )
        }
        if (!facts.screenOk) {
            return refused(ErrorCode.NEEDS_SETUP, "SCREEN_LOCKED", "the screen must be unlocked", null)
        }
        // 6: the targets
        for (pkg in facts.config.packages) {
            if (pkg !in facts.installedPackages) {
                return refused(ErrorCode.NOT_FOUND, "APP_NOT_INSTALLED", "'$pkg' is not installed", null)
            }
            if (facts.sensitivity.isBlocked(pkg)) {
                return refused(ErrorCode.PERMISSION_DENIED, "OBSERVATION_BLOCKED", "'$pkg' is on the blocked list", null)
            }
        }
        // 7: grants package-scoped (AI_GRANTS_TOO_BROAD under dev mode TOO — the explicit scopes
        // from the synthesized manifest survive GrantBuilder's putIfAbsent; anything that still
        // resolves unrestricted here is refused)
        for (permission in SCOPED_PERMISSIONS) {
            if (!isPackageScoped(facts.grants[permission])) {
                return refused(
                    ErrorCode.PERMISSION_DENIED, "AI_GRANTS_TOO_BROAD",
                    "the '$permission' grant resolved unrestricted — agent runs need app-scoped grants",
                    "OPEN_PERMISSION_REVIEW",
                )
            }
        }
        // 8: budgets — a run with no effective budget refuses to start
        val budgets = facts.config.budgets
        if (budgets.maxTurns <= 0 || budgets.maxWallClockS <= 0 || budgets.maxModelCalls <= 0 ||
            budgets.maxTotalTokens <= 0 || budgets.maxActions <= 0 || budgets.maxOutputTokens <= 0 ||
            budgets.maxConsecutiveFailures <= 0
        ) {
            return refused(ErrorCode.INVALID_ARGS, "BUDGET_INVALID", "every budget must be positive", null)
        }
        // 9: config.agent
        if (facts.config.goal.isBlank()) {
            return refused(ErrorCode.INVALID_ARGS, null, "config.agent.goal must not be blank", null)
        }
        if (facts.config.packages.isEmpty()) {
            return refused(ErrorCode.INVALID_ARGS, null, "config.agent.packages must list at least one target app", null)
        }
        if (!facts.config.supervised) {
            return refused(ErrorCode.INVALID_ARGS, null, "Stage A runs are always supervised", null)
        }
        for (tool in facts.config.tools) {
            if (tool !in facts.catalogTools) {
                return refused(ErrorCode.INVALID_ARGS, "TOOL_NOT_ALLOWED", "tool '$tool' is not in the generated catalog", null)
            }
        }
        // 10: one UI run at a time
        if (facts.uiRunActive) {
            return refused(ErrorCode.CAPABILITY_UNAVAILABLE, "UI_RUN_ACTIVE", "another UI run is active", null)
        }
        return AhResult.ok(Unit)
    }

    private fun refused(code: ErrorCode, reason: String?, message: String, fixAction: String?): AhResult<Unit> =
        AhResult.err(AhError.of(code, message, reason = reason, fixAction = fixAction))

    companion object {
        /** The four grants §5.2 (2) refuses to start without a package scope. */
        val SCOPED_PERMISSIONS = listOf(
            "device.ui.observe", "device.ui.act", "device.app.query", "device.app.launch",
        )

        /**
         * The `CallContext.scopePackages` semantics (§5.1): null (unrestricted) when the grant
         * object is missing, has no `packages` key, is the literal `"*"`, or the grant is `{}`.
         * A `packages: []` IS restricted (the empty set) — useless but not too broad.
         */
        fun isPackageScoped(grant: JsonObject?): Boolean {
            grant ?: return false
            return when (val packages = grant["packages"]) {
                is JsonArray -> true // restricted to a set (possibly empty)
                is JsonPrimitive -> packages.content != "*" // a single explicit package restricts; "*" does not
                else -> false // absent key or `{}` → unrestricted
            }
        }
    }
}
