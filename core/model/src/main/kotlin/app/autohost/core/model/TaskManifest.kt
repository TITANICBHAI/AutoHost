package app.autohost.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** The part of task.json the host reads. Full schema: schemas/task.schema.json (validated in Phase 2). */
@Serializable
data class TaskManifest(
    val schema: Int = 1,
    val id: String,
    val name: String,
    val version: String = "0.0.0",
    val description: String = "",
    val runtime: RuntimeSpec,
    val needs: Needs = Needs(),
    @SerialName("config_schema") val configSchema: JsonObject? = null,
    @SerialName("profile_schema") val profileSchema: JsonObject? = null,
    val execution: Execution = Execution(),
    /** P7-01/02 (§8.2): which task-UI surface the detail screen hosts. Null = Tier A (auto form). */
    val ui: UiSpec? = null,
    /** P11-04 (§7.10): the app packs this task references — resolved into `config.packs` per run. */
    @SerialName("uses_packs") val usesPacks: List<String> = emptyList(),
)

/** P7 (§10.1): `ui.mode` — "auto" (Tier A form), "declarative" (ui/main.json), "html" (ui/index.html).
 *  Declarative/html modes require `file` (schemas/task.schema.json if/then). */
@Serializable
data class UiSpec(val mode: String = "auto", val file: String? = null)

@Serializable
data class RuntimeSpec(val type: String, val entry: String)

@Serializable
data class CapabilityNeed(val id: String, val scope: JsonObject? = null)

@Serializable
data class AppNeed(
    @SerialName("package") val packageName: String,
    @SerialName("min_version_code") val minVersionCode: Int? = null,
)

@Serializable
data class Needs(
    val capabilities: List<CapabilityNeed> = emptyList(),
    val optional: List<CapabilityNeed> = emptyList(),
    val apps: List<AppNeed> = emptyList(),
)

@Serializable
data class Execution(
    @SerialName("ui_exclusive") val uiExclusive: Boolean = false,
    @SerialName("needs_unlocked_screen") val needsUnlockedScreen: Boolean = false,
    val pausable: Boolean = true,
    @SerialName("timeout_minutes") val timeoutMinutes: Int = 10,
    @SerialName("idle_timeout_seconds") val idleTimeoutSeconds: Int = 300,
    @SerialName("max_attempts") val maxAttempts: Int = 1,
    /** P4-11 (§11.7): the declared bound `ctx.next_run` proposals are clamped to. Null = 1 min floor. */
    @SerialName("min_interval_minutes") val minIntervalMinutes: Int? = null,
    /** P5-05 (§12.3): auto-resume INTERRUPTED runs as a new attempt while attempts remain. */
    @SerialName("auto_resume") val autoResume: Boolean = true,
)
