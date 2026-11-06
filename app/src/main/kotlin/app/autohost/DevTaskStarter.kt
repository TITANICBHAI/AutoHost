package app.autohost

import app.autohost.core.common.AhResult
import app.autohost.core.common.Clock
import app.autohost.core.common.Ids
import app.autohost.core.engine.orchestrator.RunRecord
import app.autohost.core.engine.orchestrator.StartRequest
import app.autohost.core.model.TaskManifest
import kotlinx.serialization.json.JsonObject

/**
 * X-19 (`run_starts`, ADR-089): the facade for starting a DEV-FOLDER task from the UI. The Run
 * button in AppNavHost used to build the `StartRequest` INLINE, bypassing `AppGraph.startRun`'s
 * core steps — most importantly the P11-04 PACKS INJECTION (`config.packs`), so a dev task that
 * declared `uses_packs` ran without its packs. This class is the fix, extracted so the contract
 * is JVM-testable (`DevTaskStarterTest` pins it):
 *
 *  1. the pack injection runs on the task's config (the same `packController.injectPacks` the
 *     imported-task facade uses — one mechanism, ADR-080);
 *  2. the request is `triggerType = "manual"`, run dir under `runsRoot` with a fresh ULID;
 *  3. everything else (resource locks, the schedule preflight for scheduled fires) belongs to
 *     `AppGraph.startRun`, which imported tasks still go through.
 *
 * The [start] seam is `orchestrator::start` in production; tests script it.
 */
class DevTaskStarter(
    private val packInjector: (TaskManifest, JsonObject) -> JsonObject,
    private val runsRoot: () -> String,
    private val clock: Clock,
    private val start: (StartRequest) -> AhResult<RunRecord>,
) {

    fun start(taskId: String, taskName: String, manifest: TaskManifest, taskConfig: JsonObject, taskDir: String): AhResult<RunRecord> {
        // X-19: the packs injection is THE line the inline bypass skipped
        val config = packInjector(manifest, taskConfig)
        val request = StartRequest(
            taskId = taskId,
            taskName = taskName,
            manifest = manifest,
            config = config,
            profile = JsonObject(emptyMap()),
            profileId = null,
            taskPath = taskDir,
            runDir = java.io.File(runsRoot(), Ids.ulid(clock.nowMs())).absolutePath,
        )
        return start(request)
    }
}
