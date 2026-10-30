package app.autohost

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.autohost.capabilities.a11y.A11yBridge
import app.autohost.capabilities.a11y.A11yUiProvider
import app.autohost.capabilities.a11y.NodeRegistry
import app.autohost.capabilities.a11y.ReadGate
import app.autohost.capabilities.a11y.ReadGatePolicy
import app.autohost.capabilities.a11y.ScreenshotA11yApi30
import app.autohost.capabilities.a11y.ServiceUiActions
import app.autohost.capabilities.a11y.SimpleA11yReader
import app.autohost.capabilities.app.ActivityLauncher
import app.autohost.capabilities.app.AndroidPackageReader
import app.autohost.capabilities.app.AppProvider
import app.autohost.capabilities.app.BackgroundKiller
import app.autohost.capabilities.ime.ImeBridge
import app.autohost.capabilities.ime.ImeProvider
import app.autohost.capabilities.projection.ProjectionScreenshotProvider
import app.autohost.capabilities.screen.AndroidSystemState
import app.autohost.capabilities.screen.ScreenProvider
import app.autohost.core.broker.BrokerBinder
import app.autohost.core.broker.BrokerService
import app.autohost.core.broker.CapabilityRegistry
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.broker.PermissionIndex
import app.autohost.core.broker.RunTokenStore
import app.autohost.core.broker.interceptor.AuditSink
import app.autohost.core.broker.interceptor.InMemoryAuditSink
import app.autohost.core.common.ApiGate
import app.autohost.core.common.AppDispatchers
import app.autohost.core.common.AhResult
import app.autohost.core.common.Clock
import app.autohost.core.common.ForegroundSource
import app.autohost.core.common.Ids
import androidx.room.Room
import app.autohost.core.data.db.AutoHostDatabase
import app.autohost.core.data.db.Migrations
import app.autohost.core.data.repo.DataGraph
import app.autohost.core.data.secrets.AndroidKeystoreAead
import app.autohost.core.data.secrets.Redactor
import app.autohost.core.data.secrets.SecretRegistry
import app.autohost.core.data.secrets.SecretStore
import app.autohost.core.devapi.DevApiPolicy
import app.autohost.core.devapi.DevApiServer
import app.autohost.core.devapi.TokenStore
import app.autohost.core.engine.health.HealthRegistry
import app.autohost.core.engine.health.InitialChecks
import app.autohost.core.engine.host.FileKvStore
import app.autohost.core.engine.host.CheckpointStore
import app.autohost.core.engine.host.NotifyProvider
import app.autohost.core.engine.tasks.BundleExtractor
import app.autohost.core.engine.tasks.CapabilityCatalog
import app.autohost.core.engine.tasks.CapabilityResolver
import app.autohost.core.engine.tasks.GrantService
import app.autohost.core.engine.tasks.ImportPipeline
import app.autohost.core.engine.tasks.TaskRemover
import app.autohost.core.engine.host.SecretsProvider
import app.autohost.core.engine.host.StateProvider
import app.autohost.core.engine.host.StoreProvider
import app.autohost.notify.AndroidNotificationSink
import app.autohost.prompt.UiTextOverlay
import app.autohost.surfaces.BubbleController
import app.autohost.surfaces.InspectorController
import app.autohost.surfaces.SurfaceRuns
import app.autohost.surfaces.SurfacesPrefs
import app.autohost.taskui.TaskUiController
import app.autohost.taskui.UiHandlerController
import app.autohost.core.engine.host.SystemProvider
import app.autohost.core.engine.orchestrator.InMemoryRunStore
import app.autohost.core.engine.orchestrator.IncidentTracker
import app.autohost.core.engine.orchestrator.ResourceLocks
import app.autohost.core.engine.orchestrator.RunRecord
import app.autohost.core.engine.orchestrator.RunOrchestrator
import app.autohost.core.engine.orchestrator.StartRequest
import app.autohost.core.engine.schedule.AlarmBackend
import app.autohost.core.engine.schedule.JobBackend
import app.autohost.core.engine.schedule.ScheduleEngine
import app.autohost.core.engine.schedule.SchedulerBackend
import app.autohost.core.engine.schedule.WorkBackend
import app.autohost.core.engine.tasks.GrantBuilder
import app.autohost.schedule.ScheduleController
import app.autohost.selfheal.SecureSettingsSelfHeal
import app.autohost.bridge.CrashReconciler
import app.autohost.bridge.RunPersistenceBridge
import app.autohost.export.AppRunExporter
import app.autohost.prompt.UiPromptProvider
import app.autohost.recorder.RecorderController
import app.autohost.runtime.api.RuntimeRegistry
import app.autohost.runtime.flow.FlowRuntimeProvider
import app.autohost.runtime.flow.FlowBinderTransport
import app.autohost.runtime.js.JsBrokerCaller
import app.autohost.runtime.js.JsRuntimeProvider
import app.autohost.runtime.python.PythonRuntimeProvider
import app.autohost.service.RunForegroundService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import app.autohost.core.common.AhJson
import app.autohost.core.common.AhLog
import app.autohost.core.model.RunStatus
import app.autohost.core.model.TaskManifest

/**
 * Manual wiring for the main process (no DI framework, AGENTS.md §4). Constructed once in `AutoHostApp`;
 * UI, services and receivers read it from `(application as AutoHostApp).graph`.
 */
class AppGraph(val application: Application) {

    /** Application context for app-module helpers that need assets/cache (e.g. New-from-template, P2-20). */
    val appContext: android.content.Context get() = application

    val clock: Clock = AndroidClock()
    val dispatchers: AppDispatchers = AppDispatchers.real()
    val appScope = CoroutineScope(
        SupervisorJob() + dispatchers.default + CoroutineExceptionHandler { _, failure ->
            // Crash-fix 1.3: one failed background job is logged and recorded; it must not kill the app.
            AhLog.e("app", "uncaught coroutine failure: ${failure.javaClass.name}: ${failure.message}")
            app.autohost.diag.CrashLog.record("uncaught coroutine failure in appScope", failure)
        },
    )

    // broker
    val tokens = RunTokenStore()

    /** P5-10: the raw audit ring buffer (Run screen audit viewer). */
    val auditMem = InMemoryAuditSink()

    /** P5-07: incidents opened by failed broker calls, resolved by later success in the same stage. */
    val incidents = IncidentTracker(clock)

    /**
     * The composite audit sink: ring buffer + incident tracker + idle-watchdog activity feed.
     * Every broker call flows through here exactly once (AuditInterceptor is the outermost link).
     */
    val auditSink: AuditSink = AuditSink { record ->
        auditMem.record(record)
        if (record.runId.isNotEmpty()) {
            incidents.onAudit(record, runStore.get(record.runId)?.stage)
            orchestrator.onActivity(record.runId)
        }
    }

    val registry = CapabilityRegistry()

    /**
     * P8-13: the insecure-feature switches (plan §14.5). The snapshot is loaded at app start and
     * refreshed on every Settings write; the broker's [InsecureFeatureInterceptor] reads the plain
     * volatile — the call hot path never touches the DB.
     */
    val insecureSwitches: app.autohost.security.InsecureSwitchStore =
        app.autohost.security.InsecureSwitchStore(application)
    private val basePermissionIndex: PermissionIndex by lazy {
        PermissionIndex.loadFromResources(AppGraph::class.java.classLoader)
    }

    /** P11-02 (ADR-079): the live index — host specs plus every attached plugin's fragment. */
    @Volatile private var permissionIndexOverride: PermissionIndex? = null
    private val permissionIndex: PermissionIndex get() = permissionIndexOverride ?: basePermissionIndex

    @Volatile private var grantBuilder = GrantBuilder(permissionIndex, devMode = true)
    val broker = BrokerService(
        registry = registry,
        tokens = tokens,
        env = DeviceEnv(sdkInt = ApiGate.sdkInt),
        clock = clock,
        auditSink = auditSink,
        interceptors = listOf(
            app.autohost.core.broker.interceptor.InsecureFeatureInterceptor { insecureSwitches.state },
        ),
    )
    val binder = BrokerBinder(broker)

    // runtimes (declared before the orchestrator that consumes them)
    val runtimes = RuntimeRegistry()
    private val pythonRuntime = PythonRuntimeProvider(application, binder)

    /** P6-06: declarative flows run in-process — no `:w*` worker (plan §7.9, Gate 6-a). */
    val flowRuntime = FlowRuntimeProvider(
        binder = object : FlowBinderTransport {
            override fun call(runToken: String, capability: String, method: String, argsJson: String) =
                binder.call(runToken, capability, method, argsJson)

            override fun emit(runToken: String, eventJson: String) {
                binder.emit(runToken, eventJson)
            }
        },
        dispatchers = dispatchers,
        scope = appScope,
        apiLevel = ApiGate.sdkInt,
        viewportWidth = application.resources.displayMetrics.widthPixels,
        viewportHeight = application.resources.displayMetrics.heightPixels,
    )

    /**
     * P11-01/02 (ADR-076): the JavaScript (QuickJS) runtime — the third runtime over the same
     * broker (Gate 11 F2/F5). In-process like flows; the engine sits behind the JsEngine seam
     * (the QuickJS binding is touched only in app/runtimejs/QuickJsEngineFactory.kt). Secrets
     * stay fail-closed (NOT_FOUND) until the SecretsProvider ships values — identical to the
     * Python and flow runtimes today. Redaction rides the shared Redactor.
     */
    val jsRuntime = JsRuntimeProvider(
        engineFactory = app.autohost.runtimejs.QuickJsEngineFactory,
        caller = object : JsBrokerCaller {
            override fun call(runToken: String, capability: String, method: String, argsJson: String): String =
                binder.call(runToken, capability, method, argsJson)

            override fun describe(runToken: String): String = binder.describe(runToken)

            override fun emit(runToken: String, eventJson: String) = binder.emit(runToken, eventJson)
        },
        dispatchers = dispatchers,
        scope = appScope,
        clock = clock,
        secretOf = { _ -> null }, // fail closed until SecretsProvider ships (same as flows/Python)
        redact = { text -> redactor.redact(text) },
        lowRam = runCatching {
            (application.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                .isLowRamDevice
        }.getOrDefault(false),
    )

    /** P6-09: the recorder controller (armed a11y tap → steps → flow.json + sidecar). */
    val recorder = RecorderController(
        clock = clock,
        scope = appScope,
        devRoot = { application.getExternalFilesDir("dev") },
    )

    /**
     * A fresh overlay per session (Views cannot be re-attached across windows). The Stop button
     * on the ● REC strip parks the session exactly like the in-app stop.
     */
    fun recorderOverlay(): app.autohost.recorder.RecorderOverlay =
        app.autohost.recorder.RecorderOverlay(
            stepCount = { recorder.state.value.stepCount },
            onStop = { recorder.stop() },
        )

    // engine
    val runStore = InMemoryRunStore()

    // P5-01: per-run directories (evidence + checkpoints) and the atomic checkpoint store.
    val runDirs = java.util.concurrent.ConcurrentHashMap<String, File>()
    val checkpoints: CheckpointStore = CheckpointStore { runId ->
        runDirs[runId] ?: File(runsRoot(), runId)
    }
    val orchestrator = RunOrchestrator(
        store = runStore,
        tokens = tokens,
        runtimes = runtimes,
        grants = grantBuilder,
        clock = clock,
        dispatchers = dispatchers,
        scope = appScope,
    )

    /** P5-07: per-run idle budget (execution.idle_timeout_seconds), manifest-cached per run. */
    private val idleBudgetCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val crashReconciler by lazy { CrashReconciler(this) }

    fun runsRoot(): File = File(application.filesDir, "runs").apply { mkdirs() }

    // ---- Phase 4: locks + scheduler + self-heal (plan §11) ----
    val resourceLocks = ResourceLocks(clock)
    val scheduleEngine = ScheduleEngine(clock)
    val selfHeal: SecureSettingsSelfHeal by lazy { SecureSettingsSelfHeal(application) }

    /** The scheduler backend preference (`auto` | `alarm` | `work` | `job`), user-settable in Settings. */
    @Volatile
    var schedulerBackendPreference: String = "auto"

    val scheduleBackends: Map<String, SchedulerBackend> by lazy {
        buildMap {
            put("alarm", AlarmBackend(application))
            put(
                "work",
                WorkBackend(application, app.autohost.service.ScheduleFireWorker::class.java, clock),
            )
            put(
                "job",
                JobBackend(application, ComponentName(application, app.autohost.service.ScheduleFireJobService::class.java), clock),
            )
        }
    }

    val scheduleController: ScheduleController by lazy {
        ScheduleController(
            app = application,
            clock = clock,
            engine = scheduleEngine,
            backends = scheduleBackends,
            scope = appScope,
            fireAction = { taskId, profileId, _dueAtMs, triggerType ->
                val runId = startRun(taskId, profileId, triggerType)
                if (runId != null) {
                    AhResult.ok(runId)
                } else {
                    AhResult.err(
                        app.autohost.core.model.ErrorCode.INVALID_ARGS,
                        "task not runnable: not installed, preflight refused, or lock timeout",
                        reason = "TASK_NOT_RUNNABLE",
                    )
                }
            },
            preferredBackend = { schedulerBackendPreference },
        )
    }

    // ---- Phase 2: data, grants/review, import pipeline, health, dev api ----
    val database: AutoHostDatabase by lazy {
        Room.databaseBuilder(application, AutoHostDatabase::class.java, AutoHostDatabase.NAME)
            .addMigrations(*Migrations.ALL)
            .build()
    }
    val secretRegistry = SecretRegistry()
    val redactor = Redactor(secretRegistry)
    val secrets: SecretStore by lazy {
        SecretStore(database.secretBlobDao(), AndroidKeystoreAead(), secretRegistry, clock)
    }
    @Volatile var grantService = GrantService(permissionIndex)
        private set
    val data: DataGraph by lazy { DataGraph(database, clock, grantService) }
    private val runPersistenceBridge by lazy {
        RunPersistenceBridge(runStore, data, appScope, checkpoints::load)
    }

    /**
     * P11-02 (ADR-079): a plugin attach merges its permission fragment into the live index and
     * rebuilds the grant machinery. Runs already in flight keep their built grant maps — the
     * next run resolves plugin needs. Known limit (recorded in the ADR): a DataGraph that was
     * already initialized keeps the previous GrantService instance (display-side only).
     */
    fun attachPluginPermissions(fragment: Map<String, List<String>>) {
        if (fragment.isEmpty()) return
        permissionIndexOverride = permissionIndex.mergedWith(fragment)
        grantBuilder = GrantBuilder(permissionIndex, devMode = true)
        grantService = GrantService(permissionIndex)
    }

    /** P11-02: discovery + binding live in the controller; the host policy stays in PluginHost. */
    val pluginController by lazy {
        app.autohost.plugins.PluginController(application, registry, clock) { attachPluginPermissions(it) }
    }

    /** P11-04 (ADR-080): `.aapack` install/store/resolve; the payload rides `config.packs`. */
    val packController: app.autohost.packs.PackController by lazy {
        app.autohost.packs.PackController(application, clock)
    }

    /** P11-08 (ADR-082): tasks + profiles backup/restore, minus secrets. */
    val backupController: app.autohost.backup.BackupController by lazy {
        app.autohost.backup.BackupController(this)
    }

    val capabilityCatalog: CapabilityCatalog by lazy {
        CapabilityCatalog.loadFromResources(AppGraph::class.java.classLoader)
    }
    val probe = AndroidDeviceProbe(application, ::availabilityOfCapability, ::switchEnabled)
    val capabilityResolver = CapabilityResolver(capabilityCatalog, probe)

    val bundleExtractor: BundleExtractor by lazy {
        BundleExtractor(File(application.cacheDir, "imports"), File(application.filesDir, "tasks"))
    }
    val importPipeline: ImportPipeline by lazy {
        // P11-04: pack awareness — missing `uses_packs` become non-blocking report warnings.
        ImportPipeline(bundleExtractor, scanner = { null }, compat = capabilityResolver, installedPackIds = { packController.installedIds() })
    }
    val taskRemover = TaskRemover(File(application.filesDir, "tasks")) { taskId ->
        AhLog.i("app", "removing task", buildJsonObject { put("task_id", taskId) })
    }

    val healthRegistry = HealthRegistry().also { InitialChecks.registerAll(it) }

    // Dev API: off until the user enables it in Settings (docs/DEVAPI.md §1); token rotates there.
    val devApiTokens = TokenStore()
    @Volatile var devApiEnabled: Boolean = false

    /**
     * P11-07 (ADR-081): the reverse transport — the phone dials the PC bridge and serves the same
     * JSON-RPC surface over it. Started from Settings → Developer → Reverse bridge.
     */
    val reverseLink = app.autohost.devapi.ReverseLinkController(
        dispatcher = { body, auth, caller -> devApiServer.dispatch(body, auth, caller) },
        tokenProvider = { devApiTokens.currentOrNull() },
        enabledProvider = { devApiEnabled && devApiPolicy.enabled },
    )

    val devApiServer: DevApiServer by lazy {
        DevApiServer(
            backend = AppDevApiBackend(this),
            policy = devApiPolicy,
            tokens = devApiTokens,
        )
    }

    /**
     * P8: the Dev API policy is app-mutable now — the Settings toggle flips [DevApiPolicy.enabled]
     * and the dev_api_remote switch flips [DevApiPolicy.allowRemote]; the server rebinds on the
     * next start/restart. Default stays honest: off, localhost-only.
     */
    val devApiPolicy = DevApiPolicy(enabled = false, allowRemote = false)

    /** P8: applies the dev_api_remote switch to the live server (called from the switch store). */
    fun refreshDevApiPolicy() {
        val state = insecureSwitches.state
        val remoteAllowed = "dev_api_remote" in state.enabled || (state.relaxed && "dev_api_remote" !in state.denied)
        if (devApiPolicy.allowRemote != remoteAllowed) {
            devApiPolicy.allowRemote = remoteAllowed
            if (devApiServer.isRunning()) devApiServer.restart()
            AhLog.i(
                "security", "dev_api_remote applied",
                buildJsonObject { put("allow_remote", remoteAllowed) },
            )
        }
    }

    fun availabilityOfCapability(capabilityId: String): app.autohost.core.model.Availability? {
        val env = DeviceEnv(sdkInt = ApiGate.sdkInt)
        val providers = registry.providers(capabilityId)
        if (providers.isEmpty()) return null
        // honest chain state: available if any provider is, else the first blocking verdict
        return providers.map { it.availability(env) }.firstOrNull { it is app.autohost.core.model.Availability.Available }
            ?: providers.first().availability(env)
    }

    /**
     * P8-13/P8-14: the CompatReport "disabled by setting" row reads this (via AndroidDeviceProbe).
     * [feature] is an InsecureFeatures id (the resolver maps capability → feature first).
     */
    fun switchEnabled(feature: String): Boolean? {
        val state = insecureSwitches.state
        return feature in state.enabled || (state.relaxed && feature !in state.denied)
    }

    fun a11yConnected(): Boolean = A11yBridge.service.value != null

    /**
     * The Dev API acts under a synthetic run token named `developer` (docs/DEVAPI.md §1). Grants are
     * dev-mode grant-all in v0 (the switches screen tightens this in a later phase); high-risk
     * capabilities stay behind the broker's own checks.
     */
    private val devApiBrokerToken: String by lazy {
        tokens.issue(
            app.autohost.core.broker.RunGrants(
                runId = "devapi",
                taskId = "devapi",
                profileId = null,
                grants = grantBuilder.build(
                    TaskManifest(id = "devapi", name = "Dev API", runtime = app.autohost.core.model.RuntimeSpec("python", "main.py")),
                    JsonObject(emptyMap()),
                    JsonObject(emptyMap()),
                ),
                issuedMs = clock.nowMs(),
            ),
        )
    }

    /** One broker call on behalf of a Dev API method. Envelope result mapped to a JSON object. */
    fun callAsDevApi(capability: String, method: String, args: JsonObject): JsonObject = kotlinx.coroutines.runBlocking {
        val envelope = broker.call(devApiBrokerToken, capability, method, args)
        if (envelope.ok) {
            (envelope.result as? JsonObject) ?: JsonObject(emptyMap())
        } else {
            buildJsonObject {
                put("__error", buildJsonObject {
                    put("code", envelope.error?.code ?: "INTERNAL")
                    put("message", envelope.error?.message ?: "call failed")
                })
            }
        }
    }

    /**
     * P2-22: run start from the Dev API / UI / scheduler — loads the latest installed version,
     * resolves config + profile values and hands the request to the orchestrator. Returns null
     * when the task is unknown or the scheduled-fire pre-flight refuses (P4-09, plan §11.5).
     *
     * P4-08/P4-09: scheduled UI-acting runs go through the preflight and acquire the global UI
     * lock (§11.4); manual/dev-api runs stay user-initiated (the user can see the device).
     */
    suspend fun startRun(
        taskId: String,
        profileId: String?,
        triggerType: String,
        triggerPayload: JsonObject = JsonObject(emptyMap()),
    ): String? {
        val version = data.tasks.latestVersion(taskId) ?: return null
        val manifest = AhJson.json.decodeFromString(TaskManifest.serializer(), version.manifestJson)
        // P11-04 (ADR-080): app packs referenced by the task ride the run config (`config.packs`)
        // — one mechanism for all three runtimes, version-pinned per run.
        val config = packController.injectPacks(manifest, data.tasks.configOf(taskId))
        val profileRow = profileId?.let { data.profiles.byId(it) }
        val profileValues = profileRow?.valuesJson?.let {
            AhJson.json.decodeFromString(JsonObject.serializer(), it)
        } ?: JsonObject(emptyMap())
        val uiActing = manifest.execution.uiExclusive ||
            manifest.needs.capabilities.any { it.id == "device.ui.act" }

        // P4-09 preflight — scheduled fires only (§11.5); outcomes: proceed/wait/WAITING_USER/SKIPPED
        if (triggerType == ScheduleController.TRIGGER_SCHEDULE) {
            val screenOn = app.autohost.capabilities.screen.AndroidSystemState.isOn()
            val unlocked = app.autohost.capabilities.screen.AndroidSystemState.isUnlocked()
            val secure = app.autohost.capabilities.screen.AndroidSystemState.isSecure()
            val facts = app.autohost.core.engine.orchestrator.Preflight.Facts(
                conditions = scheduleConditionsFor(taskId),
                conditionEnv = app.autohost.core.engine.schedule.ConditionEnv(
                    nowMs = clock.nowMs(),
                    charging = isCharging(),
                    batteryPct = batteryPercent(),
                    unmetered = isUnmetered(),
                    screenAwake = screenOn,
                    screenUnlocked = unlocked,
                ),
                needsUnlockedScreen = manifest.execution.needsUnlockedScreen,
                screenOn = screenOn,
                unlocked = unlocked,
                secureLock = secure,
                uiActing = uiActing,
                anotherUiRunActive = runStore.runs.value.any { it.active },
            )
            when (val outcome = app.autohost.core.engine.orchestrator.Preflight.evaluate(manifest, facts.conditions, facts)) {
                is app.autohost.core.engine.orchestrator.Preflight.Outcome.Proceed -> Unit
                is app.autohost.core.engine.orchestrator.Preflight.Outcome.Wait -> {
                    AhLog.w("app", "preflight wait", buildJsonObject { put("task_id", taskId); put("reason", outcome.reason) })
                    return null
                }
                is app.autohost.core.engine.orchestrator.Preflight.Outcome.NeedsUser -> {
                    AhLog.w("app", "preflight needs user", buildJsonObject { put("task_id", taskId); put("reason", outcome.reason) })
                    postNeedsUserNotification(taskId, outcome.reason)
                    return null
                }
                is app.autohost.core.engine.orchestrator.Preflight.Outcome.Skip -> {
                    AhLog.i("app", "preflight skipped", buildJsonObject { put("task_id", taskId); put("reason", outcome.reason) })
                    recordScheduleSkip(taskId, outcome.reason)
                    return null
                }
            }
        }

        // P4-08: one UI-acting run at a time for scheduled fires (§11.4). Lock timeout → skip.
        var lockHandle: app.autohost.core.engine.orchestrator.ResourceLocks.Handle? = null
        if (triggerType == ScheduleController.TRIGGER_SCHEDULE && uiActing) {
            val acquisition = resourceLocks.acquire(
                app.autohost.core.engine.orchestrator.ResourceLocks.Request(
                    runId = "sched-${clock.nowMs()}", // real run id arrives with the orchestrator's start
                    taskId = taskId,
                    profileId = profileId,
                    uiActing = true,
                    dueAtMs = clock.nowMs(),
                    lockTimeoutMs = resourceLockTimeoutMs,
                ),
            )
            when (acquisition) {
                is app.autohost.core.engine.orchestrator.ResourceLocks.Acquisition.Granted ->
                    lockHandle = acquisition.handle
                is app.autohost.core.engine.orchestrator.ResourceLocks.Acquisition.Queued -> {
                    AhLog.i("app", "ui lock busy; scheduled run queued", buildJsonObject { put("task_id", taskId) })
                    return null // bounded queue is drained by the next reconcile/fire; honest skip
                }
                app.autohost.core.engine.orchestrator.ResourceLocks.Acquisition.Timeout -> {
                    recordScheduleSkip(taskId, "LOCK_TIMEOUT")
                    return null
                }
            }
        }

        val runDir = File(runsRoot(), Ids.ulid(clock.nowMs())).absolutePath
        val result = orchestrator.start(
            StartRequest(
                taskId = taskId,
                taskName = manifest.name,
                manifest = manifest,
                config = config,
                profile = profileValues,
                profileId = profileId,
                taskPath = version.bundlePath,
                runDir = runDir,
                triggerType = triggerType,
                triggerPayload = triggerPayload,
            ),
        )
        val record = (result as? AhResult.Ok)?.value
        if (record == null) {
            lockHandle?.release()
            return null
        }
        if (lockHandle != null) heldScheduleLocks[record.runId] = lockHandle
        // P7-06: the bubble policy (auto mode) shows the control bubble for UI-acting runs.
        runUiActing[record.runId] = uiActing
        bubble.noteUiActing(record.runId, uiActing)
        return record.runId
    }

    /**
     * P7-06/P7-12: the global kill switch (plan §5.2) — notification action, bubble Stop, the
     * volume-key gesture all land here: cancels all runs (the orchestrator revokes each token and
     * kills its worker), which in turn closes prompts via the terminal-transition collector.
     */
    fun globalStop(reason: String) {
        AhLog.w("app", "global stop", buildJsonObject { put("reason", reason) })
        orchestrator.cancelAll()
    }

    /**
     * P8-03: cancels the active runs of one task — the busy_policy=replace path of the trigger
     * rules (plan §11.9). Returns how many runs were cancelled.
     */
    fun cancelRunsOfTask(taskId: String): Int {
        var count = 0
        for (record in runStore.runs.value) {
            if (record.taskId == taskId && record.active && orchestrator.cancel(record.runId)) count += 1
        }
        return count
    }

    /** UI-lock handles for scheduled runs, released on the run's terminal transition (§11.4). */
    private val heldScheduleLocks = java.util.concurrent.ConcurrentHashMap<String, app.autohost.core.engine.orchestrator.ResourceLocks.Handle>()

    @Volatile
    var resourceLockTimeoutMs: Long = app.autohost.core.engine.orchestrator.ResourceLocks.DEFAULT_LOCK_TIMEOUT_MS

    /** The schedule rule's conditions for the preflight (P4-09), or null when unscheduled. */
    private suspend fun scheduleConditionsFor(taskId: String): app.autohost.core.model.ScheduleConditions? =
        data.schedules.ofTask(taskId).firstOrNull { it.enabled }?.let { state ->
            app.autohost.core.model.ScheduleRule.fromJson(state.ruleJson)?.conditions
        }

    private suspend fun recordScheduleSkip(taskId: String, reason: String) {
        data.schedules.ofTask(taskId).firstOrNull { it.enabled }?.let {
            data.schedules.recordEvent(it.scheduleId, "FIRED", "{\"outcome\":\"SKIPPED:$reason\"}")
        }
    }

    private fun isCharging(): Boolean = runCatching {
        val bm = application.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val status = bm?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)

    private fun batteryPercent(): Int = runCatching {
        val bm = application.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        bm?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }?.let { level ->
            val scale = bm.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            (level * 100) / scale.coerceAtLeast(1)
        } ?: 100
    }.getOrDefault(100)

    private fun isUnmetered(): Boolean = runCatching {
        val cm = application.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ?: false
    }.getOrDefault(false)

    /** WAITING_USER(unlock) surfaced from a scheduled fire (plan §11.5/§11.6). */
    private fun postNeedsUserNotification(taskId: String, reason: String) {
        runCatching {
            val manager = application.getSystemService(android.app.NotificationManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    android.app.NotificationChannel("needs_user", "Needs you", android.app.NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val notification = android.app.Notification.Builder(application, "needs_user")
                .setSmallIcon(app.autohost.R.drawable.ic_autohost)
                .setContentTitle("Task needs you")
                .setContentText("Unlock the phone to run $taskId ($reason)")
                .setAutoCancel(true)
                .build()
            manager.notify(NEEDS_USER_NOTIFICATION_ID++, notification)
        }
    }

    private var NEEDS_USER_NOTIFICATION_ID = 5000

    // capability providers (wave 1 + Phase 3 hardening)
    private val a11yReader = SimpleA11yReader({ A11yBridge.service.value }, clock)
    private val a11yRegistry = NodeRegistry(clock)
    private val a11yReadGate = ReadGate(clock, ReadGatePolicy())
    private val a11yProvider = A11yUiProvider(
        reader = a11yReader,
        registry = a11yRegistry,
        actions = ServiceUiActions({ A11yBridge.service.value }),
        windowChanges = { A11yBridge.lastWindowChangeMs },
        clock = clock,
        readGate = a11yReadGate,
        imeBackend = object : app.autohost.capabilities.a11y.TextEntryBackend {
            override fun available(): Boolean = ImeBridge.isSelected()
            override fun commit(text: String, clearFirst: Boolean): String = ImeBridge.commitText(text, clearFirst, secret = false).outcome
        },
        clipboard = object : app.autohost.capabilities.a11y.ClipboardAccess {
            override fun set(text: String): Boolean = ImeBridge.clipboardSet(text)
            override fun clear(): Boolean = ImeBridge.clipboardSet("")
        },
        changeEvents = A11yBridge.windowChanges,
    )
    private val appProvider = AppProvider(
        packages = AndroidPackageReader(application),
        launcher = A11yActivityLauncher(),
        foreground = ForegroundSource.live,
        clock = clock,
        killer = BackgroundKiller { pkg ->
            val am = application.getSystemService(android.app.ActivityManager::class.java)
            try {
                am.killBackgroundProcesses(pkg)
                true
            } catch (e: Exception) {
                false
            }
        },
        stopUi = A11yAppStopUi(),
    )
    private val imeProvider = ImeProvider()
    private val screenProvider = ScreenProvider()
    private val screenshotProvider = ProjectionScreenshotProvider(
        clock = clock,
        evidenceDir = File(File(application.filesDir, "runs"), "pending"),
        a11yActive = { ApiGate.sdkInt >= 30 && A11yBridge.connected },
        a11yCapture = {
            val service = A11yBridge.service.value
            when {
                service == null || ApiGate.sdkInt < 30 -> ProjectionScreenshotProvider.A11yShot.Unavailable
                else -> when (val outcome = ScreenshotA11yApi30.take(service)) {
                    is ScreenshotA11yApi30.Outcome.Ok -> ProjectionScreenshotProvider.A11yShot.Ok(outcome.bitmap)
                    is ScreenshotA11yApi30.Outcome.Failed -> ProjectionScreenshotProvider.A11yShot.Blocked(outcome.errorCode, outcome.detail)
                }
            }
        },
    )
    private val storeProvider = StoreProvider(fileKv())
    private val stateProvider = StateProvider(checkpoints)
    val runExporter: AppRunExporter by lazy {
        AppRunExporter(
            runStore = runStore,
            auditSink = auditMem,
            incidents = incidents,
            stateProvider = stateProvider,
            redactor = redactor,
            runRoot = runsRoot(),
            manifestOf = { taskId ->
                kotlinx.coroutines.runBlocking { data.tasks.latestVersion(taskId)?.manifestJson }
            },
            environment = buildJsonObject {
                put("android_api", android.os.Build.VERSION.SDK_INT)
                put("android_release", android.os.Build.VERSION.RELEASE)
            },
        )
    }
    val uiPromptProvider = UiPromptProvider(application, clock, UiTextOverlay(clock))

    // P9-01..06: the shell tier — one ordered transport chain (plan §3.1 T3: adb-over-tcp →
    // wireless pairing (draft) → Shizuku → root), shared by the device.shell / device.ui /
    // device.screenshot shell providers. Keys persist under filesDir/adb (one RSA identity).
    private val adbKeys = app.autohost.capabilities.shell.AdbKeyFiles(
        File(application.filesDir, "adb"),
        "autohost@" + android.os.Build.MODEL.let { if (it.isNullOrBlank()) "device" else it },
    ).load()
    private val shellTransports = app.autohost.capabilities.shell.ShellTransports(
        listOf(
            app.autohost.capabilities.shell.AdbTcpTransport("127.0.0.1", 5555, adbKeys),
            app.autohost.capabilities.shell.WirelessPairingTransport(adbKeys),
            app.autohost.capabilities.shell.ShizukuTransport(app.autohost.shell.ShizukuProcessExec()),
            app.autohost.capabilities.shell.RootTransport(),
        ),
    )
    private val shellProvider = app.autohost.capabilities.shell.ShellProvider(shellTransports)
    private val shellUiProvider = app.autohost.capabilities.shell.ShellUiProvider(shellTransports)
    private val shellScreenshotProvider = app.autohost.capabilities.shell.ShellScreenshotProvider(
        shellTransports,
        File(File(application.filesDir, "runs"), "pending"),
    )

    // ---- P9 part 2: app manage, logcat/device-control, settings write, self-heal, ui.automation ----

    /** P9-11: the one settings-write door (WRITE_SECURE direct, shell `settings put` fallback). */
    private val secureSettingsHelper =
        app.autohost.shell.SecureSettingsHelper(application, shellTransports)

    /** P9-11: shell-fallback self-heal (a11y re-enable, IME auto-select, keep-awake). */
    val shellSelfHeal = app.autohost.selfheal.ShellSelfHeal(secureSettingsHelper)

    /** P9-09: the installer-UI path (a11y-driven, auto-accept gate backed). */
    private val installController = app.autohost.install.InstallController(application, clock)

    /** P9-09: `device.app.manage` — force-stop/clear/grant/install/uninstall (T3, DANGEROUS). */
    private val appManageProvider = app.autohost.capabilities.app.AppManageProvider(
        transports = shellTransports,
        installer = installController,
    )

    /** P9-10: `device.logcat` and the T3 device-control family. */
    private val logcatProvider = app.autohost.capabilities.shell.LogcatProvider(shellTransports)
    private val radioProvider = app.autohost.capabilities.shell.RadioProvider(shellTransports)
    private val displayProvider = app.autohost.capabilities.shell.DisplayProvider(shellTransports)
    private val mockLocationProvider = app.autohost.capabilities.shell.MockLocationProvider(shellTransports)

    // ---- P10 part 1: browser tier (webview host, a11y link, CDP) + vision engines (ADR-070..072) ----

    /** P10-02: the app-side browser host — page registry, visible activities, profile lock. */
    val browserController = app.autohost.browser.BrowserController(
        application,
        taskBundleDir = { taskId ->
            // The task installer extracts bundles under filesDir/tasks/<taskId>/<version>/;
            // the newest version directory is the one runs execute.
            runCatching {
                File(application.filesDir, "tasks/$taskId").listFiles()
                    ?.filter { it.isDirectory }?.maxByOrNull { it.name }
            }.getOrNull()
        },
        newPageId = { "pg-" + Ids.ulid(clock.nowMs()) },
    )

    /** P10-02: the in-app WebView browser (T0) — the default browser link. */
    private val webViewBrowserProvider = app.autohost.capabilities.browser.WebViewBrowserProvider(
        browserController,
        newPageId = { "pg-" + app.autohost.core.common.Ids.ulid(clock.nowMs()) },
    )

    /** P10-03: the a11y link into the user's real browser (T1) — ready when the service is. */
    private val a11yBrowserProvider = app.autohost.capabilities.browser.A11yBrowserProvider(
        launcher = { url ->
            runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                application.startActivity(intent)
                true
            }.getOrDefault(false)
        },
        link = app.autohost.browser.A11yBrowserLink(clock),
    )

    /** P10-04: CDP over the shell tier (T3, draft — S27 pending). */
    private val cdpBrowserProvider = app.autohost.capabilities.browser.CdpBrowserProvider(
        shellProbe = { shellTransports.firstLive() != null },
        shellExec = { command, timeoutMs ->
            val transport = shellTransports.firstLive()
            if (transport == null) {
                app.autohost.capabilities.browser.CdpBrowserProvider.ShellExecResult(null, "", "no shell transport")
            } else {
                runCatching {
                    val result = transport.exec(command, timeoutMs)
                    app.autohost.capabilities.browser.CdpBrowserProvider.ShellExecResult(result.exitCode, result.stdout, result.stderr)
                }.getOrElse { app.autohost.capabilities.browser.CdpBrowserProvider.ShellExecResult(null, "", it.message ?: "shell failed") }
            }
        },
        transportFactory = { app.autohost.browser.CdpTransportFactory() },
    )

    /** P10-05: vision — OCR (ML Kit), QR (ZXing), template match (pure NCC). */
    private val visionImages = app.autohost.browser.VisionImages(
        captureScreen = { target ->
            runCatching { app.autohost.capabilities.projection.Screenshotter.capture(target) }.getOrNull()
        },
        cacheDir = application.cacheDir,
        runDirs = { runId ->
            buildList {
                add(File(application.filesDir, "runs/$runId/evidence"))
                add(File(application.filesDir, "runs/$runId/tmp"))
                val taskId = runStore.runs.value.firstOrNull { it.runId == runId }?.taskId
                if (taskId != null) {
                    File(application.filesDir, "tasks/$taskId").listFiles()
                        ?.filter { it.isDirectory }?.maxByOrNull { it.name }?.let { add(it) }
                }
            }
        },
    )
    private val visionProvider = app.autohost.capabilities.vision.VisionProvider(
        ocr = app.autohost.capabilities.vision.MlKitOcrEngine(),
        qr = app.autohost.capabilities.vision.ZxingQrDecoder(),
        images = visionImages,
        clockNowMs = { clock.nowMs() },
    )

    // ---- P10 part 2: personal data, hardware, sms/telephony (ADR-073/074) + net riders ----

    private val contactsProvider = app.autohost.capabilities.data.ContactsProvider(
        app.autohost.data.AndroidContacts(application),
    )
    private val calendarProvider = app.autohost.capabilities.data.CalendarProvider(
        app.autohost.data.AndroidCalendar(application),
    )
    private val mediaProvider = app.autohost.capabilities.data.MediaProvider(
        app.autohost.data.AndroidMedia(application),
    )
    private val locationProvider = app.autohost.capabilities.data.LocationProvider(
        app.autohost.data.AndroidLocation(application),
    )
    private val cameraProvider = app.autohost.capabilities.data.CameraProvider(
        app.autohost.data.AndroidCamera(application, File(application.cacheDir, "camera")),
    )
    private val micProvider = app.autohost.capabilities.data.MicProvider(
        app.autohost.data.AndroidMic(application, File(application.cacheDir, "mic")),
    )
    private val sensorsProvider = app.autohost.capabilities.data.SensorsProvider(
        app.autohost.data.AndroidSensors(application),
    )
    private val smsProvider = app.autohost.capabilities.data.SmsProvider(
        app.autohost.data.AndroidSms(application), clock,
    )
    private val telephonyProvider = app.autohost.capabilities.data.TelephonyProvider(
        app.autohost.data.AndroidTelephony(application),
    )

    /** P10 W3 riders: raw TCP/UDP + the device-initiated reverse WS (net_reverse switch upstream). */
    val socketProvider = app.autohost.capabilities.net.SocketProvider(
        object : app.autohost.capabilities.net.SocketSeams.TcpFactory {
            override fun connect(host: String, port: Int, timeoutMs: Long): app.autohost.capabilities.net.SocketSeams.Connection {
                val socket = java.net.Socket()
                socket.tcpNoDelay = true
                socket.connect(java.net.InetSocketAddress(host, port), timeoutMs.toInt().coerceAtMost(30_000))
                socket.soTimeout = timeoutMs.toInt().coerceAtLeast(1)
                return app.autohost.capabilities.net.SocketSeams.Connection(
                    socket.getInputStream(), socket.getOutputStream(),
                ) { ms -> socket.soTimeout = ms.toInt().coerceAtLeast(1) }
            }
        },
    )
    private val reverseWsProvider = app.autohost.capabilities.net.ReverseWsProvider(
        app.autohost.capabilities.net.JvmWsSocketFactory(),
    )

    /** P10-06..08: lazy-permission truth refresh (call at app start + after FixHub outcomes). */
    fun refreshDataPermissionState() {
        fun granted(permission: String): Boolean = androidx.core.content.ContextCompat.checkSelfPermission(
            application, permission,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        contactsProvider.granted = granted(android.Manifest.permission.READ_CONTACTS)
        calendarProvider.granted = granted(android.Manifest.permission.READ_CALENDAR)
        mediaProvider.granted = granted(android.Manifest.permission.READ_MEDIA_IMAGES) ||
            granted(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        locationProvider.granted = granted(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        cameraProvider.granted = granted(android.Manifest.permission.CAMERA)
        micProvider.granted = granted(android.Manifest.permission.RECORD_AUDIO)
        val flavorFull = runCatching {
            application.packageManager.getPackageInfo(application.packageName, 0).versionName != null
        }.getOrDefault(false) && application.filesDir.parentFile?.name?.contains("full") == true
        // Flavor truth comes from the build config: core never declares the SMS permissions.
        val flavor = listOf(flavorFull, granted(android.Manifest.permission.SEND_SMS)).any { it }
        smsProvider.flavorFull = flavor || granted(android.Manifest.permission.SEND_SMS)
        telephonyProvider.flavorFull = flavor || granted(android.Manifest.permission.SEND_SMS) || granted(android.Manifest.permission.CALL_PHONE)
        smsProvider.granted = granted(android.Manifest.permission.READ_SMS)
        telephonyProvider.granted = granted(android.Manifest.permission.READ_PHONE_STATE)
    }


    /** P9-08: the experimental exclusive UiAutomation link (plan §5.4; UNVERIFIED-on-hardware). */
    val uiAutomationController = app.autohost.shell.UiAutomationController(
        transports = shellTransports,
        socketFactory = UiAutomationControllerSocketFactory(),
        a11yConnected = { app.autohost.capabilities.a11y.A11yBridge.service.value != null },
        experimentalEnabled = { uiAutomationToggle },
    )
    private val uiAutomationProvider = app.autohost.capabilities.shell.UiAutomationProvider(
        session = { uiAutomationController.session() },
    )

    /** P8-13-style volatile toggle for the experimental UiAutomation provider (app_settings key). */
    @Volatile
    var uiAutomationToggle: Boolean = false
        private set

    /** The experimental toggle the Settings screen writes (persisted, default off). */
    fun setUiAutomationEnabled(on: Boolean) {
        uiAutomationToggle = on
        appScope.launch {
            runCatching { data.settings.setBool(SETTING_UI_AUTOMATION, on) }
        }
        if (!on) uiAutomationController.stop("TOGGLE_OFF")
    }

    /** Loads the persisted toggle at app start (default off — experimental, plan §5.4). */
    private suspend fun loadUiAutomationToggle() {
        uiAutomationToggle = runCatching { data.settings.bool(SETTING_UI_AUTOMATION) }.getOrDefault(false)
    }

    /** P9-12: the post-reboot re-arm reminder (ADB-over-TCP resets at boot on the Android 10 phone). */
    val shellRearmReminder = app.autohost.shell.RearmReminder(application, shellTransports)

    /** P9-12: live transport rows for the Setup section — (id, state, reason?). */
    fun shellTransportStatuses(): List<Triple<String, String, String?>> =
        shellTransports.statuses().map { (transport, status) ->
            Triple(
                transport.transportId,
                when (status) {
                    is app.autohost.capabilities.shell.TransportStatus.Live -> "live"
                    is app.autohost.capabilities.shell.TransportStatus.AwaitingAuthorization -> "awaiting_authorization"
                    is app.autohost.capabilities.shell.TransportStatus.NeedsSetup -> "needs_setup"
                    is app.autohost.capabilities.shell.TransportStatus.Unsupported -> "unsupported"
                },
                when (status) {
                    is app.autohost.capabilities.shell.TransportStatus.NeedsSetup -> status.reason
                    is app.autohost.capabilities.shell.TransportStatus.Unsupported -> status.reason
                    is app.autohost.capabilities.shell.TransportStatus.AwaitingAuthorization -> "SHELL_AWAITING_AUTHORIZATION"
                    else -> null
                },
            )
        }

    /** P9-12: force a re-probe; remembers a live tier so the boot reminder only fires after real use. */
    suspend fun refreshShellTransports() {
        // Probes do bounded socket I/O — keep it off the caller's dispatcher.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            shellTransports.invalidateAll()
            if (shellTransports.firstLive() != null) {
                shellRearmReminder.rememberLive(true)
            }
        }
    }

    /** Socket seam for the UiAutomation controller (loopback-only client connections). */
    private inner class UiAutomationControllerSocketFactory :
        app.autohost.capabilities.shell.UiAutomationSessionClient.SocketFactory {
        override fun open(host: String, port: Int): app.autohost.capabilities.shell.UiAutomationSessionClient.Connection {
            val socket = javax.net.SocketFactory.getDefault().createSocket(host, port)
            return object : app.autohost.capabilities.shell.UiAutomationSessionClient.Connection {
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                override fun sendLine(line: String) {
                    writer.write(line); writer.write("\n"); writer.flush()
                }
                override fun readLine(): String? = reader.readLine()
                override fun close() = socket.close()
            }
        }
    }

    private val secretsProvider = SecretsProvider()

    // P8-02: the event backbone — the hub, the app-side controller (sources/bridges/FGS) and the
    // device.events read-side provider.
    private val rawEventHub = app.autohost.core.engine.events.EventHub(clock)
    val eventHub = app.autohost.events.EventHubController(application, rawEventHub, clock, appScope)
    private val eventsProvider = app.autohost.core.engine.host.EventsProvider { rawEventHub }

    // P8-01: the notification-listener provider (device.notifications).
    private val notificationsProvider =
        app.autohost.capabilities.notifications.NotificationsProvider(clock)

    // P8-05/P8-06: clipboard (focus-gated reads) and the allowlisted intent capability.
    private val clipboardProvider = app.autohost.capabilities.clipboard.ClipboardProvider(
        app.autohost.capabilities.clipboard.AndroidClipboard,
    )
    private val intentsProvider = app.autohost.capabilities.intents.IntentProvider()
    private val systemProvider = SystemProvider { androidSystemInfo() }

    // ---- P8 part 2: files, net, audio/power, screen control, overlays, settings, chains ----

    /** P8-07: task-private storage host provider (W1 first-build gap closed). */
    private val filesPrivateProvider = app.autohost.capabilities.files.FilesPrivateProvider(
        rootFor = { taskId -> File(File(application.filesDir, "taskdata"), taskId) },
    )

    /** P8-07: SAF trees + MediaStore (user-picked folders). */
    private val filesSharedProvider =
        app.autohost.capabilities.files.FilesSharedProvider(app.autohost.capabilities.files.AndroidSharedFiles(application))

    /** P8-08 first-build gap closed: the W1 net.http provider. */
    private val httpProvider = app.autohost.capabilities.net.HttpProvider(app.autohost.capabilities.net.JavaHttpEngine())

    /** P8-08: outbound WebSocket client + the webhook endpoint (started from Settings). */
    private val wsProvider =
        app.autohost.capabilities.net.WsClientProvider(app.autohost.capabilities.net.JvmWsSocketFactory())

    /** P8-10: run-drawn overlays ride the a11y window. */
    val overlaySink = app.autohost.surfaces.TaskOverlaySink()
    private val uiOverlayProvider = app.autohost.capabilities.screen.UiOverlayProvider(overlaySink)

    /** P8-09: audio + power (one AndroidAudio/AndroidPower each — the release path shares the maps). */
    private val androidAudio = app.autohost.capabilities.audio.AndroidAudio(application)
    private val androidPower = app.autohost.capabilities.audio.AndroidPower(application)
    private val audioProvider = app.autohost.capabilities.audio.AudioProvider(androidAudio)
    private val powerProvider = app.autohost.capabilities.audio.PowerProvider(androidPower)

    /** P8-11 reads + P9-10/P9-11 writes (the writer seam: direct WRITE_SECURE, shell fallback). */
    private val settingsProvider = app.autohost.core.engine.host.SystemSettingsProvider(
        reader = { table, key ->
            when (table) {
                "system" -> android.provider.Settings.System.getString(application.contentResolver, key)
                "secure" -> android.provider.Settings.Secure.getString(application.contentResolver, key)
                else -> android.provider.Settings.Global.getString(application.contentResolver, key)
            }
        },
        writer = { table, key, value ->
            when (val outcome = secureSettingsHelper.write(table, key, value)) {
                is app.autohost.shell.SecureSettingsHelper.Outcome.Applied ->
                    app.autohost.core.engine.host.SystemSettingsProvider.WriteOutcome(true, outcome.path)
                is app.autohost.shell.SecureSettingsHelper.Outcome.Refused ->
                    app.autohost.core.engine.host.SystemSettingsProvider.WriteOutcome(false, outcome.detail)
            }
        },
    )
    /** P8-12: the raw-bridge handshake (grant + raw_api switch enforced upstream in the pipeline). */
    private val rawBridgeProvider = app.autohost.capabilities.rawbridge.RawBridgeProvider(
        workerPid = { android.os.Process.myPid() },
        workerArmer = { runId, armed ->
            AhLog.i(
                "rawbridge", if (armed) "raw bridge armed" else "raw bridge disarmed",
                buildJsonObject { put("run_id", runId) },
            )
        },
    )

    /**
     * P8-11: the chain runner behind tasks.run — starts through [startRun] with trigger `chain`,
     * embeds the depth in the child's trigger payload, and awaits the terminal status on the run
     * store (locks apply to the child exactly like any other run).
     */
    private val chainRunner = object : app.autohost.core.engine.host.TasksRunProvider.ChainRunner {
        override fun start(taskId: String, profileId: String?, args: JsonObject?, depth: Int): String? {
            val payload = buildJsonObject {
                put("chain_depth", depth)
                put("parent_run", "tasks.run")
                args?.let { put("args", it) }
            }
            return kotlinx.coroutines.runBlocking {
                startRun(taskId, profileId, "chain", payload)
            }
        }

        override fun await(runId: String, timeoutMs: Long): app.autohost.core.engine.host.TasksRunProvider.ChainRunner.Outcome {
            val deadline = clock.nowMs() + timeoutMs
            while (clock.nowMs() < deadline) {
                val run = runStore.runs.value.firstOrNull { it.runId == runId }
                when {
                    run == null -> return app.autohost.core.engine.host.TasksRunProvider.ChainRunner.Outcome.UnknownRun
                    run.status.terminal -> return app.autohost.core.engine.host.TasksRunProvider.ChainRunner.Outcome.Terminal(
                        run.status.name,
                        (run.result?.get("result") as? JsonPrimitive)?.content,
                    )
                }
                Thread.sleep(200)
            }
            return app.autohost.core.engine.host.TasksRunProvider.ChainRunner.Outcome.Timeout
        }
    }

    private val tasksRunProvider = app.autohost.core.engine.host.TasksRunProvider(chainRunner)

    /** P8-08: the webhook endpoint → `external` trigger rules (Settings → Webhooks, S23). */
    val webhooks: app.autohost.webhook.WebhookController by lazy {
        app.autohost.webhook.WebhookController(application, appScope) { name, payload ->
            triggerController.externalFire(name, payload)
        }
    }

    /** P7-05: notify.post — task notifications with handler actions (Android sink below). */
    private val notifyProvider = NotifyProvider(AndroidNotificationSink(application))

    /** P7-01: Tier B/C task-UI state + actions; P7-03: the handler call path surfaces dispatch through. */
    val uiHandlers by lazy {
        UiHandlerController(application, data, grantBuilder, tokens, binder, clock, appScope)
    }

    // ---- Phase 7 part 2: system surfaces (plan §10.3) ----

    /** P7-06..12: prefs for every surface (bubble mode, tile bindings, external token, key filter). */
    val surfacesPrefs by lazy { SurfacesPrefs(application) }

    /** P7-06..10: the one path tiles/shortcuts/widget/intent use to start runs. */
    val surfaceRuns by lazy { SurfaceRuns(this) }

    /**
     * P8-03: the trigger-rules runtime — armed after the scheduleController (both feed on the
     * same backends); event rules ride the hub, time rules the scheduler backends.
     */
    val triggerController: app.autohost.schedule.TriggerController by lazy {
        app.autohost.schedule.TriggerController(
            app = application,
            clock = clock,
            engine = scheduleEngine,
            backends = scheduleBackends,
            scope = appScope,
            hub = rawEventHub,
            fireAction = { taskId, profileId, triggerType, payload ->
                startRun(taskId, profileId, triggerType, payload)
            },
        )
    }

    /** P7-06: floating control bubble (auto-shows for UI-acting runs, plan §10.3). */
    val bubble by lazy { BubbleController(this, surfacesPrefs, appScope) }

    /** P7-11: the on-device inspector overlay (bounds + ids, tap copies a selector). */
    val inspector by lazy { InspectorController(application, clock, appScope) }

    /** Per-run "acts on the UI" flag — the bubble policy's `auto` mode consumes it (plan §10.3). */
    private val runUiActing = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    val taskUi by lazy {
        TaskUiController(
            data = data,
            runStore = runStore,
            handlers = uiHandlers,
            startRun = { taskId, profileId, triggerType, payload ->
                startRun(taskId, profileId, triggerType, payload)
            },
            scope = appScope,
        )
    }

    // dev-folder tasks
    val devTasks = DevFolderTaskSource(application, clock)

    init {
        // Crash-fix 1.3: make this graph readable through AutoHostApp.graph BEFORE any controller starts.
        // Controllers launch background coroutines that read AutoHostApp.graph while this constructor is still
        // running; without this line they could hit UninitializedPropertyAccessException and kill the process.
        (application as? AutoHostApp)?.publishGraph(this)
        A11yBridge.clock = clock
        AndroidSystemState.init(application)
        // P9-08: the exclusive-session toggle loads async; a11y reconnects close any live session.
        appScope.launch { loadUiAutomationToggle() }
        appScope.launch {
            app.autohost.capabilities.a11y.A11yBridge.service.collect { service ->
                uiAutomationController.onA11yEvent(service != null)
            }
        }
        // P9-12: the boot re-arm reminder also covers a process start that missed BOOT_COMPLETED.
        appScope.launch { shellRearmReminder.onBoot("app_start") }
        // P8-01/P8-05/P8-06: process context for the notification bridge, clipboard and intents.
        app.autohost.capabilities.clipboard.AndroidClipboard.init(application)
        app.autohost.capabilities.intents.AndroidIntents.init(application)
        // P8-09: TTS warms up asynchronously; honesty preserved (calls before ready are refused).
        androidAudio.initTts()
        registerActivityFocusTracker()
        app.autohost.capabilities.screen.ScreenGuardService.clock = clock
        pythonRuntime.setDeathListener { runId -> orchestrator.onWorkerDeath(runId) }
        // P6-06: an in-process flow "dies" only when the interpreter coroutine crashes; the death
        // listener maps that to INTERRUPTED exactly like a lost Python worker.
        flowRuntime.setDeathListener { runId -> orchestrator.onWorkerDeath(runId) }
        // P11-01: the JS runtime dies the same way — an engine-internal crash (never a script
        // error, which is a FAILED outcome like any other runtime's EXCEPTION).
        jsRuntime.setDeathListener { runId -> orchestrator.onWorkerDeath(runId) }
        registry.register(a11yProvider)
        // P9-08: the experimental UiAutomation link sits between a11y and the shell dump link —
        // first-available keeps a11y in charge while its service is connected; the exclusive
        // session only answers when it is live (the controller closes it on a11y reconnect).
        registry.register(uiAutomationProvider)
        // P9-05: the shell device.ui link — registered AFTER a11y so the a11y service wins whenever
        // it is connected (first-available rule, CapabilityRegistry order); the shell provider only
        // answers when T1 is down.
        registry.register(shellUiProvider)
        registry.register(appProvider)
        registry.register(imeProvider)
        registry.register(screenProvider)
        registry.register(screenshotProvider)
        registry.register(storeProvider)
        registry.register(stateProvider)
        registry.register(secretsProvider)
        registry.register(systemProvider)
        // P7-04/P7-05: ui.prompt was built in P5-06 but never registered (first-build gap, closed here)
        // and notify.post ships with the P7-05 actions path.
        registry.register(uiPromptProvider)
        registry.register(notifyProvider)
        // P8-01/P8-02: the notification-listener provider and the device.events read side of the hub.
        registry.register(notificationsProvider)
        registry.register(eventsProvider)
        // P8-05/P8-06: clipboard + intents.
        registry.register(clipboardProvider)
        registry.register(intentsProvider)
        // P8 part 2: files, net, audio/power, overlays, settings reads, chains, raw bridge.
        registry.register(filesPrivateProvider)
        registry.register(filesSharedProvider)
        registry.register(httpProvider)
        registry.register(wsProvider)
        registry.register(uiOverlayProvider)
        registry.register(audioProvider)
        registry.register(powerProvider)
        registry.register(settingsProvider)
        registry.register(tasksRunProvider)
        registry.register(rawBridgeProvider)
        // P9-01..06: device.shell + the shell device.screenshot link (after the projection chain).
        registry.register(shellProvider)
        registry.register(shellScreenshotProvider)
        // P9-09..10: app manage, logcat and the T3 device-control family (all shell-backed).
        registry.register(appManageProvider)
        registry.register(logcatProvider)
        registry.register(radioProvider)
        registry.register(displayProvider)
        registry.register(mockLocationProvider)
        // P10-02..04: the browser provider chain — webview first (default, plan default #8),
        // then the a11y link (user's real browser), then CDP (T3, draft until S27).
        registry.register(webViewBrowserProvider)
        registry.register(a11yBrowserProvider)
        registry.register(cdpBrowserProvider)
        // P10-05: vision (OCR / template match / QR — hints, not proof).
        registry.register(visionProvider)
        // P10-06..08: personal data, hardware, sms/telephony (lazy-permission availability).
        registry.register(contactsProvider)
        registry.register(calendarProvider)
        registry.register(mediaProvider)
        registry.register(locationProvider)
        registry.register(cameraProvider)
        registry.register(micProvider)
        registry.register(sensorsProvider)
        registry.register(smsProvider)
        registry.register(telephonyProvider)
        // P10 W3 riders: net.socket (TCP/UDP) + net.reverse (device-initiated WS; net_reverse switch upstream).
        registry.register(socketProvider)
        registry.register(reverseWsProvider)
        // P11-02 (ADR-079): bind capability plugins — after every host registration so the
        // registry's first-available fallback order keeps host providers in front of plugins.
        runCatching { pluginController.refresh() }
            .onFailure { AhLog.w("app", "plugin refresh failed: ${it.message}") }
        refreshDataPermissionState()
        // P10-03: the a11y browser link readiness follows the accessibility service connection.
        a11yBrowserProvider.setLinkReady(app.autohost.capabilities.a11y.A11yBridge.service.value != null)

        // P8-10: the a11y wake/lock actions for device.screen control + the write-settings flag.
        screenProvider.a11yActions = object : app.autohost.capabilities.screen.A11yScreenActions {
            override fun wakeUp(): Boolean = runCatching {
                val power = application.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                if (power.isInteractive) {
                    true
                } else {
                    @Suppress("DEPRECATION")
                    power.newWakeLock(
                        android.os.PowerManager.FULL_WAKE_LOCK or
                            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            android.os.PowerManager.ON_AFTER_RELEASE,
                        "AutoHost:screen-wake",
                    ).acquire(1_000L)
                    true
                }
            }.getOrDefault(false)
            override fun lockScreen(): Boolean = runCatching {
                A11yBridge.service.value?.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
                ) == true
            }.getOrDefault(false)
        }
        screenProvider.canWriteSettings = {
            android.provider.Settings.System.canWrite(application)
        }
        runtimes.register(pythonRuntime)
        runtimes.register(flowRuntime)
        // P11-01: the third runtime — manifest `runtime.type: "javascript"` resolves here.
        runtimes.register(jsRuntime)

        // P4-11: `ctx.next_run` proposals land here (plan §11.7), clamped by the controller.
        orchestrator.onNextRun = { runId, payload ->
            scheduleController.proposeNextRun(runId, payload)
        }

        // P5-07: idle budget per run from the manifest (cached; the DB read happens once).
        orchestrator.idleLimitMs = { runId ->
            idleBudgetCache[runId] ?: runCatching {
                val manifest = runStore.get(runId)?.taskId?.let { taskId ->
                    kotlinx.coroutines.runBlocking {
                        data.tasks.latestVersion(taskId)?.manifestJson?.let {
                            AhJson.json.decodeFromString(TaskManifest.serializer(), it)
                        }
                    }
                }
                (manifest?.execution?.idleTimeoutSeconds ?: 300) * 1000L
            }.getOrNull()?.also { idleBudgetCache[runId] = it }
        }

        // The run foreground service lives exactly as long as some run is active (plan §5.2);
        // the same stream releases scheduled runs' UI locks on terminal transitions (P4-08),
        // closes open prompts (P5-06) and closes remaining incidents (P5-07).
        var previousStatuses = emptyMap<String, String>()
        appScope.launch {
            runStore.runs.collect { runs ->
                if (runs.any { it.active }) {
                    RunForegroundService.start(application)
                } else {
                    RunForegroundService.stop(application)
                }
                for (run in runs) {
                    val previous = previousStatuses[run.runId]
                    if (run.active) visionImages.activeRunId = run.runId // P10-05: vision reads this run's dirs
                    if (run.status.terminal && previous != null && previous != run.status.name) {
                        heldScheduleLocks.remove(run.runId)?.release()
                        scheduleController.onRunFinished(run.taskId, run.status.name)
                        // P8-03: task.chain rules + busy-queue drains observe the same transition.
                        triggerController.onRunFinished(run.taskId, run.status.name)
                        // P8 part 2: task-scoped power locks/keep-awake, ws handles and overlays
                        // are released with the run (never outlive it).
                        app.autohost.capabilities.audio.PowerProvider.releaseAll(androidPower, run.runId)
                        wsProvider.closeAllForRun(run.runId)
                        overlaySink.clear(run.runId)
                        uiPromptProvider.closeAllForRun(run.runId)
                        // P10-02..04: browser pages + the CDP forward die with the run.
                        browserController.closeAll(run.runId)
                        cdpBrowserProvider.closeForRun()
                        // P10 part 2: sockets, reverse handles and the SMS send budget die with it too.
                        socketProvider.closeAllForRun()
                        reverseWsProvider.closeAllForRun()
                        app.autohost.capabilities.data.SmsProvider.clearRun(run.runId)
                        incidents.closeRun(run.runId)
                        idleBudgetCache.remove(run.runId)
                        runUiActing.remove(run.runId)
                        bubble.forget(run.runId)
                    }
                    previousStatuses = previousStatuses + (run.runId to run.status.name)
                }
                // P7-09: the home widget shows last status — refresh it on every run change.
                SurfaceRuns.refreshWidgets(this@AppGraph)
            }
        }

        // P5-01/P5-18: persist runs/events/stages/evidence into Room (History, export, crash recovery).
        runPersistenceBridge.start()

        // P7-06: the bubble subscribes to runs + a11y connection; P7-12: the a11y key filter gets
        // its config and the kill-switch hook (same process — plain lambdas, no binder hop).
        bubble.start()
        A11yBridge.keyFilterProvider = { surfacesPrefs.keyFilterSpec() }
        A11yBridge.globalStopHook = { reason -> globalStop(reason) }

        // P8-02: the event backbone — a11y-fed bridges start immediately (they only deliver while
        // the service is connected); the notification bridge starts on the listener's first event.
        eventHub.startA11yBridges()
        eventHub.startNotificationBridge()
        // P8-03: the trigger-rules runtime subscribes the hub and arms its time rules.
        triggerController.start()
        // P8-08: the webhook endpoint reconciles with its settings (start/stop applies them).
        webhooks.reconcile("app_start")

        // P4-06: reconcile at app start + keep the periodic workers alive (plan §11.3).
        appScope.launch {
            kotlinx.coroutines.delay(RECONCILE_STARTUP_DELAY_MS) // let the DB settle after process start
            // P8-13: the switch snapshot must be live before any broker call can be gated.
            startupStep("insecure switches") { insecureSwitches.refresh() }
            startupStep("schedule reconcile") { scheduleController.reconcile("app_start") }
            // P8-03: time-source trigger rules re-arm on the same reconcile pass.
            startupStep("trigger reconcile") { triggerController.reconcile("app_start") }
            startupStep("reconcile worker") { app.autohost.service.ReconcileWorker.ensurePeriodic(application) }
            startupStep("retention worker") { app.autohost.service.RetentionWorker.ensurePeriodic(application) }
            startupStep("scheduler backend setting") {
                schedulerBackendPreference = data.settings.string(SETTING_SCHEDULER_BACKEND) ?: "auto"
            }
            // P5-05: orphaned runs from a crash/reboot → INTERRUPTED + bounded auto-resume (plan §12.4).
            startupStep("crash reconcile") { crashReconciler.reconcile("app_start") }
        }
    }

    /**
     * P5-05: re-opens a persisted INTERRUPTED run as a new attempt. Rebuilds the in-memory record
     * (lost with the old process), maps the run dir and hands everything to the orchestrator.
     */
    fun resumeRunFromDb(
        row: app.autohost.core.data.db.RunEntity,
        manifest: TaskManifest,
        attempt: Int,
        checkpoint: JsonObject?,
    ) {
        runStore.create(
            RunRecord(
                runId = row.runId,
                taskId = row.taskId,
                taskName = manifest.name,
                profileId = row.profileId,
                status = RunStatus.INTERRUPTED,
                attempt = row.attempt,
                triggerType = row.trigger,
                startedAtMs = row.startedAt,
                endedAtMs = row.endedAt,
            ),
        )
        runDirs[row.runId] = File(runsRoot(), row.runId)
        kotlinx.coroutines.runBlocking {
            val config = data.tasks.configOf(row.taskId)
            val profileValues = row.profileId?.let { profileId ->
                data.profiles.byId(profileId)?.valuesJson?.let {
                    AhJson.json.decodeFromString(JsonObject.serializer(), it)
                }
            } ?: JsonObject(emptyMap())
            val request = StartRequest(
                taskId = row.taskId,
                taskName = manifest.name,
                manifest = manifest,
                config = config,
                profile = profileValues,
                profileId = row.profileId,
                taskPath = data.tasks.latestVersion(row.taskId)?.bundlePath ?: return@runBlocking,
                runDir = (runDirs[row.runId] ?: File(runsRoot(), row.runId)).absolutePath,
                triggerType = row.trigger,
                attempt = attempt,
                checkpoint = checkpoint,
            )
            orchestrator.resumeAfterInterrupt(request, row.runId)
        }
    }

    /** Loads/sets the scheduler backend preference (P4-13; Settings screen writes it). */
    suspend fun setSchedulerBackendPreference(value: String) {
        schedulerBackendPreference = value
        data.settings.setString(SETTING_SCHEDULER_BACKEND, value)
        scheduleController.reconcile("backend_changed")
    }

    fun refreshDevTasks() = devTasks.refresh()

    /**
     * Crash-fix 1.3: runs one startup step so that a failure is logged and kept for the in-app crash report instead of
     * killing the process or skipping every step after it. Cancellation and out-of-memory still propagate.
     * Must stay `inline`: the wrapped calls are suspend functions called from a coroutine.
     */
    private inline fun startupStep(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (e: Throwable) {
            AhLog.e("startup", "step failed: $name: ${e.javaClass.name}: ${e.message}")
            app.autohost.diag.CrashLog.record("startup step failed: $name", e)
        }
    }

    /**
     * P8-05: maintain the process focus flag from activity lifecycle callbacks — the honest gate
     * behind `device.clipboard.get` (Android 10+ background-read rule).
     */
    private fun registerActivityFocusTracker() {
        application.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                app.autohost.capabilities.clipboard.AppFocusTracker.focused = true
            }

            override fun onActivityPaused(activity: android.app.Activity) {
                app.autohost.capabilities.clipboard.AppFocusTracker.focused = false
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
    }

    private fun fileKv(): FileKvStore = FileKvStore(File(application.filesDir, "kv/kv.json"))
    private fun androidSystemInfo(): JsonObject = buildJsonObject {
        put("sdk_int", ApiGate.sdkInt)
        put("manufacturer", android.os.Build.MANUFACTURER)
        put("model", android.os.Build.MODEL)
        put("app_version", try {
            application.packageManager.getPackageInfo(application.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        })
    }

    /**
     * Launch through the accessibility service context (background activity start needs the special context;
     * spike S2d validates the chain — plan §18 S2). The provider verifies afterwards via ForegroundTracker.
     */
    private inner class A11yActivityLauncher : ActivityLauncher {
        override fun launch(packageName: String): Boolean {
            val service = A11yBridge.service.value ?: return false
            val intent = application.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                service.startActivity(intent)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * The App-info UI stop path (P3-20): open the app-details settings page through the a11y context, then
     * drive Force stop + the confirm dialog with real node actions. Honest best effort — the caller verifies
     * via the foreground tracker.
     */
    private inner class A11yAppStopUi : app.autohost.capabilities.app.AppStopUi {
        override suspend fun forceStopViaUi(packageName: String): Boolean {
            val service = A11yBridge.service.value ?: return false
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                service.startActivity(intent)
            } catch (e: Exception) {
                return false
            }
            // Wait for the settings window, then click Force stop and confirm (label/id matching like the
            // AutoAcceptGate; verified on the phone in Gate 3 / G9-a).
            return A11yBridge.awaitConnected(2_000) && clickLabel("force stop")
        }

        private fun clickLabel(label: String): Boolean {
            val reader = SimpleA11yReader({ A11yBridge.service.value }, clock)
            val snap = reader.read(null)
            val target = snap.nodes.firstOrNull { n -> n.text.contains(label, ignoreCase = true) && n.has("clickable") }
                ?: return false
            val info = snap.infoAt(target.index) ?: return false
            return try {
                info.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            } catch (e: RuntimeException) {
                false
            }
        }
    }

    companion object {
        /** Deep link for the Setup screen → system accessibility settings. */
        fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

        /** P4-13: persisted scheduler backend preference (`auto`|`alarm`|`work`|`job`). */
        const val SETTING_SCHEDULER_BACKEND = "scheduler.backend"

        /** P9-08: the experimental exclusive UiAutomation provider toggle (default off). */
        const val SETTING_UI_AUTOMATION = "ui.automation.experimental"

        /** Give the DB and providers a beat before the first reconcile pass after process start. */
        const val RECONCILE_STARTUP_DELAY_MS = 4_000L
    }
}

/** Android clock: wall time from the system, monotonic time from uptime (AGENTS.md §4 Clock rule). */
class AndroidClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun monotonicMs(): Long = android.os.SystemClock.elapsedRealtime()
}
