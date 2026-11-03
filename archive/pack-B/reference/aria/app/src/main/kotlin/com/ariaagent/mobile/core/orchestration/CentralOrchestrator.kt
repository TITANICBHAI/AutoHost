// Ported from donors/orchestration-java/CentralAIOrchestrator.java
// Original repo: TITANICBHAI/AI-ASSISTANT-INCOMPLETE
// Changes: NOT an Android Service (the spine has AgentForegroundService for
// that). Plain Kotlin coordinator class. FeedbackSystem and
// ErrorResolutionWorkflow dependencies dropped; those slots are exposed as
// optional listeners on the orchestrator. Coroutine-based periodic audit.

package com.ariaagent.mobile.core.orchestration

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The brain of the orchestration layer.
 *
 * Wires up [ComponentRegistry], [EventRouter], [DiffEngine], [HealthMonitor],
 * [OrchestrationScheduler], and [ProblemSolvingBroker], and runs a periodic
 * audit (health check + diff sweep) on its own coroutine.
 *
 * Unlike the donor (which extended `android.app.Service`), this is a plain
 * coroutine-friendly class. The Android `Service` lifecycle in the spine is
 * owned by `AgentForegroundService`; this orchestrator is a long-lived
 * *object* that the foreground service holds on to.
 *
 * Wire-up:
 * ```kotlin
 * val solver = LlamaProblemSolver(llamaEngine)
 * val orch = CentralOrchestrator(context, solver)
 * orch.initialize()
 * orch.start()
 * ```
 *
 * Dependents register themselves with `orch.registry.registerComponent(...)`
 * and emit heartbeats to `orch.healthMonitor.recordHeartbeat(componentId)`.
 */
class CentralOrchestrator(
    context: Context,
    problemSolver: ProblemSolvingBroker.ProblemSolver,
    private val periodicAuditIntervalMs: Long = 60_000L,
    private val initialAuditDelayMs: Long = 30_000L,
) {
    private val appContext: Context = context.applicationContext

    val registry: ComponentRegistry = ComponentRegistry()
    val eventRouter: EventRouter = EventRouter()
    val diffEngine: DiffEngine = DiffEngine()
    val healthMonitor: HealthMonitor = HealthMonitor(registry)
    val problemSolvingBroker: ProblemSolvingBroker = ProblemSolvingBroker(problemSolver)
    val scheduler: OrchestrationScheduler =
        OrchestrationScheduler(registry, eventRouter, healthMonitor)

    /**
     * Live ComponentInterface instances keyed by componentId. Populated via
     * [registerInstance]; consumed by [registryStageExecutor] which the
     * scheduler invokes for each pipeline stage. This is what closes the
     * "noop" gap — instead of returning canned `{"status":"noop"}`, the
     * scheduler now calls the real engine adapter.
     */
    private val componentInstances = ConcurrentHashMap<String, ComponentInterface>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateMutex = Mutex()
    private var auditJob: Job? = null

    /** Real stage executor that resolves componentId -> ComponentInterface
     *  and dispatches `execute()`. Returns null on unknown component so the
     *  scheduler records an error and trips its circuit breaker. */
    private val registryStageExecutor = OrchestrationScheduler.StageExecutor { stage, data ->
        val component = componentInstances[stage.componentId]
        if (component == null) {
            Log.w(TAG, "RegistryStageExecutor: no instance for ${stage.componentId}")
            null
        } else {
            try {
                component.execute(data)
            } catch (t: Throwable) {
                Log.e(TAG, "RegistryStageExecutor: ${stage.componentId} threw", t)
                null
            }
        }
    }

    @Volatile var isInitialized: Boolean = false; private set
    @Volatile var isRunning: Boolean = false; private set

    /** Wire dependencies and subscribe to lifecycle events. Idempotent. */
    suspend fun initialize(): Unit = stateMutex.withLock {
        if (isInitialized) {
            Log.w(TAG, "Already initialized")
            return
        }
        Log.i(TAG, "Initializing CentralOrchestrator…")

        registry.setEventRouter(eventRouter)
        diffEngine.setEventRouter(eventRouter)
        healthMonitor.setEventRouter(eventRouter)

        subscribeToEvents()

        isInitialized = true
        Log.i(TAG, "CentralOrchestrator initialized successfully")
    }

    /** Begin health monitoring, scheduler, and the periodic audit loop. */
    suspend fun start() {
        stateMutex.withLock {
            if (isRunning) {
                Log.w(TAG, "Already running")
                return
            }
            if (!isInitialized) {
                Log.w(TAG, "start() called before initialize() — initializing first")
            }
        }
        if (!isInitialized) initialize()

        stateMutex.withLock {
            // Plug the real stage executor before the scheduler starts firing
            // trigger rules so registered pipelines hit the engines, not the
            // NoopStageExecutor.
            scheduler.setStageExecutor(registryStageExecutor)
            healthMonitor.start()
            scheduler.start()

            auditJob = scope.launch {
                delay(initialAuditDelayMs)
                while (isActive) {
                    try {
                        performPeriodicAudit()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error in periodic audit", t)
                    }
                    delay(periodicAuditIntervalMs)
                }
            }
            isRunning = true
            Log.i(TAG, "CentralOrchestrator started successfully")
        }
    }

    suspend fun stop(): Unit = stateMutex.withLock {
        if (!isRunning) {
            Log.w(TAG, "Not running")
            return
        }
        Log.i(TAG, "Stopping CentralOrchestrator…")
        scheduler.stop()
        healthMonitor.stop()
        auditJob?.cancel()
        auditJob = null
        isRunning = false
        Log.i(TAG, "CentralOrchestrator stopped")
    }

    /** Tear down everything — call from app/service onDestroy. */
    fun shutdown() {
        scheduler.shutdown()
        problemSolvingBroker.shutdown()
        eventRouter.shutdown()
        scope.cancel()
        isRunning = false
        isInitialized = false
        Log.i(TAG, "CentralOrchestrator shut down")
    }

    private fun subscribeToEvents() {
        eventRouter.subscribe(OrchestrationEvent.Type.COMPONENT_ERROR) { event ->
            Log.w(TAG, "Component error detected: ${event.source}")
            handleComponentError(event)
        }
        eventRouter.subscribe(OrchestrationEvent.Type.COMPONENT_DEGRADED) { event ->
            Log.w(TAG, "Component degraded: ${event.source}")
            handleComponentDegradation(event)
        }
        eventRouter.subscribe(OrchestrationEvent.Type.STATE_DIFF_DETECTED) { event ->
            Log.i(TAG, "State diff detected on ${event.source}")
            handleStateDiff(event)
        }
        eventRouter.subscribe(OrchestrationEvent.Type.HEALTH_CHECK_FAILED) { event ->
            Log.e(TAG, "Health check failed: ${event.source}")
            healthMonitor.attemptWarmRestart(event.source)
        }
    }

    private fun handleComponentError(event: OrchestrationEvent) {
        val componentId = event.source
        val errorType = event.get("error_type") as? String ?: "COMPONENT_ERROR"
        val errorMessage = event.get("error_message") as? String ?: "Unknown error"

        // Donor used FeedbackSystem + ErrorResolutionWorkflow first; both are
        // unported. Skip directly to broker submission so the LLM tries to
        // diagnose.
        val ticket = ProblemTicket(
            componentId = componentId,
            problemType = errorType,
            description = errorMessage,
            context = event.data,
        )
        problemSolvingBroker.submitProblem(ticket)
    }

    private fun handleComponentDegradation(event: OrchestrationEvent) {
        val componentId = event.source
        healthMonitor.isolateComponent(componentId)
        scheduler.adjustScheduling(componentId, enable = false)
    }

    private fun handleStateDiff(event: OrchestrationEvent) {
        val diff = event.get("diff") as? StateDiff ?: return
        if (diff.severity == StateDiff.Severity.CRITICAL) {
            Log.e(TAG, "Critical state diff on ${diff.componentId}: ${diff.description}")
            // Future: forward to ErrorResolutionWorkflow once it lives in the spine.
        }
    }

    private fun performPeriodicAudit() {
        Log.d(TAG, "Performing periodic audit")
        healthMonitor.performHealthCheck()
        // Feed DiffEngine a fresh snapshot from every live instance before
        // it runs the diff sweep — otherwise its `latestSnapshots` map
        // contains only the seed snapshot from registerInstance() and the
        // diff is always trivially empty.
        for ((id, component) in componentInstances) {
            try {
                diffEngine.captureSnapshot(component.captureState())
            } catch (t: Throwable) {
                Log.w(TAG, "captureState() threw for $id", t)
            }
        }
        diffEngine.performPeriodicDiffCheck()
    }

    /**
     * Register a real [ComponentInterface] instance and put it on the registry.
     *
     * This is the preferred entry point: it wires the live engine adapter into
     * [componentInstances] (so [registryStageExecutor] can dispatch to it) AND
     * mirrors the registration on [ComponentRegistry] so health checks, diff
     * tracking, and capability lookup all see the same component.
     *
     * If you only need the registry entry (no executable behaviour), keep
     * using `registry.registerComponent(id, name, capabilities)` directly.
     */
    fun registerInstance(component: ComponentInterface) {
        componentInstances[component.componentId] = component
        registry.registerComponent(
            componentId = component.componentId,
            componentName = component.componentName,
            capabilities = component.capabilities,
        )
        // Mark ACTIVE + record an initial heartbeat so the first health check
        // window doesn't immediately flag the component as stale.
        registry.updateComponentStatus(component.componentId, ComponentRegistry.ComponentStatus.ACTIVE)
        healthMonitor.recordHeartbeat(component.componentId)
        // Seed the DiffEngine with the component's own initial snapshot as
        // the *expected* state. Subsequent periodic captures become the
        // *actual* state, so a real divergence (e.g. llama_engine "loaded"
        // flips to false unexpectedly) fires STATE_DIFF_DETECTED on the
        // event router.
        try {
            val snapshot = component.captureState()
            diffEngine.setExpectedState(snapshot)
            diffEngine.captureSnapshot(snapshot)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to seed DiffEngine snapshot for ${component.componentId}", t)
        }
        Log.i(TAG, "Registered live instance: ${component.componentId} (${component.capabilities})")
    }

    /** Look up the live instance for a componentId, if one was registered
     *  via [registerInstance]. Returns null for registry-only components. */
    fun getInstance(componentId: String): ComponentInterface? = componentInstances[componentId]

    /** Convenience accessor for the application context held by orchestration. */
    fun applicationContext(): Context = appContext

    companion object {
        private const val TAG = "CentralOrchestrator"

        /** Stub solver that returns a polite no-op response — used until the
         *  spine wires the real LlamaEngine adapter. */
        val NoopProblemSolver = ProblemSolvingBroker.ProblemSolver { _ ->
            "ProblemSolver not configured. Component error noted but no LLM-based diagnosis available."
        }
    }
}
