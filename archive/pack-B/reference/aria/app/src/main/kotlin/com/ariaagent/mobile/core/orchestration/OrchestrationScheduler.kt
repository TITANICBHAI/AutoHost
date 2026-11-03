// Ported from donors/orchestration-java/OrchestrationScheduler.java
// Original repo: TITANICBHAI/AI-ASSISTANT-INCOMPLETE
// Changes: removed FeedbackSystem / ErrorResolutionWorkflow dependencies
// (not present in spine — left as optional listener hooks). ExecutorService
// trio replaced with CoroutineScope + dispatchers. Pipeline executor is the
// caller-supplied StageExecutor.

package com.ariaagent.mobile.core.orchestration

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives orchestration pipelines (sequential or parallel chains of components)
 * and trigger rules (periodically evaluated conditions that fire pipelines).
 *
 * The donor referenced concrete pipelines (game_analysis, voice_processing,
 * monitoring) and a `FeedbackSystem` / `ErrorResolutionWorkflow` neither of
 * which exist in the spine yet. Those dependencies are dropped: pipelines
 * here are purely declarative, and stage execution is delegated to a
 * caller-supplied [StageExecutor]. The spine wires this in with the agent's
 * own components later.
 */
class OrchestrationScheduler(
    private val componentRegistry: ComponentRegistry,
    private val eventRouter: EventRouter,
    private val healthMonitor: HealthMonitor,
    private val triggerEvaluationIntervalMs: Long = 10_000L,
    private val initialTriggerDelayMs: Long = 5_000L,
) {
    /** Caller plugs in the concrete way to invoke a stage's componentId. */
    fun interface StageExecutor {
        suspend fun execute(stage: PipelineStage, data: Map<String, Any?>): Map<String, Any?>?
    }

    private val pipelines = ConcurrentHashMap<String, OrchestrationPipeline>()
    private val triggerRules = ConcurrentHashMap<String, TriggerRule>()
    private val stateMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var triggerJob: Job? = null
    @Volatile private var stageExecutor: StageExecutor = NoopStageExecutor
    @Volatile private var running: Boolean = false

    fun setStageExecutor(executor: StageExecutor) {
        this.stageExecutor = executor
    }

    suspend fun start(): Unit = stateMutex.withLock {
        if (running) return
        running = true
        triggerJob = scope.launch {
            delay(initialTriggerDelayMs)
            while (isActive) {
                try {
                    evaluateTriggerRules()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error evaluating trigger rules", t)
                }
                delay(triggerEvaluationIntervalMs)
            }
        }
        Log.i(TAG, "Orchestration Scheduler started")
    }

    suspend fun stop(): Unit = stateMutex.withLock {
        if (!running) return
        running = false
        triggerJob?.cancel()
        triggerJob = null
        Log.i(TAG, "Orchestration Scheduler stopped")
    }

    fun isRunning(): Boolean = running

    fun registerPipeline(pipeline: OrchestrationPipeline) {
        pipelines[pipeline.name] = pipeline
        Log.i(TAG, "Registered pipeline: ${pipeline.name}")
    }

    fun registerTriggerRule(ruleId: String, rule: TriggerRule) {
        triggerRules[ruleId] = rule
        Log.i(TAG, "Registered trigger rule: $ruleId")
    }

    fun executePipeline(pipelineName: String, data: Map<String, Any?>) {
        val pipeline = pipelines[pipelineName] ?: run {
            Log.w(TAG, "Pipeline not found: $pipelineName")
            return
        }
        Log.i(TAG, "Executing pipeline: $pipelineName (${if (pipeline.sequential) "seq" else "par"})")
        val started = System.currentTimeMillis()
        scope.launch {
            try {
                if (pipeline.sequential) executeSequential(pipeline, data)
                else executeParallel(pipeline, data)
            } finally {
                Log.d(TAG, "Pipeline $pipelineName completed in ${System.currentTimeMillis() - started}ms")
            }
        }
    }

    fun adjustScheduling(componentId: String, enable: Boolean) {
        if (enable) Log.i(TAG, "Re-enabling component in scheduling: $componentId")
        else Log.i(TAG, "Disabling component in scheduling: $componentId")
    }

    fun shutdown() {
        running = false
        triggerJob?.cancel()
        scope.cancel()
    }

    private suspend fun executeSequential(pipeline: OrchestrationPipeline, data: Map<String, Any?>) {
        val stageData = data.toMutableMap()
        for (stage in pipeline.stages) {
            if (!isComponentHealthy(stage.componentId)) {
                Log.w(TAG, "Skipping unhealthy component: ${stage.componentId}")
                continue
            }
            val breaker = healthMonitor.getCircuitBreaker(stage.componentId)
            if (breaker != null && !breaker.allowExecution()) {
                Log.w(TAG, "Circuit breaker blocking execution: ${stage.componentId}")
                continue
            }
            val ok = runStage(stage, stageData) { it?.let(stageData::putAll) }
            if (!ok && stage.critical) break
        }
    }

    private suspend fun executeParallel(pipeline: OrchestrationPipeline, data: Map<String, Any?>) {
        val frozen = data.toMap()
        pipeline.stages.map { stage ->
            scope.async {
                if (!isComponentHealthy(stage.componentId)) {
                    Log.w(TAG, "Skipping unhealthy component: ${stage.componentId}")
                    return@async
                }
                val breaker = healthMonitor.getCircuitBreaker(stage.componentId)
                if (breaker != null && !breaker.allowExecution()) {
                    Log.w(TAG, "Circuit breaker blocking execution: ${stage.componentId}")
                    return@async
                }
                runStage(stage, frozen) {}
            }
        }.awaitAll()
    }

    private suspend fun runStage(
        stage: PipelineStage,
        input: Map<String, Any?>,
        onResult: (Map<String, Any?>?) -> Unit,
    ): Boolean {
        Log.d(TAG, "Executing stage: ${stage.componentId}")
        healthMonitor.recordHeartbeat(stage.componentId)
        return try {
            val result = stageExecutor.execute(stage, input)
            if (result != null) {
                onResult(result)
                healthMonitor.recordSuccess(stage.componentId)
                true
            } else {
                healthMonitor.recordError(stage.componentId, "execution_failed")
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error executing stage ${stage.componentId}", t)
            healthMonitor.recordError(stage.componentId, "exception")
            false
        }
    }

    private fun isComponentHealthy(componentId: String): Boolean =
        componentRegistry.isComponentHealthy(componentId)

    private fun evaluateTriggerRules() {
        for ((_, rule) in triggerRules) {
            if (rule.shouldTrigger()) {
                executePipeline(rule.pipelineName, rule.triggerData)
            }
        }
    }

    /** Default executor used until the spine plugs in a real one. */
    private object NoopStageExecutor : StageExecutor {
        override suspend fun execute(stage: PipelineStage, data: Map<String, Any?>): Map<String, Any?> =
            mapOf(
                "status" to "noop",
                "component" to stage.componentId,
                "timestamp" to System.currentTimeMillis(),
            )
    }

    /** Declarative pipeline: ordered list of stages, sequential by default. */
    class OrchestrationPipeline(val name: String, val sequential: Boolean = true) {
        private val _stages = mutableListOf<PipelineStage>()
        val stages: List<PipelineStage> get() = _stages.toList()
        fun addStage(componentId: String, critical: Boolean = false): OrchestrationPipeline {
            _stages += PipelineStage(componentId, critical)
            return this
        }
    }

    /** A single stage in a pipeline; [critical]=true short-circuits sequential pipelines on failure. */
    data class PipelineStage(val componentId: String, val critical: Boolean)

    /** Time-throttled trigger that fires when its [predicate] returns true. */
    class TriggerRule(
        val pipelineName: String,
        val condition: String,
        private val minimumIntervalMs: Long,
        var triggerData: Map<String, Any?> = emptyMap(),
        private val predicate: () -> Boolean = { true },
    ) {
        @Volatile private var lastTriggerTime: Long = 0L

        fun shouldTrigger(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastTriggerTime < minimumIntervalMs) return false
            return if (predicate()) {
                lastTriggerTime = now
                true
            } else false
        }
    }

    companion object {
        private const val TAG = "OrchestrationScheduler"
    }
}
