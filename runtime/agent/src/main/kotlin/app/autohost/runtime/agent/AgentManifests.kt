package app.autohost.runtime.agent

import app.autohost.core.model.AppNeed
import app.autohost.core.model.CapabilityNeed
import app.autohost.core.model.Needs
import app.autohost.core.model.RuntimeSpec
import app.autohost.core.model.TaskManifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * P13-12 (AGENT_RUNTIME §4.1): the synthesized in-memory `TaskManifest` for an ad-hoc agent run —
 * EVERY field of `TaskManifest` is set explicitly (the orchestrator is not changed for this: it
 * issues the token from the manifest and looks the provider up by `runtime.type`). The importer
 * must refuse user ids starting `builtin.` (P13-13's validator rule) so this manifest can never
 * be impersonated by a task file.
 *
 * The idle budget rides the manifest (`idle_timeout_seconds = 180`) and the orchestrator's
 * `idleLimitMsFor` prefers the IN-MEMORY `requests[runId]` manifest over the database (X-16) —
 * so the 300 s fallback can never silently apply to a synthesized manifest.
 */
object AgentManifests {

    /** The internal id — the P13-13 importer rule refuses `builtin.` for user tasks. */
    const val ADHOC_ID = "builtin.agent.adhoc"

    /** §4.1: must exceed the confirm window (120 s) plus a model-call timeout margin. */
    const val IDLE_TIMEOUT_SECONDS = 180

    fun synthesize(packages: List<String>, maxWallClockS: Long = DEFAULT_BUDGETS.maxWallClockS): TaskManifest {
        val scope = buildJsonObject {
            put("packages", kotlinx.serialization.json.JsonArray(packages.map { JsonPrimitive(it) }))
        }
        return TaskManifest(
            schema = 1,
            id = ADHOC_ID,
            name = "Ask", // the goal text is shown separately in the UI (§4.1)
            version = "1.0.0",
            runtime = RuntimeSpec(type = "agent", entry = "agent"), // entry required, unused by the agent
            needs = Needs(
                capabilities = listOf(
                    CapabilityNeed("device.ui.observe", scope),
                    CapabilityNeed("device.ui.act", scope),
                    CapabilityNeed("device.app.query", scope),
                    CapabilityNeed("device.app.launch", scope),
                    // ui.prompt is NOT implicit — it must be listed (§4.1); `{}` per the spec table
                    CapabilityNeed("ui.prompt", buildJsonObject { }),
                ),
                optional = emptyList(),
                apps = packages.map { AppNeed(packageName = it) },
            ),
            execution = app.autohost.core.model.Execution(  // §4.1 field-by-field; see the class doc
                uiExclusive = true,           // one UI run at a time
                needsUnlockedScreen = true,
                pausable = true,              // pause is honoured between turns (§11)
                timeoutMinutes = ceilDiv(maxWallClockS, 60).toInt(), // the agent enforces it itself (ADR-098 (5))
                idleTimeoutSeconds = IDLE_TIMEOUT_SECONDS,
                maxAttempts = 1,
                minIntervalMinutes = null,
                autoResume = false,           // an interrupted agent run is NEVER silently re-run
            ),
            ui = null,
            configSchema = null,
            profileSchema = null,
            usesPacks = emptyList(),
        )
    }

    fun ceilDiv(value: Long, by: Long): Long = (value + by - 1) / by

    /** The §4.2 placeholder budgets (P13-20's numbers replace them; a run config may only TIGHTEN). */
    data class Budgets(
        val maxTurns: Int = 30,
        val maxWallClockS: Long = 600,
        val maxModelCalls: Int = 40,
        val maxTotalTokens: Long = 200_000,
        val maxOutputTokens: Int = 1_024,
        val maxActions: Int = 60,
        val maxConsecutiveFailures: Int = 3,
    ) {
        /** Maps onto the loop's budget limits (same numbers, the loop's units). */
        fun toLimits() = RunBudget.Limits(
            maxTurns = maxTurns,
            wallClockMs = maxWallClockS * 1_000,
            maxModelCalls = maxModelCalls,
            tokenBudget = maxTotalTokens,
            maxActions = maxActions,
            maxOutputTokens = maxOutputTokens,
        )

        companion object {
            val DEFAULT = Budgets()
        }
    }

    /** §4.2's placeholder host ceilings — the budget-tightening check is P13-13's preflight. */
    val DEFAULT_BUDGETS = Budgets()
}

/**
 * P13-12 (AGENT_RUNTIME §4.2): the reserved `config.agent` run-config key — host-written only.
 * The parse is tolerant of absent optional keys (defaults per §4.2) and NEVER stores a key,
 * token or secret (there are none here to parse — secrets stay in the SecretStore).
 */
data class AgentConfig(
    val goal: String,
    val packages: List<String>,
    val provider: String?,
    val model: String?,
    val supervised: Boolean,
    val budgets: AgentManifests.Budgets,
    val confirmTimeoutS: Int,
    /** The `tools` narrowing (§4.2: may only NARROW the catalog; empty = the whole catalog). */
    val tools: List<String> = emptyList(),
) {
    companion object {
        fun from(config: JsonObject): AgentConfig {
            val agent = (config["agent"] as? JsonObject)?.jsonObject
                ?: return empty()
            fun str(key: String): String? = (agent[key] as? JsonPrimitive)?.content
            val budgets = (agent["budgets"] as? JsonObject)?.jsonObject
            fun int(key: String, default: Int): Int =
                budgets?.get(key)?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: default
            fun long(key: String, default: Long): Long =
                budgets?.get(key)?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() } ?: default
            return AgentConfig(
                goal = str("goal") ?: "",
                packages = (agent["packages"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?: emptyList(),
                provider = str("provider"),
                model = str("model"),
                supervised = str("supervised")?.toBooleanStrictOrNull() ?: true,
                budgets = AgentManifests.Budgets(
                    maxTurns = int("max_turns", AgentManifests.DEFAULT_BUDGETS.maxTurns),
                    maxWallClockS = long("max_wall_clock_s", AgentManifests.DEFAULT_BUDGETS.maxWallClockS),
                    maxModelCalls = int("max_model_calls", AgentManifests.DEFAULT_BUDGETS.maxModelCalls),
                    maxTotalTokens = long("max_total_tokens", AgentManifests.DEFAULT_BUDGETS.maxTotalTokens),
                    maxOutputTokens = int("max_output_tokens", AgentManifests.DEFAULT_BUDGETS.maxOutputTokens),
                    maxActions = int("max_actions", AgentManifests.DEFAULT_BUDGETS.maxActions),
                    maxConsecutiveFailures = int("max_consecutive_failures", AgentManifests.DEFAULT_BUDGETS.maxConsecutiveFailures),
                ),
                confirmTimeoutS = str("confirm_timeout_s")?.toIntOrNull() ?: 120,
                tools = (agent["tools"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?: emptyList(),
            )
        }

        private fun empty() = AgentConfig("", emptyList(), null, null, true, AgentManifests.DEFAULT_BUDGETS, 120)
    }
}
