package app.autohost.agent

import app.autohost.core.common.AhResult
import app.autohost.core.common.Clock
import app.autohost.core.common.Ids
import app.autohost.core.engine.orchestrator.RunOrchestrator
import app.autohost.core.engine.orchestrator.RunRecord
import app.autohost.core.engine.orchestrator.StartRequest
import app.autohost.core.model.TaskManifest
import app.autohost.runtime.agent.AgentConfig
import app.autohost.runtime.agent.AgentManifests
import app.autohost.runtime.agent.AgentPreflight
import app.autohost.runtime.agent.AppSensitivity
import app.autohost.runtime.agent.ToolCatalog
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P13-13 (AGENT_RUNTIME §4): the agent start path — runs the §12 preflight and, on success,
 * builds the synthesized `StartRequest` for `RunOrchestrator.start` (the orchestrator is NOT
 * changed: it issues the token from the synthesized manifest and resolves the provider by
 * `runtime.type`). Every preflight failure returns a typed error and **starts no run**.
 *
 * The AI facts (`aiEnabled`/`profileReady`/`urlOk`) are wired fail-closed until the AI settings
 * exist (P14-06) — with no settings surface there is genuinely no enabled AI, so `AI_DISABLED`
 * is the honest verdict today; the device facts (a11y, screen, installed targets, another UI
 * run) come from the live :app helpers. Target installation is checked per-target (the full
 * installed-app list is never read — ADR-098 (4)'s privacy rule).
 */
class AgentRunStarter(
    private val orchestrator: RunOrchestrator,
    private val clock: Clock,
    private val runsRoot: () -> String,
    private val grantsFor: (TaskManifest, JsonObject) -> Map<String, JsonObject>,
    private val androidFacts: () -> AndroidFacts,
    private val sensitivity: AppSensitivity = AppSensitivity(),
) {

    /** The device/AI facts :app gathers (AGENT_RUNTIME §12's Android-dependent checks). */
    data class AndroidFacts(
        val aiEnabled: Boolean,
        val profileReady: Boolean,
        val urlOk: Boolean,
        val a11yConnected: Boolean,
        val screenOk: Boolean,
        val isInstalled: (String) -> Boolean,
        val uiRunActive: Boolean,
    )

    /**
     * @param agentOverride host-written extra `config.agent` keys (budgets/tools/confirm) —
     *   they override the defaults field-by-field; never a key, token or secret (§4.2).
     */
    fun start(
        goal: String,
        packages: List<String>,
        profileId: String?,
        agentOverride: JsonObject = JsonObject(emptyMap()),
    ): AhResult<RunRecord> {
        val agentObject = buildJsonObject {
            put("goal", goal)
            put("packages", JsonArray(packages.map { JsonPrimitive(it) }))
            profileId?.let { put("provider", it) }
            agentOverride.forEach { (key, value) -> put(key, value) }
        }
        val config = buildJsonObject { put("agent", agentObject) }
        val agentConfig = AgentConfig.from(config)
        val manifest = AgentManifests.synthesize(packages, agentConfig.budgets.maxWallClockS)
        val grants = grantsFor(manifest, config)
        val android = androidFacts()
        val facts = AgentPreflight.Facts(
            config = agentConfig,
            manifest = manifest,
            grants = grants,
            sensitivity = sensitivity,
            catalogTools = ToolCatalog.load().names(),
            aiEnabled = android.aiEnabled,
            profileReady = android.profileReady,
            urlOk = android.urlOk,
            a11yConnected = android.a11yConnected,
            screenOk = android.screenOk,
            installedPackages = packages.filter { android.isInstalled(it) }.toSet(),
            uiRunActive = android.uiRunActive,
        )
        when (val verdict = AgentPreflight(facts).check()) {
            is AhResult.Err -> return AhResult.err(verdict.error) // the typed §12 verdict; no run
            is AhResult.Ok -> {}
        }
        val runDir = File(runsRoot(), Ids.ulid(clock.nowMs())).absolutePath
        return orchestrator.start(
            StartRequest(
                taskId = manifest.id,
                taskName = manifest.name,
                manifest = manifest,
                config = config,
                profile = JsonObject(emptyMap()),
                profileId = profileId,
                taskPath = runDir, // unused by the agent runtime (entry is required but unused too)
                runDir = runDir,
                triggerType = "manual",
            ),
        )
    }
}
