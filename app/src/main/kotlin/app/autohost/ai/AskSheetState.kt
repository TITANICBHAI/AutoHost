package app.autohost.ai

import app.autohost.core.common.AhResult
import app.autohost.core.engine.orchestrator.RunRecord
import app.autohost.core.llm.ProviderProfile

/**
 * P14-07 (S33, UI_SPEC §10, AGENT_RUNTIME §4/§12): the Ask sheet state — pure and synchronous.
 *
 *  - the goal is clamped to 2 000 chars (§4);
 *  - target apps are EXPLICIT and REQUIRED (≥ 1) — the foreground app is only ever a suggestion;
 *  - the provider comes from the AI settings' profiles (P14-06); a tighter budget is OPTIONAL and
 *    may only TIGHTEN the §4.2 host ceilings (the state clamps, the preflight re-checks);
 *  - starting goes through the [Starter] seam (the real `AgentRunStarter` with its §12 preflight);
 *    a refusal surfaces as a typed reason and NO run starts;
 *  - supervised by default — the sheet offers no way to turn supervision off (Stage A).
 */
class AskSheetState(private val starter: Starter) {

    fun interface Starter {
        suspend fun start(goal: String, packages: List<String>, profileId: String?, agentOverride: kotlinx.serialization.json.JsonObject): AhResult<RunRecord>
    }

    /** One launchable installed app for the picker (R4: the proper app-picker widget). */
    data class AppEntry(val pkg: String, val label: String)

    data class Model(
        val goal: String = "",
        val selectedPackages: Set<String> = emptySet(),
        val availableApps: List<AppEntry> = emptyList(),
        val appQuery: String = "",
        val selectedProfileId: String? = null,
        val maxTurnsOverride: Int? = null,
        val maxWallClockSOverride: Long? = null,
        val aiEnabled: Boolean = false,
        val profiles: List<ProviderProfile> = emptyList(),
        val starting: Boolean = false,
        val refusal: String? = null,
        val startedRunId: String? = null,
    )

    companion object {
        const val GOAL_MAX_CHARS = 2_000

        /** The §4.2 host ceilings — the sheet may only tighten them. */
        const val HOST_MAX_TURNS = 30
        const val HOST_MAX_WALL_CLOCK_S = 600L
    }

    @Volatile var model: Model = Model()
        private set

    fun hydrate(aiEnabled: Boolean, profiles: List<ProviderProfile>) {
        model = model.copy(aiEnabled = aiEnabled, profiles = profiles)
    }

    fun setGoal(text: String) {
        model = model.copy(goal = text.take(GOAL_MAX_CHARS), refusal = null)
    }

    /** The user picks targets explicitly; the foreground app is only a pre-filled suggestion. */
    fun togglePackage(pkg: String) {
        if (pkg.isBlank()) return
        val updated = model.selectedPackages.toMutableSet()
        if (!updated.add(pkg)) updated.remove(pkg)
        model = model.copy(selectedPackages = updated, refusal = null)
    }

    /** R4: the picker's app list (the host reads launchable apps via PackageManager — the
     *  privacy rule binds the MODEL, never this user-facing list). */
    fun hydrateApps(apps: List<AppEntry>) {
        model = model.copy(availableApps = apps.sortedBy { it.label.lowercase() })
    }

    fun setAppQuery(text: String) {
        model = model.copy(appQuery = text)
    }

    /** The picker's visible list: the query filters label and package; selected apps stay visible. */
    fun visibleApps(): List<AppEntry> {
        val q = model.appQuery.trim().lowercase()
        return model.availableApps.filter { app ->
            q.isEmpty() || app.label.lowercase().contains(q) || app.pkg.lowercase().contains(q)
        }
    }

    fun selectProfile(profileId: String?) {
        model = model.copy(selectedProfileId = profileId, refusal = null)
    }

    /** Budget overrides CLAMP to the host ceilings — the sheet can only tighten (§4.2). */
    fun setMaxTurns(value: Int?) {
        model = model.copy(maxTurnsOverride = value?.coerceIn(1, HOST_MAX_TURNS))
    }

    fun setMaxWallClockS(value: Long?) {
        model = model.copy(maxWallClockSOverride = value?.coerceIn(1, HOST_MAX_WALL_CLOCK_S))
    }

    /** Everything the §12 preflight needs before the button lights up. */
    val canStart: Boolean
        get() {
            val m = model
            return m.aiEnabled && m.goal.isNotBlank() && m.selectedPackages.isNotEmpty() &&
                m.profiles.isNotEmpty() && m.selectedProfileId != null
        }

    /** The refusal reasons the sheet shows BEFORE the preflight (the preflight re-checks all). */
    fun localRefusal(): String? = when {
        !model.aiEnabled -> "AI features are off — enable them in Settings → AI features"
        model.goal.isBlank() -> "write a goal first"
        model.selectedPackages.isEmpty() -> "pick at least one target app"
        model.profiles.isEmpty() -> "no AI provider profile — add one in Settings → AI features"
        model.selectedProfileId == null -> "pick a provider"
        else -> null
    }

    suspend fun start() {
        val local = localRefusal()
        if (local != null) {
            model = model.copy(refusal = local)
            return
        }
        model = model.copy(starting = true, refusal = null)
        val budgets = kotlinx.serialization.json.buildJsonObject {
            model.maxTurnsOverride?.let { put("max_turns", it) }
            model.maxWallClockSOverride?.let { put("max_wall_clock_s", it) }
        }
        val override = if (budgets.isNotEmpty()) {
            kotlinx.serialization.json.buildJsonObject { put("budgets", budgets) }
        } else {
            kotlinx.serialization.json.JsonObject(emptyMap())
        }
        when (val result = starter.start(model.goal, model.selectedPackages.toList(), model.selectedProfileId, override)) {
            is AhResult.Ok -> model = model.copy(starting = false, startedRunId = result.value.runId)
            is AhResult.Err -> model = model.copy(
                starting = false,
                refusal = result.error.reason?.let { "$it: ${result.error.message}" } ?: result.error.message,
            )
        }
    }
}
