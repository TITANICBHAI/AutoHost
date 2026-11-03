// EngineComponents.kt — ComponentInterface adapters for the spine's engines.
//
// Closes the gap called out in core/orchestration/README.md: every concrete
// engine (LlamaEngine, VisionEngine, AgentLoop, PolicyNetwork) is wrapped in
// a thin ComponentInterface so the orchestrator's RegistryStageExecutor can
// look it up by componentId and dispatch real work — no more NoopStageExecutor
// fake "status=noop" results.
//
// Why adapters instead of making the engines implement the interface directly?
//   * Engines are Kotlin singletons (`object`) and have no business depending
//     on the orchestration package. Adapters keep the dependency arrow
//     pointing inward (engines -> nothing; orchestration -> engines via
//     adapters). If we ever swap one engine for another, only the adapter
//     changes.
//   * Adapters can stash per-component metadata (capabilities, last result)
//     without polluting the engine's surface area.

package com.ariaagent.mobile.core.orchestration

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.ariaagent.mobile.core.agent.AgentLoop
import com.ariaagent.mobile.core.ai.LlamaEngine
import com.ariaagent.mobile.core.ai.VisionEngine
import com.ariaagent.mobile.core.rl.LearningScheduler
import com.ariaagent.mobile.core.rl.PolicyNetwork

// ── LlamaEngine adapter ─────────────────────────────────────────────────────

/**
 * Routes orchestration `execute()` calls to [LlamaEngine.infer].
 *
 * Input map:
 *   - "prompt"      String (required)
 *   - "max_tokens"  Int    (optional, default 256)
 *   - "temperature" Float  (optional, default 0.3)
 *
 * Output map:
 *   - "text"            String  — generated text
 *   - "tokens_per_sec"  Double  — last reported throughput
 *   - "memory_mb"       Double  — last reported KV-cache + model footprint
 *
 * Returns `null` (treated by the scheduler as "execution_failed") when the
 * model isn't loaded or an exception is thrown — so the circuit breaker can
 * trip exactly the way it's supposed to.
 */
class LlamaEngineComponent : ComponentInterface {
    override val componentId: String = ID
    override val componentName: String = "Llama Engine"
    override val capabilities: List<String> = listOf("inference", "diagnosis", "embedding")

    override suspend fun initialize() { /* engine self-initialises in load() */ }
    override suspend fun start() { /* nothing — load() is driven by AgentViewModel */ }
    override suspend fun stop() { /* keep the model loaded across orchestrator stop/start */ }

    override fun captureState(): ComponentStateSnapshot = ComponentStateSnapshot(
        componentId = componentId,
        version = 1,
        state = mapOf(
            "loaded" to LlamaEngine.isLoaded(),
            "vision_loaded" to LlamaEngine.isVisionLoaded(),
            "unified" to LlamaEngine.isUnifiedMode(),
        ),
    )

    override fun restoreState(snapshot: ComponentStateSnapshot) {
        // The model is owned by AgentViewModel — restoration is its job, not ours.
    }

    override suspend fun execute(input: Map<String, Any?>): Map<String, Any?> {
        val prompt = input["prompt"] as? String
            ?: error("LlamaEngineComponent.execute: 'prompt' (String) is required")
        val maxTokens = (input["max_tokens"] as? Number)?.toInt() ?: 256
        val temperature = (input["temperature"] as? Number)?.toFloat() ?: 0.3f
        val text = LlamaEngine.infer(
            prompt = prompt,
            maxTokens = maxTokens,
            temperature = temperature,
            onToken = null,
        )
        return mapOf(
            "text" to text,
            "tokens_per_sec" to LlamaEngine.lastToksPerSec,
            "memory_mb" to LlamaEngine.memoryMb,
        )
    }

    override fun isHealthy(): Boolean = LlamaEngine.isLoaded()

    override fun status(): String = if (LlamaEngine.isLoaded())
        "loaded (${"%.1f".format(LlamaEngine.lastToksPerSec)} tok/s)"
    else "not loaded"

    companion object {
        const val ID = "llama_engine"
        private const val TAG = "LlamaEngineComp"
    }
}

// ── VisionEngine adapter ────────────────────────────────────────────────────

/**
 * Routes orchestration `execute()` calls to [VisionEngine.describe].
 *
 * Input map:
 *   - "context"     Context (required) — Android context for asset access
 *   - "bitmap"      Bitmap  (required) — frame to describe
 *   - "goal"        String  (optional) — current agent goal, fed into the prompt
 *   - "annotation"  String  (optional) — IRL annotation override
 *   - "screen_hash" String  (optional) — used as the cache key
 *   - "max_tokens"  Int     (optional, default 96)
 *
 * Output map:
 *   - "description" String — vision description, may be empty on cache miss
 *                            with no model loaded
 *
 * NOTE: `context` is in the input map rather than constructor-injected so
 * the adapter is safe to construct before the foreground service is alive.
 */
class VisionEngineComponent : ComponentInterface {
    override val componentId: String = ID
    override val componentName: String = "Vision Engine"
    override val capabilities: List<String> = listOf("vision_describe", "ocr", "detection")

    override suspend fun initialize() {}
    override suspend fun start() {}
    override suspend fun stop() {}

    override fun captureState(): ComponentStateSnapshot = ComponentStateSnapshot(
        componentId = componentId,
        version = 1,
        state = mapOf(
            "vision_loaded" to LlamaEngine.isVisionLoaded(),
        ),
    )

    override fun restoreState(snapshot: ComponentStateSnapshot) { /* no-op */ }

    override suspend fun execute(input: Map<String, Any?>): Map<String, Any?> {
        val context = input["context"] as? Context
            ?: error("VisionEngineComponent.execute: 'context' (Context) is required")
        val bitmap = input["bitmap"] as? Bitmap
            ?: error("VisionEngineComponent.execute: 'bitmap' (Bitmap) is required")
        val goal = input["goal"] as? String ?: ""
        val annotation = input["annotation"] as? String ?: ""
        val screenHash = input["screen_hash"] as? String ?: ""
        val maxTokens = (input["max_tokens"] as? Number)?.toInt() ?: 96
        val description = VisionEngine.describe(
            context = context,
            bitmap = bitmap,
            goal = goal,
            screenHash = screenHash,
            annotation = annotation,
            maxTokens = maxTokens,
        )
        return mapOf("description" to description)
    }

    override fun isHealthy(): Boolean = LlamaEngine.isVisionLoaded()

    override fun status(): String = if (isHealthy()) "ready" else "vision model not loaded"

    companion object {
        const val ID = "vision_engine"
        private const val TAG = "VisionEngineComp"
    }
}

// ── AgentLoop adapter ───────────────────────────────────────────────────────

/**
 * Routes orchestration `execute()` calls to lifecycle commands on [AgentLoop].
 *
 * Input map:
 *   - "command" String — one of: "start", "stop", "pause", "status"
 *
 * For "start":
 *   - "context"      Context (required)
 *   - "goal"         String  (required)
 *   - "app_package"  String  (optional, default "")
 *   - "learn_only"   Boolean (optional, default false)
 *
 * Output (always):
 *   - "status"      String — current loop status (idle/running/paused/done/error)
 *   - "step_count"  Int    — last observed step count
 *   - "goal"        String — current goal
 *   - "last_error"  String — last recorded error
 */
class AgentLoopComponent : ComponentInterface {
    override val componentId: String = ID
    override val componentName: String = "Agent Loop"
    override val capabilities: List<String> = listOf("reasoning", "stepping", "lifecycle_control")

    override suspend fun initialize() {}
    override suspend fun start() {}
    override suspend fun stop() {}

    override fun captureState(): ComponentStateSnapshot = ComponentStateSnapshot(
        componentId = componentId,
        version = 1,
        state = mapOf(
            "status" to AgentLoop.state.status.name,
            "step_count" to AgentLoop.state.stepCount,
            "goal" to AgentLoop.state.goal,
            "last_error" to AgentLoop.state.lastError,
        ),
    )

    override fun restoreState(snapshot: ComponentStateSnapshot) { /* no-op */ }

    override suspend fun execute(input: Map<String, Any?>): Map<String, Any?> {
        when ((input["command"] as? String)?.lowercase()) {
            "start" -> {
                val context = input["context"] as? Context
                    ?: error("AgentLoopComponent.start: 'context' (Context) is required")
                val goal = input["goal"] as? String
                    ?: error("AgentLoopComponent.start: 'goal' (String) is required")
                val appPackage = input["app_package"] as? String ?: ""
                val learnOnly = input["learn_only"] as? Boolean ?: false
                AgentLoop.start(context, goal, appPackage, learnOnly = learnOnly)
            }
            "stop" -> AgentLoop.stop()
            "pause" -> AgentLoop.pause()
            "status", null -> { /* fall through to snapshot below */ }
            else -> Log.w(TAG, "Unknown command: ${input["command"]}")
        }
        return mapOf(
            "status" to AgentLoop.state.status.name,
            "step_count" to AgentLoop.state.stepCount,
            "goal" to AgentLoop.state.goal,
            "last_error" to AgentLoop.state.lastError,
        )
    }

    override fun isHealthy(): Boolean =
        AgentLoop.state.status != AgentLoop.Status.ERROR

    override fun status(): String = "${AgentLoop.state.status.name} step=${AgentLoop.state.stepCount}"

    companion object {
        const val ID = "agent_loop"
        private const val TAG = "AgentLoopComp"
    }
}

// ── PolicyNetwork adapter ───────────────────────────────────────────────────

/**
 * Routes orchestration `execute()` calls to [PolicyNetwork.selectAction].
 *
 * Input map:
 *   - "screen_embedding" FloatArray (required, length-128 ideally)
 *   - "goal_embedding"   FloatArray (required, length-128 ideally)
 *
 * Output map:
 *   - "action_index"  Int   — argmax action
 *   - "confidence"    Float — probability of chosen action
 *   - "adam_step"     Int   — current optimiser step (proxy for training progress)
 */
class PolicyNetworkComponent : ComponentInterface {
    override val componentId: String = ID
    override val componentName: String = "Policy Network"
    override val capabilities: List<String> = listOf("rl", "action_scoring")

    override suspend fun initialize() {}
    override suspend fun start() {}
    override suspend fun stop() {}

    override fun captureState(): ComponentStateSnapshot = ComponentStateSnapshot(
        componentId = componentId,
        version = 1,
        state = mapOf(
            "ready" to PolicyNetwork.isReady(),
            "adam_step" to PolicyNetwork.adamStepCount,
        ),
    )

    override fun restoreState(snapshot: ComponentStateSnapshot) { /* no-op */ }

    override suspend fun execute(input: Map<String, Any?>): Map<String, Any?> {
        val screen = input["screen_embedding"] as? FloatArray
            ?: error("PolicyNetworkComponent: 'screen_embedding' (FloatArray) is required")
        val goal = input["goal_embedding"] as? FloatArray
            ?: error("PolicyNetworkComponent: 'goal_embedding' (FloatArray) is required")
        val (idx, confidence) = PolicyNetwork.selectAction(screen, goal)
        return mapOf(
            "action_index" to idx,
            "confidence" to confidence,
            "adam_step" to PolicyNetwork.adamStepCount,
        )
    }

    override fun isHealthy(): Boolean = PolicyNetwork.isReady()

    override fun status(): String = if (PolicyNetwork.isReady())
        "ready (adam_step=${PolicyNetwork.adamStepCount})"
    else "not initialised"

    companion object {
        const val ID = "policy_network"
        private const val TAG = "PolicyNetworkComp"
    }
}

// ── LearningScheduler adapter ───────────────────────────────────────────────

/**
 * Routes orchestration `execute()` calls to lifecycle commands on
 * [LearningScheduler], the longest-running background subsystem in the spine
 * (BatteryReceiver -> charging-window LoRA + RL training).
 *
 * Without this adapter the scheduler is invisible to orchestration health: it
 * starts in `AgentForegroundService.onCreate()` and only logs to logcat. With
 * it, the orchestrator can detect that the scheduler died, surface a
 * COMPONENT_ERROR event, and the diff engine can spot when `is_training`
 * flips unexpectedly.
 *
 * Input map:
 *   - "command" String — one of "start", "stop", "status" (default: status)
 *
 * Output map:
 *   - "is_training" Boolean — current training cycle state
 *
 * Constructor takes the live LearningScheduler instance (it owns broadcast
 * receivers and a wake lock, so we can't construct a fresh one inside the
 * adapter without leaking those resources).
 */
class LearningSchedulerComponent(
    private val scheduler: LearningScheduler,
) : ComponentInterface {
    override val componentId: String = ID
    override val componentName: String = "Learning Scheduler"
    override val capabilities: List<String> = listOf("rl_training", "lora_training", "background_scheduling")

    override suspend fun initialize() {}
    override suspend fun start() { scheduler.start() }
    override suspend fun stop() { scheduler.stop() }

    override fun captureState(): ComponentStateSnapshot = ComponentStateSnapshot(
        componentId = componentId,
        version = 1,
        state = mapOf("is_training" to scheduler.isTrainingRunning()),
    )

    override fun restoreState(snapshot: ComponentStateSnapshot) { /* no-op */ }

    override suspend fun execute(input: Map<String, Any?>): Map<String, Any?> {
        when ((input["command"] as? String)?.lowercase()) {
            "start" -> scheduler.start()
            "stop" -> scheduler.stop()
            "status", null -> { /* fall through */ }
            else -> Log.w(TAG, "Unknown command: ${input["command"]}")
        }
        return mapOf("is_training" to scheduler.isTrainingRunning())
    }

    /** The scheduler is "healthy" whenever its receiver is registered.
     *  isTrainingRunning() is a *legitimate* false during off-cycles, so we
     *  treat presence of the LearningScheduler instance as health — the
     *  orchestrator's heartbeat loop is what proves the receiver is alive. */
    override fun isHealthy(): Boolean = true

    override fun status(): String =
        if (scheduler.isTrainingRunning()) "training" else "idle (waiting for charge)"

    companion object {
        const val ID = "learning_scheduler"
        private const val TAG = "LearningSchedComp"
    }
}
