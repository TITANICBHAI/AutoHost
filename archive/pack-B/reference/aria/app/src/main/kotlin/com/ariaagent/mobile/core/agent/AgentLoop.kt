package com.ariaagent.mobile.core.agent

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.ariaagent.mobile.core.ai.LlamaEngine
import com.ariaagent.mobile.core.ai.PromptBuilder
import com.ariaagent.mobile.core.ai.Sam2Engine
import com.ariaagent.mobile.core.ai.VisionEngine
import com.ariaagent.mobile.core.events.AgentEventBus
import com.ariaagent.mobile.core.memory.EmbeddingEngine
import com.ariaagent.mobile.core.memory.ExperienceStore
import com.ariaagent.mobile.core.memory.ObjectLabelStore
import com.ariaagent.mobile.core.memory.SessionReplayStore
import com.ariaagent.mobile.core.memory.VisionEmbeddingStore
import com.ariaagent.mobile.core.patterns.SuggestionStore
import com.ariaagent.mobile.core.patterns.UsagePatternTracker
import com.ariaagent.mobile.core.monitoring.MonitoringPusher
import com.ariaagent.mobile.core.perception.ObjectDetectorEngine
import com.ariaagent.mobile.core.perception.ScreenObserver
import com.ariaagent.mobile.core.persistence.ProgressPersistence
import com.ariaagent.mobile.core.system.PixelVerifier
import com.ariaagent.mobile.core.system.SustainedPerformanceManager
import com.ariaagent.mobile.core.system.ThermalGuard
import com.ariaagent.mobile.system.actions.GestureEngine
import com.ariaagent.mobile.system.accessibility.AgentAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * AgentLoop — The central Observe → Reason → Act engine.
 *
 * This is the core of ARIA. It runs a continuous loop:
 *   1. OBSERVE   — capture screen (accessibility tree + OCR + bitmap)
 *   2. RETRIEVE  — find relevant past experiences via embedding similarity
 *   3. REASON    — call LlamaEngine.infer() with full prompt
 *   4. PARSE     — extract JSON action from LLM output
 *   5. ACT       — dispatch gesture via GestureEngine
 *   6. EVALUATE  — wait for screen to settle, assign reward
 *   7. STORE     — persist ExperienceTuple to SQLite
 *   8. EMIT      — push status event to AgentEventBus (→ AgentViewModel → Compose UI)
 *
 * Loop stops when:
 *   - LLM returns {"tool":"Done"}
 *   - stepCount >= maxSteps (safety cap: prevents infinite loops)
 *   - stop() is called
 *   - An unrecoverable exception is thrown
 *
 * Events emitted to AgentEventBus (consumed by AgentViewModel → Compose screens):
 *   agent_status_changed  { status, currentTask, currentApp, stepCount }
 *   action_performed      { tool, nodeId, success, reward, stepCount }
 *   token_generated       { token, tokensPerSecond }
 *
 * Phase: 3 (Action Layer) — depends on Phase 1 (LLM) + Phase 2 (Perception).
 */
object AgentLoop {

    enum class Status { IDLE, RUNNING, PAUSED, DONE, ERROR }

    data class LoopState(
        val status: Status = Status.IDLE,
        val goal: String = "",
        val appPackage: String = "",
        val stepCount: Int = 0,
        val lastAction: String = "",
        val lastError: String = "",
        val gameMode: String = "none"   // "none" | "arcade" | "puzzle" | "strategy"
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var loopJob: Job? = null

    @Volatile
    var state = LoopState()
        private set

    // Event callback — wired by AgentViewModel to forward events into AgentEventBus
    var onEvent: ((name: String, data: Map<String, Any>) -> Unit)? = null

    // ── Floating Chat live instruction injection ───────────────────────────────
    // Set by AgentEventBus subscriber when the user sends a chat message or draws
    // a gesture on the floating overlay. Consumed once per step in REASON phase.
    @Volatile private var pendingUserInstruction: String?      = null
    @Volatile private var pendingGestureAnnotation: String?    = null
    @Volatile private var currentSessionId: String             = ""
    @Volatile private var currentActionTools: List<String>     = emptyList()

    private const val MAX_STEPS = 200             // total steps across all sub-tasks in one run
    private const val MAX_STEPS_PER_SUBTASK = 30  // per-sub-task ceiling — prevents one task eating all budget
    private const val A11Y_RETRY_COUNT = 3        // retry attempts before declaring service dead
    private const val A11Y_RETRY_DELAY_MS = 2_000L
    // FLAWS.md #5 — adaptive backoff for the in-loop "(not ready)" sentinel.
    // Starts at 250 ms so the first transient settles fast (most a11y-unready
    // states clear within one window animation frame), then doubles up to a
    // 2-second cap so we don't hammer ScreenObserver if the service is wedged.
    private const val A11Y_SENTINEL_BACKOFF_INITIAL_MS = 250L
    private const val A11Y_SENTINEL_BACKOFF_MAX_MS     = 2_000L
    private const val STEP_DELAY_MS = 800L
    private const val SCREEN_SETTLE_MS = 600L
    private const val WAIT_RETRY_DELAY_MS = 1200L

    /**
     * Start the agent loop for a given goal.
     * If already running, stops the current run and starts fresh.
     *
     * Phase 14 additions (all integrated here):
     *   14.1 PixelVerifier    — fast action-result verification via pixel diff on the acted node
     *   14.3 SustainedPerf   — sustained performance mode enabled for stable inference throughput
     *   14.4 ProgressPersist — progress.txt + goals.json synced at start and after each step
     *
     * @param context      Android context (needed for ExperienceStore, EmbeddingEngine)
     * @param goal         Natural-language task description from the user
     * @param appPackage   Target app package (e.g. "com.android.settings")
     * @param learnOnly    When true, skip all gesture dispatch (GestureEngine + GameLoop).
     *                     The loop still observes screens, reasons via LLM, and stores
     *                     experience tuples — generating training data without touching the device.
     *                     Reward = 0.6 (neutral-positive) since outcomes cannot be verified.
     *                     LlmRewardEnricher re-scores these during the next training cycle.
     */
    fun start(context: Context, goal: String, appPackage: String, learnOnly: Boolean = false) {
        loopJob?.cancel()
        state = LoopState(status = Status.RUNNING, goal = goal, appPackage = appPackage)
        emitStatus()

        // ── Phase 14.3: Enable Sustained Performance Mode ─────────────────────
        // Stabilises Exynos 9611 clocks during LLM inference — prevents the
        // ramp-overheat-throttle cycle that degrades tok/s mid-reasoning-turn.
        SustainedPerformanceManager.enable()

        // ── Phase 16: Start monitoring pusher ──────────────────────────────────
        MonitoringPusher.start(context)

        // ── Floating Chat: subscribe to live user instructions ─────────────────
        // Runs as a parallel side-coroutine — sets shared volatiles that the
        // main loop reads at the top of each REASON step then clears.
        pendingUserInstruction   = null
        pendingGestureAnnotation = null
        scope.launch {
            AgentEventBus.flow.collect { (name, data) ->
                when (name) {
                    "user_instruction"       -> pendingUserInstruction   = data["text"] as? String
                    "user_gesture_annotation"-> pendingGestureAnnotation = data["annotation"] as? String
                }
            }
        }

        loopJob = scope.launch {
            val store         = ExperienceStore.getInstance(context)
            val labelStore    = ObjectLabelStore.getInstance(context)
            val replayStore   = SessionReplayStore.getInstance(context)
            val visionEmbStore= VisionEmbeddingStore.getInstance(context)
            val patternTracker= UsagePatternTracker.getInstance(context)
            val suggStore     = SuggestionStore.getInstance(context)

            val sessionId     = java.util.UUID.randomUUID().toString()
            currentSessionId  = sessionId
            currentActionTools= emptyList()
            replayStore.startSession(sessionId, goal)

            val actionHistory    = mutableListOf<String>()
            val actionToolHistory= mutableListOf<String>()  // for UsagePatternTracker
            var previousSnapshot: ScreenObserver.ScreenSnapshot? = null
            // FLAWS.md #5 — per-run adaptive backoff for the a11y "(not ready)"
            // sentinel. Reset to the initial value below whenever a real tree
            // is observed, doubled (capped) on every consecutive sentinel hit.
            var a11ySentinelBackoffMs = A11Y_SENTINEL_BACKOFF_INITIAL_MS

            // Phase 15: track element names acted on for AppSkillRegistry frequency counting
            val elementsTouched = mutableListOf<String>()
            var lastAppPackage = appPackage

            // ── Phase 18: Stuck detection ──────────────────────────────────────
            // Count consecutive steps where the screen hash did not change AND the
            // action was neither Wait nor Back (the agent is spinning wheels).
            // Thresholds: 3 → inject hint | 5 → force Back | 8 → abort task.
            // sameHashCount: debounce — require 2 identical consecutive hashes before
            // incrementing stuckCount, reducing false positives from animated content.
            var stuckCount      = 0
            var sameHashCount   = 0
            var lastScreenHash  = ""
            var stuckHint       = ""

            // ── Task chaining step budget ──────────────────────────────────────
            // stepsInCurrentSubTask is reset to 0 each time we advance to a new
            // sub-task. This prevents any single sub-task from consuming the entire
            // MAX_STEPS budget and starving later sub-tasks in a chained plan.
            var stepsInCurrentSubTask = 0

            // ── Phase 14.4: Log task start + inject previous progress into context ─
            ProgressPersistence.logTaskStart(context, goal)

            // Read last N lines of progress.txt — inject as first history entry so
            // Llama 3.2 "syncs" its context and avoids repeating known-failed actions.
            val previousContext = ProgressPersistence.readContext(context)
            if (previousContext.isNotEmpty()) {
                actionHistory.add(
                    """{"tool":"ContextSync","reason":"Previous session log:\n$previousContext"}"""
                )
            }

            // ── Phase 19: Task plan decomposition ─────────────────────────────
            // LLM pre-pass breaks the goal into 2-7 ordered sub-tasks before the
            // main loop starts. This gives the agent a structured plan to follow
            // rather than discovering steps through pure trial-and-error.
            //
            // Resume logic: if goals.json already holds THIS goal with some steps
            // still pending, we resume from the first unchecked step (crash-safe).
            // If the goal changed or all steps are done, we decompose fresh.
            val existingGoalState = ProgressPersistence.readGoalState(context)
            val subTasksRaw: List<String>
            var subTaskIdx: Int
            if (
                existingGoalState != null &&
                existingGoalState.goal == goal &&
                existingGoalState.subTasks.any { !it.passed }
            ) {
                // Resume a partially-completed run for this same goal
                subTasksRaw = existingGoalState.subTasks.map { it.label }
                subTaskIdx  = existingGoalState.subTasks.indexOfFirst { !it.passed }.coerceAtLeast(0)
                Log.i("AgentLoop", "Resuming plan at step ${subTaskIdx + 1}/${subTasksRaw.size}: \"${subTasksRaw[subTaskIdx]}\"")
                ProgressPersistence.logNote(context, "Resuming at step ${subTaskIdx + 1}: ${subTasksRaw[subTaskIdx]}")
            } else {
                // Fresh task — decompose via LLM and persist plan to goals.json
                subTasksRaw = TaskDecomposer.decompose(goal)
                ProgressPersistence.initGoals(context, goal, subTasksRaw)
                subTaskIdx  = 0
                if (subTasksRaw.size > 1) {
                    Log.i("AgentLoop", "Task plan (${subTasksRaw.size} steps): " +
                          subTasksRaw.joinToString(" → ") { "\"$it\"" })
                    ProgressPersistence.logNote(context,
                        "Plan: " + subTasksRaw.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString(" | "))
                }
            }
            var currentSubGoal = subTasksRaw.getOrElse(subTaskIdx) { goal }

            try {
                while (isActive && state.stepCount < MAX_STEPS) {

                    // ── 0. PER-SUB-TASK STEP BUDGET CHECK ────────────────────
                    // If the current sub-task has consumed MAX_STEPS_PER_SUBTASK steps
                    // without completing, advance to the next sub-task rather than blocking
                    // the entire chained plan. This prevents one slow/stuck sub-task from
                    // eating the full session budget and starving the remaining sub-tasks.
                    if (stepsInCurrentSubTask >= MAX_STEPS_PER_SUBTASK) {
                        val nextSubGoal = subTasksRaw.getOrNull(subTaskIdx + 1)
                        if (nextSubGoal != null) {
                            Log.w("AgentLoop",
                                "Sub-task ${subTaskIdx + 1} hit step limit ($MAX_STEPS_PER_SUBTASK) — advancing to sub-task ${subTaskIdx + 2}")
                            ProgressPersistence.logNote(context,
                                "Step limit reached for sub-task ${subTaskIdx + 1} — skipping to ${subTaskIdx + 2}/${subTasksRaw.size}")
                            subTaskIdx++
                            currentSubGoal        = nextSubGoal
                            stepsInCurrentSubTask = 0
                            stuckCount            = 0
                            stuckHint             = ""
                            lastScreenHash        = ""
                            delay(STEP_DELAY_MS)
                            continue
                        } else {
                            // No more sub-tasks — record partial failure and end
                            Log.w("AgentLoop", "Last sub-task hit step limit — ending task")
                            state = state.copy(status = Status.DONE, lastError = "subtask_step_limit")
                            ProgressPersistence.logNote(context, "Last sub-task hit $MAX_STEPS_PER_SUBTASK step limit")
                            ProgressPersistence.logTaskEnd(context, goal, succeeded = false)
                            SustainedPerformanceManager.disable()
                            emitStatus()
                            recordAndChain(context, goal, lastAppPackage, succeeded = false,
                                stepsTaken = state.stepCount, elementsTouched = elementsTouched)
                            break
                        }
                    }

                    // ── 0b. STEP STARTED ──────────────────────────────────────
                    // Push to AgentEventBus → Compose ViewModel before any work begins.
                    // Lets the UI show "OBSERVE" spinner immediately.
                    run {
                        val stepData = mapOf(
                            "stepNumber" to state.stepCount,
                            "activity"   to "observe"
                        )
                        onEvent?.invoke("step_started", stepData)
                        AgentEventBus.emit("step_started", stepData)
                    }

                    // ── 0b. ACCESSIBILITY SERVICE GUARD ──────────────────────
                    // On low-RAM devices (M31: 6 GB) the OS can temporarily suspend the
                    // Accessibility Service. A single null frame must not abort the task —
                    // retry A11Y_RETRY_COUNT times with A11Y_RETRY_DELAY_MS between checks.
                    // Only if the service is still absent after all retries do we abort.
                    // Without this guard, getSemanticTree() returns the sentinel string
                    // "(accessibility service not active)" which looks non-empty and causes
                    // the LLM to hallucinate actions over garbage until MAX_STEPS runs out.
                    if (!AgentAccessibilityService.isActive) {
                        var a11yRetries = 0
                        while (a11yRetries < A11Y_RETRY_COUNT && !AgentAccessibilityService.isActive) {
                            Log.w("AgentLoop",
                                "A11y service not active — waiting ${A11Y_RETRY_DELAY_MS}ms (retry ${a11yRetries + 1}/$A11Y_RETRY_COUNT)")
                            delay(A11Y_RETRY_DELAY_MS)
                            a11yRetries++
                        }
                        if (!AgentAccessibilityService.isActive) {
                            Log.e("AgentLoop",
                                "Accessibility Service still dead after $A11Y_RETRY_COUNT retries — aborting")
                            state = state.copy(status = Status.DONE, lastError = "accessibility_service_dead")
                            ProgressPersistence.logNote(context, "Aborted: accessibility service not active after retries")
                            ProgressPersistence.logTaskEnd(context, goal, succeeded = false)
                            SustainedPerformanceManager.disable()
                            emitStatus()
                            recordAndChain(context, goal, lastAppPackage, succeeded = false,
                                stepsTaken = state.stepCount, elementsTouched = elementsTouched)
                            break
                        }
                        Log.i("AgentLoop", "A11y service recovered after $a11yRetries retry/retries — continuing")
                    }

                    // ── 1. OBSERVE ────────────────────────────────────────────
                    val snapshot = ScreenObserver.capture()

                    // Allow vision-only steps: if the a11y tree and OCR are both
                    // empty (game, Flutter, Unity) but a bitmap exists AND vision
                    // is loaded, let the step proceed — vision is the only signal.
                    val visionAvailableForEmptyScreen =
                        snapshot.bitmap != null && VisionEngine.isVisionModelReady(context)
                    if (snapshot.isEmpty() && !visionAvailableForEmptyScreen) {
                        delay(WAIT_RETRY_DELAY_MS)
                        continue
                    }

                    // ── Accessibility sentinel guard ──────────────────────────
                    // Two sentinel strings that indicate the a11y service has not
                    // yet built a real tree and must not be forwarded to the LLM:
                    //   "(not ready)"                     — initialising on boot
                    //   "(accessibility service not active)" — service suspended by OS
                    // Sending either string to the LLM causes hallucinated node IDs.
                    // Pause for one tick, emit a UI event, and re-observe.
                    val a11ySentinel = snapshot.a11yTree == "(not ready)" ||
                                       snapshot.a11yTree == "(accessibility service not active)"
                    if (a11ySentinel) {
                        // FLAWS.md #5 — adaptive backoff. A flat 2 s wait was
                        // tuned for "service truly dead" but punished the much
                        // more common "tree just hasn't propagated yet" case.
                        Log.w("AgentLoop",
                            "A11y sentinel detected ('${snapshot.a11yTree}') — backing off ${a11ySentinelBackoffMs}ms")
                        state = state.copy(status = Status.PAUSED, lastError = "accessibility_not_ready")
                        AgentEventBus.emit("agent_status_changed", mapOf(
                            "status"      to "paused",
                            "currentTask" to state.goal,
                            "currentApp"  to state.appPackage,
                            "stepCount"   to state.stepCount,
                            "lastAction"  to state.lastAction,
                            "lastError"   to "accessibility_not_ready"
                        ))
                        delay(a11ySentinelBackoffMs)
                        a11ySentinelBackoffMs = (a11ySentinelBackoffMs * 2)
                            .coerceAtMost(A11Y_SENTINEL_BACKOFF_MAX_MS)
                        state = state.copy(status = Status.RUNNING, lastError = "")
                        continue
                    }
                    // Real tree this iteration → reset the sentinel backoff so
                    // the next transient is handled fast again.
                    a11ySentinelBackoffMs = A11Y_SENTINEL_BACKOFF_INITIAL_MS

                    // In learn-only mode the screen never changes (we never act),
                    // so skip the change-detection gate — process every observed frame.
                    if (!learnOnly && !ScreenObserver.hasChanged(previousSnapshot, snapshot)) {
                        delay(WAIT_RETRY_DELAY_MS)
                        continue
                    }
                    previousSnapshot = snapshot

                    // ── 1b. GAME DETECTION — switch to fast policy loop if game ──
                    // If a game is detected (score, level, game-over patterns / known pkg),
                    // hand off to GameLoop for the current step and skip LLM reasoning.
                    // GameLoop runs its own single step (Observe→Act→Store→Emit) and returns.
                    // We stay in this outer while loop so we re-detect on every tick —
                    // if the user leaves the game, next iteration will see GameType.NONE.
                    // In learn-only mode, game handoff is skipped — we never dispatch gestures.
                    val gameSignal = GameDetector.detect(snapshot)
                    if (!learnOnly &&
                        gameSignal.gameType != GameDetector.GameType.NONE &&
                        gameSignal.confidence >= GameDetector.MIN_CONFIDENCE
                    ) {
                        val newGameMode = gameSignal.gameType.name.lowercase()
                        if (state.gameMode != newGameMode) {
                            Log.i("AgentLoop", "Switching to game mode: $newGameMode (${gameSignal.triggerReason})")
                            state = state.copy(gameMode = newGameMode)
                            emitStatus()
                        }
                        // Start (or continue) GameLoop — it has its own coroutine
                        if (!GameLoop.isActive()) {
                            GameLoop.onEvent = onEvent
                            GameLoop.start(
                                context  = context,
                                goal     = goal,
                                gameType = gameSignal.gameType,
                                store    = store
                            )
                        }
                        delay(STEP_DELAY_MS)  // yield — GameLoop is driving
                        continue
                    }

                    // Left the game — stop GameLoop if it was running
                    if (state.gameMode != "none") {
                        Log.i("AgentLoop", "Leaving game mode → resuming LLM-guided loop")
                        GameLoop.stop()
                        state = state.copy(gameMode = "none")
                        emitStatus()
                    }

                    // ── 1d. VISUAL DETECTION — MediaPipe EfficientDet-Lite0 ───────────
                    // Runs ONLY when model is ready (non-blocking check).
                    // Covers icons, game sprites, Flutter/Unity widgets not in a11y tree.
                    // Producer-Consumer: detector runs here; LLM consumes via PromptBuilder.
                    // ~37ms on Exynos 9611 — within the 800ms STEP_DELAY_MS budget.
                    val detectedObjects = if (
                        ObjectDetectorEngine.isModelReady(context) && snapshot.bitmap != null
                    ) {
                        runCatching {
                            ObjectDetectorEngine.detect(context, snapshot.bitmap!!)
                        }.getOrDefault(emptyList())
                    } else emptyList()

                    // ── 2. RETRIEVE — find relevant past experiences ───────────
                    val memory = EmbeddingEngine.retrieve(
                        context = context,
                        query = "${goal} ${snapshot.ocrText.take(100)}",
                        store = store,
                        topK = 3
                    )

                    // ── 2b. LABEL LOOKUP — human-annotated elements for this screen
                    // Primary: exact screen hash match (same screen state, O(1) lookup)
                    // Fallback: embedding similarity across all enriched labels for the app
                    val screenLabels = labelStore.getByScreen(snapshot.appPackage, snapshot.screenHash())
                        .ifEmpty {
                            EmbeddingEngine.retrieveLabels(
                                context = context,
                                query = "${goal} ${snapshot.ocrText.take(100)}",
                                labelStore = labelStore,
                                appPackage = snapshot.appPackage,
                                topK = 5
                            )
                        }

                    // ── 2c. THERMAL CHECK — pause if device is running hot ─────
                    // Severe heat on M31 (no NPU, CPU-heavy inference) causes system throttle.
                    // Pause instead of OOM-crashing or burning through tok/s at 4 tok/s.
                    if (ThermalGuard.isEmergency()) {
                        state = state.copy(status = Status.PAUSED, lastError = "thermal_pause")
                        onEvent?.invoke("agent_status_changed", mapOf(
                            "status" to "paused",
                            "currentTask" to state.goal,
                            "currentApp" to state.appPackage,
                            "stepCount" to state.stepCount,
                            "lastAction" to state.lastAction,
                            "lastError" to "thermal_pause"
                        ))
                        delay(10_000L)  // wait 10s then re-check
                        if (ThermalGuard.isEmergency()) break  // still hot → abort
                        state = state.copy(status = Status.RUNNING, lastError = "")
                    }

                    // ── 2d. APP KNOWLEDGE — skill registry hint (Phase 15) ────────
                    // Fetch ARIA's accumulated knowledge about the current app.
                    // Injected as [APP KNOWLEDGE] block into the LLM system prompt.
                    val appKnowledge = runCatching {
                        AppSkillRegistry.getInstance(context).getPromptHint(snapshot.appPackage)
                    }.getOrDefault("")

                    // Cross-app knowledge: compact hints for the top-3 other apps
                    // the agent has learned, excluding the current foreground app.
                    // Lets the agent make informed decisions when switching apps
                    // mid-goal (e.g. share from Photos → WhatsApp).
                    val crossAppKnowledge = runCatching {
                        val registry = AppSkillRegistry.getInstance(context)
                        registry.getAll()
                            .filter { it.appPackage != snapshot.appPackage && it.promptHint.isNotBlank() }
                            .take(3)
                            .joinToString("\n") { "• ${it.appName.ifBlank { it.appPackage.substringAfterLast('.') }}: ${it.promptHint.take(80)}" }
                    }.getOrDefault("")

                    // ── 2e. VISION DESCRIPTION ────────────────────────────────────────
                    // Two modes depending on how the primary model was loaded:
                    //
                    // UNIFIED MODE (LlamaEngine.isUnifiedMode() == true):
                    //   The active catalog VLM is loaded as a single model instance that
                    //   handles both vision and reasoning.  VisionEngine.describe() is
                    //   SKIPPED — the VLM will see the raw screenshot bytes directly in
                    //   the REASON step via inferWithVision().  No intermediate text
                    //   description is needed; the model reasons over pixels + prompt.
                    //
                    // HELPER MODE (text-only / custom model, unifiedMode == false):
                    //   VisionEngine runs SmolVLM-256M as a separate screen-reading
                    //   helper, producing a [VISION DESCRIPTION] block injected into the
                    //   PromptBuilder text prompt — the original always-on behaviour.
                    //   Cache-hits cost <1 ms; new frames run ~400 ms of SmolVLM inference.
                    val unifiedVlmMode = LlamaEngine.isUnifiedMode()
                    val visionDescription: String = if (
                        !unifiedVlmMode &&
                        snapshot.bitmap != null &&
                        VisionEngine.isVisionModelReady(context)
                    ) {
                        runCatching {
                            VisionEngine.describe(
                                context     = context,
                                bitmap      = snapshot.bitmap!!,
                                goal        = goal,
                                screenHash  = snapshot.screenHash()
                            )
                        }.getOrDefault("").also { desc ->
                            if (desc.isNotBlank()) Log.d("AgentLoop", "Vision[${snapshot.screenHash()}]: $desc")
                        }
                    } else ""

                    // ── Multi-Modal Memory: store vision embedding keyed by screenHash ──────
                    // Uses the MiniLM embedding of SmolVLM's description so we get semantic
                    // visual retrieval with zero additional models. Runs only when vision ran.
                    if (visionDescription.isNotBlank()) {
                        runCatching {
                            val embVec = EmbeddingEngine.embed(context, visionDescription)
                            visionEmbStore.store(
                                screenHash  = snapshot.screenHash(),
                                appPackage  = snapshot.appPackage,
                                description = visionDescription,
                                embedding   = embVec
                            )
                        }
                    }

                    // ── 2f. SAM2 TAP CANDIDATES — MobileSAM (Phase 18) ─────────────
                    // Run MobileSAM on the current frame when:
                    //   a) The a11y tree is empty (game / Flutter / Unity screen), OR
                    //   b) Vision returned something useful but no nodes exist.
                    // SAM regions are formatted as normalised (x, y) strings and injected
                    // into the prompt as [SAM REGIONS] — LLM then uses TapXY to act.
                    val samRegions: List<String> = if (
                        snapshot.bitmap != null &&
                        Sam2Engine.isLoaded() &&
                        snapshot.a11yTree.isBlank()       // only needed when a11y tree is absent
                    ) {
                        runCatching {
                            Sam2Engine.segment(context, snapshot.bitmap!!)
                                .take(8)
                                .mapIndexed { i, r ->
                                    "[sam-${i + 1}] x=%.2f y=%.2f saliency=%.2f".format(r.normX, r.normY, r.score)
                                }
                        }.getOrDefault(emptyList())
                    } else emptyList()

                    if (samRegions.isNotEmpty()) {
                        Log.d("AgentLoop", "SAM2 found ${samRegions.size} tap candidates (no a11y nodes)")
                    }

                    // ── Multi-Modal Memory: visual retrieval when a11y is empty ───────────
                    // When no text signal exists, retrieve past experiences by vision similarity.
                    val visualMemory: List<String> = if (snapshot.isEmpty() && visionDescription.isNotBlank()) {
                        runCatching {
                            val queryEmb = EmbeddingEngine.embed(context, visionDescription)
                            visionEmbStore.retrieveSimilar(queryEmb, snapshot.appPackage, topK = 3)
                                .map { m -> "[${(m.similarity * 100).toInt()}% visual] ${m.description.take(80)}" }
                        }.getOrDefault(emptyList())
                    } else emptyList()

                    // ── Floating Chat: consume pending user instruction / gesture ─────────
                    // Drain the shared volatiles set by the event bus subscriber.
                    // These are appended to the history as a high-priority user note so the
                    // LLM sees them immediately in the next REASON pass.
                    val userNote = buildString {
                        pendingUserInstruction?.let { instr ->
                            append("\n[USER INSTRUCTION]: $instr")
                            pendingUserInstruction = null
                        }
                        pendingGestureAnnotation?.let { gesture ->
                            append("\n[USER GESTURE]: $gesture")
                            pendingGestureAnnotation = null
                        }
                    }.trim()
                    if (userNote.isNotBlank()) {
                        actionHistory.add("""{"tool":"UserNote","reason":"$userNote"}""")
                        Log.i("AgentLoop", "Injecting user note: $userNote")
                    }

                    // ── 3. REASON — call LLM ──────────────────────────────────
                    // Phase 19: goal = currentSubGoal so the LLM focuses on ONE step;
                    // goalPlan shows the full checklist so it keeps global context.
                    val goalPlan = if (subTasksRaw.size > 1)
                        ProgressPersistence.goalSummary(context)
                    else ""
                    val extendedMemory = (memory + visualMemory).take(5)
                    val prompt = PromptBuilder.build(
                        snapshot          = snapshot,
                        goal              = currentSubGoal,
                        history           = actionHistory,
                        memory            = extendedMemory,
                        objectLabels      = screenLabels,
                        detectedObjects   = detectedObjects,
                        appKnowledge      = appKnowledge,
                        crossAppKnowledge = crossAppKnowledge,
                        visionDescription = visionDescription,
                        samRegions        = samRegions,
                        stuckHint         = stuckHint,
                        goalPlan          = goalPlan
                    )

                    // ── Unified VLM: one inferWithVision() call — image + prompt together ──
                    // In unified mode the VLM sees the raw screenshot directly alongside
                    // the reasoning prompt.  No [VISION DESCRIPTION] block is needed because
                    // the model already has pixel-level context from its image encoder.
                    // Fallback to text-only infer() when no bitmap is available this step.
                    //
                    // Bitmap safety: the try-finally guarantees recycle() is called even
                    // if inference or token-callback throws — preventing memory leaks on
                    // the Exynos 9611 (no hardware bitmap pool on this SoC).
                    // The local `bmp` capture avoids forcing !! on snapshot.bitmap inside
                    // the lambda; all null checks are done before entering the try block.
                    val bmp = snapshot.bitmap
                    val rawOutput: String = try {
                        if (unifiedVlmMode && bmp != null) {
                            val imageBytes = VisionEngine.compressFramePublic(bmp)
                            LlamaEngine.inferWithVision(imageBytes, prompt, maxTokens = 200) { token ->
                                val tokData = mapOf("token" to token, "tokensPerSecond" to LlamaEngine.lastToksPerSec)
                                onEvent?.invoke("token_generated", tokData)
                                AgentEventBus.emit("token_generated", tokData)
                            }
                        } else {
                            LlamaEngine.infer(prompt, maxTokens = 200) { token ->
                                val tokData = mapOf("token" to token, "tokensPerSecond" to LlamaEngine.lastToksPerSec)
                                onEvent?.invoke("token_generated", tokData)
                                AgentEventBus.emit("token_generated", tokData)
                            }
                        }
                    } finally {
                        // Always recycle — even if inference or parsing throws.
                        // hasChanged() uses screenHash() only (no bitmap access), so
                        // previousSnapshot.bitmap being recycled here is safe.
                        bmp?.recycle()
                    }

                    // ── 4. PARSE ──────────────────────────────────────────────
                    val actionJson = PromptBuilder.parseAction(rawOutput)
                    actionHistory.add(actionJson)
                    if (actionHistory.size > 10) actionHistory.removeAt(0)

                    // ── 4b. STUCK DETECTION (Phase 18) ────────────────────────
                    // Track whether the screen changed AND the agent isn't spinning.
                    // Definition of "stuck": same screen hash AND action is NOT Wait/Back/Done.
                    val currentHash = snapshot.screenHash()
                    val isWaiting   = actionJson.contains("\"tool\":\"Wait\"", ignoreCase = true)
                    val isBacking   = actionJson.contains("\"tool\":\"Back\"", ignoreCase = true)
                    val isDoneNow   = actionJson.contains("\"tool\":\"Done\"", ignoreCase = true)
                    // Debounced stuck detection: require the hash to be UNCHANGED for
                    // 2 consecutive ticks before scoring it as a genuine stuck step.
                    // This prevents animated content (hash jitter from looping spinners,
                    // score counters, etc.) from falsely triggering the stuck escalation.
                    if (!isDoneNow && !isWaiting && !isBacking && currentHash == lastScreenHash) {
                        sameHashCount++
                        if (sameHashCount >= 2) {
                            sameHashCount = 0
                            stuckCount++
                            Log.w("AgentLoop", "Stuck count = $stuckCount (hash=$currentHash)")
                            stuckHint = when {
                                stuckCount >= 8 -> {
                                    // Abort — nothing we can do
                                    Log.e("AgentLoop", "Stuck limit reached — aborting task")
                                    state = state.copy(status = Status.DONE, lastError = "stuck_abort")
                                    ProgressPersistence.logTaskEnd(context, goal, succeeded = false)
                                    SustainedPerformanceManager.disable()
                                    emitStatus()
                                    recordAndChain(context, goal, lastAppPackage, succeeded = false,
                                        stepsTaken = state.stepCount, elementsTouched = elementsTouched)
                                    break
                                }
                                stuckCount >= 5 -> {
                                    // Force Back to break out of dead-end screen
                                    Log.w("AgentLoop", "Stuck $stuckCount — forcing Back")
                                    AgentAccessibilityService.performBack()
                                    "You have been stuck on the same screen for $stuckCount steps. " +
                                    "A Back action was forced. Try a completely different approach."
                                }
                                stuckCount >= 3 -> {
                                    "You seem stuck. Try a different action — scroll, Back, or look " +
                                    "for a different element. Avoid repeating the last action."
                                }
                                else -> stuckHint  // keep previous hint if any
                            }
                        }
                        // else: first tick with same hash — wait for next tick to confirm
                    } else {
                        // Hash changed or action was Wait/Back — reset debounce and stuck counter
                        sameHashCount = 0
                        if (currentHash != lastScreenHash) {
                            stuckCount = 0
                            stuckHint  = ""
                        }
                    }
                    lastScreenHash = currentHash

                    // ── 5. ACT ────────────────────────────────────────────────
                    val isDone = actionJson.contains("\"tool\":\"Done\"")
                    val isWait = actionJson.contains("\"tool\":\"Wait\"")

                    var actionSuccess = false
                    if (isDone) {
                        // ── Phase 19: Sub-task advancement ──────────────────────
                        // Mark the current sub-task as done in goals.json. If more
                        // sub-tasks remain, advance to the next one and CONTINUE the
                        // loop (rather than ending the task). Only when the last
                        // sub-task is marked done do we break and end the session.
                        ProgressPersistence.markSubTaskPassed(context, (subTaskIdx + 1).toString())
                        subTaskIdx++
                        val nextSubGoal = subTasksRaw.getOrNull(subTaskIdx)
                        if (nextSubGoal != null) {
                            currentSubGoal        = nextSubGoal
                            stuckCount            = 0
                            stuckHint             = ""
                            lastScreenHash        = ""
                            stepsInCurrentSubTask = 0   // fresh budget for each sub-task
                            Log.i("AgentLoop", "Sub-task done → step ${subTaskIdx + 1}/${subTasksRaw.size}: \"$currentSubGoal\"")
                            ProgressPersistence.logNote(context,
                                "Step ${subTaskIdx + 1}/${subTasksRaw.size}: $currentSubGoal")
                            delay(STEP_DELAY_MS)
                            continue   // restart while loop with next sub-goal
                        }
                        // All sub-tasks complete — end the full task
                        state = state.copy(status = Status.DONE, lastAction = actionJson)
                        ProgressPersistence.logTaskEnd(context, goal, succeeded = true)
                        SustainedPerformanceManager.disable()
                        emitStatus()
                        // Phase 15: record skill outcome, then auto-chain next queued task
                        recordAndChain(context, goal, lastAppPackage, succeeded = true,
                            stepsTaken = state.stepCount, elementsTouched = elementsTouched)
                        break
                    } else if (isWait) {
                        val duration = extractWaitDuration(actionJson)
                        delay(duration)
                        actionSuccess = true
                    } else if (learnOnly) {
                        // ── LEARN-ONLY: skip gesture, store reasoning as training data ──
                        // The LLM observed a real screen and produced a valid action — this
                        // (screen, goal) → action pair is valuable LoRA training data.
                        // We never dispatch the gesture so the device is not disturbed.
                        // Reward = 0.6 (neutral-positive). LlmRewardEnricher will rescore
                        // this during the next idle training cycle.
                        Log.d("AgentLoop", "Learn-only: storing reasoning without gesture dispatch")
                        actionSuccess = true
                    } else {
                        // ── Phase 14.1: PixelVerifier — fast action-result verification ──
                        // Capture the pre-action pixel state for the targeted node region.
                        // This avoids a full-screen capture; we only diff the acted element.
                        val actedNodeId = extractNodeId(actionJson)
                        val nodeRect = actedNodeId?.let { nid ->
                            runCatching {
                                val node = AgentAccessibilityService.getNodeById(nid)
                                if (node != null) {
                                    val r = android.graphics.Rect()
                                    node.getBoundsInScreen(r)
                                    r
                                } else null
                            }.getOrNull()
                        }

                        val preBitmap = nodeRect?.let { PixelVerifier.capturePre(it) }

                        val gestureSuccess = GestureEngine.executeFromJson(actionJson)

                        // Verify via pixel diff — faster and more precise than full-screen diff.
                        // If we have a valid region pre-bitmap AND the gesture was dispatched,
                        // use PixelVerifier result. Otherwise fall back to gesture result.
                        actionSuccess = if (preBitmap != null && nodeRect != null) {
                            val verification = PixelVerifier.verify(
                                preBitmap   = preBitmap,
                                screenRect  = nodeRect,
                                textVerify  = false   // text verify adds OCR cost — off by default
                            )
                            Log.d("AgentLoop", "PixelVerifier: diff=%.4f changed=${verification.changed}".format(verification.pixelDiff))
                            // Trust pixel diff if gesture was dispatched; AND-combine with gesture success
                            gestureSuccess && verification.changed
                        } else {
                            // No region available (e.g. Back action, or a11y node not found)
                            delay(SCREEN_SETTLE_MS)
                            gestureSuccess
                        }
                    }

                    // ── 6. EVALUATE — assign reward ───────────────────────────
                    // Base reward:
                    //   learn-only → 0.6 (unverified; LlmRewardEnricher rescores at training)
                    //   verified success → +1.0
                    //   verified failure → -0.5
                    // Label boost: if the action targeted a known labeled element,
                    //   add importanceScore/10 (0.0–1.0) to the reward.
                    //   Human-verified labels are ground truth — reward correct use.
                    val baseReward = when {
                        learnOnly     -> 0.6
                        actionSuccess -> 1.0
                        else          -> -0.5
                    }
                    val actedNodeId = extractNodeId(actionJson)
                    val labelBoost = if (actionSuccess && screenLabels.isNotEmpty() && actedNodeId != null) {
                        screenLabels.firstOrNull { label ->
                            actionJson.contains(label.name, ignoreCase = true) ||
                            actionJson.contains(label.ocrText.take(20), ignoreCase = true)
                        }?.let { it.importanceScore / 10.0 } ?: 0.0
                    } else 0.0
                    val reward = baseReward + labelBoost
                    val result = if (actionSuccess) "success" else "failure"

                    // ── 7. STORE — persist experience ────────────────────────
                    store.save(ExperienceStore.ExperienceTuple(
                        appPackage    = snapshot.appPackage,
                        taskType      = goal.take(60),
                        screenSummary = snapshot.toLlmString().take(500),
                        actionJson    = actionJson,
                        result        = result,
                        reward        = reward,
                        isEdgeCase    = !actionSuccess
                    ))

                    // ── Session Replay: record this step for timeline DVR ─────────────────
                    runCatching {
                        val replayResult = when {
                            isDone    -> SessionReplayStore.RESULT_SUCCESS
                            isWait    -> SessionReplayStore.RESULT_WAIT
                            stuckCount >= 3 -> SessionReplayStore.RESULT_STUCK
                            actionSuccess   -> SessionReplayStore.RESULT_SUCCESS
                            else            -> SessionReplayStore.RESULT_FAIL
                        }
                        val reason = Regex("\"reason\"\\s*:\\s*\"([^\"]+)\"")
                            .find(actionJson)?.groupValues?.get(1) ?: ""
                        replayStore.recordStep(
                            SessionReplayStore.ReplayEntry(
                                sessionId  = sessionId,
                                stepIdx    = state.stepCount,
                                screenHash = snapshot.screenHash(),
                                actionJson = actionJson,
                                reason     = reason,
                                result     = replayResult,
                                appPackage = snapshot.appPackage
                            )
                        )
                    }

                    // Track tools used this session for UsagePatternTracker
                    extractTool(actionJson)?.let { t ->
                        actionToolHistory.add(t)
                        currentActionTools = actionToolHistory.toList()
                    }

                    // ── Phase 14.4: Log step to progress.txt ──────────────────
                    // Appended immediately after SQLite write so even a crash between
                    // steps leaves a complete record for the next session to resume from.
                    ProgressPersistence.logStep(
                        context    = context,
                        stepNum    = state.stepCount + 1,
                        actionJson = actionJson,
                        result     = result
                    )

                    // ── Phase 15: Track elements for AppSkillRegistry ──────────
                    // Collect element names the agent acted on for frequency stats.
                    extractNodeId(actionJson)?.let { nid ->
                        if (nid.isNotBlank() && nid != "null") elementsTouched.add(nid)
                    }
                    // Track the app we were interacting with across game-mode switches
                    lastAppPackage = snapshot.appPackage

                    // ── 8. EMIT ───────────────────────────────────────────────
                    val newStep = state.stepCount + 1
                    stepsInCurrentSubTask++
                    state = state.copy(
                        stepCount  = newStep,
                        lastAction = actionJson
                    )

                    val actionData = mapOf(
                        "tool"        to (extractTool(actionJson) ?: "unknown"),
                        "nodeId"      to (extractNodeId(actionJson) ?: ""),
                        "success"     to actionSuccess,
                        "reward"      to reward,
                        "stepCount"   to newStep,
                        "appPackage"  to snapshot.appPackage,
                        "timestamp"   to System.currentTimeMillis()
                    )
                    onEvent?.invoke("action_performed", actionData)
                    AgentEventBus.emit("action_performed", actionData)
                    emitStatus()

                    delay(STEP_DELAY_MS)
                }

                if (state.stepCount >= MAX_STEPS) {
                    state = state.copy(status = Status.DONE, lastError = "max_steps_reached")
                    ProgressPersistence.logNote(context, "Max steps ($MAX_STEPS) reached — task abandoned")
                    ProgressPersistence.logTaskEnd(context, goal, succeeded = false)
                    SustainedPerformanceManager.disable()
                    emitStatus()
                    // Phase 15: record failure, then chain next task if queued
                    recordAndChain(context, goal, lastAppPackage, succeeded = false,
                        stepsTaken = state.stepCount, elementsTouched = elementsTouched)
                }

            } catch (e: Exception) {
                state = state.copy(status = Status.ERROR, lastError = e.message ?: "unknown error")
                ProgressPersistence.logNote(context, "EXCEPTION: ${e.message ?: "unknown error"}")
                ProgressPersistence.logTaskEnd(context, goal, succeeded = false)
                SustainedPerformanceManager.disable()
                emitStatus()
                // Phase 15: record failure, then chain next task if queued
                recordAndChain(context, goal, lastAppPackage, succeeded = false,
                    stepsTaken = state.stepCount, elementsTouched = elementsTouched)
            }
        }
    }

    fun stop() {
        val currentGoal = state.goal
        loopJob?.cancel()
        GameLoop.stop()
        state = state.copy(status = Status.IDLE, gameMode = "none")
        SustainedPerformanceManager.disable()
        MonitoringPusher.stop()
        emitStatus()
    }

    fun pause() {
        loopJob?.cancel()
        state = state.copy(status = Status.PAUSED)
        emitStatus()
    }

    fun isRunning(): Boolean = state.status == Status.RUNNING

    private fun emitStatus() {
        val data = mapOf(
            "status"      to state.status.name.lowercase(),
            "currentTask" to state.goal,
            "currentApp"  to state.appPackage,
            "stepCount"   to state.stepCount,
            "lastAction"  to state.lastAction,
            "lastError"   to state.lastError,
            "gameMode"    to state.gameMode
        )
        onEvent?.invoke("agent_status_changed", data)
        AgentEventBus.emit("agent_status_changed", data)
    }

    private fun extractTool(json: String): String? =
        Regex("\"tool\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)

    private fun extractNodeId(json: String): String? =
        Regex("\"node_id\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)

    private fun extractWaitDuration(json: String): Long {
        val ms = Regex("\"duration_ms\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()
        return (ms ?: 500L).coerceIn(100L, 5000L)
    }

    // ─── Phase 15 helpers ─────────────────────────────────────────────────────

    /**
     * Record this task's outcome in AppSkillRegistry, then check TaskQueueManager for
     * the next queued task and auto-start it if the engine is still available.
     *
     * This is the task-chaining entry point: called at all three loop exits
     * (Done, max-steps, exception). Runs in the existing coroutine scope.
     */
    private fun recordAndChain(
        context: Context,
        goal: String,
        appPackage: String,
        succeeded: Boolean,
        stepsTaken: Int,
        elementsTouched: List<String>,
    ) {
        scope.launch(Dispatchers.IO) {

            // ── Session Replay: close session record ───────────────────────────
            runCatching {
                SessionReplayStore.getInstance(context).endSession(currentSessionId)
            }

            // ── Proactive Goal Surfacing: detect recurring patterns ────────────
            if (succeeded && currentActionTools.isNotEmpty()) {
                runCatching {
                    val hit = UsagePatternTracker.getInstance(context).record(
                        context        = context,
                        appPackage     = appPackage,
                        goal           = goal,
                        actionSequence = currentActionTools,
                        stepCount      = stepsTaken
                    )
                    if (hit != null) {
                        // Generate a friendly suggestion text via LLM (short, non-blocking)
                        val suggText = if (LlamaEngine.isLoaded()) {
                            runCatching {
                                LlamaEngine.infer(
                                    "In one sentence, suggest to the user that they should automate this Android task. Task: ${goal.take(80)}. Be friendly and specific. No more than 15 words.",
                                    maxTokens = 30
                                ).trim().take(200)
                            }.getOrDefault("You've done this ${hit.repeatCount}× — want me to automate it?")
                        } else {
                            "You've done \"${goal.take(50)}\" ${hit.repeatCount}× this week — want me to automate it?"
                        }
                        SuggestionStore.getInstance(context).addSuggestion(
                            SuggestionStore.Suggestion(
                                appPackage     = appPackage,
                                goalText       = goal,
                                repeatCount    = hit.repeatCount,
                                suggestionText = suggText
                            )
                        )
                        AgentEventBus.emit("suggestion_available", mapOf(
                            "goal"        to goal,
                            "repeatCount" to hit.repeatCount,
                            "text"        to suggText
                        ))
                        Log.i("AgentLoop", "Suggestion stored: $suggText")
                    }
                }.onFailure { e ->
                    Log.w("AgentLoop", "Pattern tracking failed: ${e.message}")
                }
            }

            // 1. Update the skill registry for this app
            runCatching {
                AppSkillRegistry.getInstance(context).recordTaskOutcome(
                    appPackage      = appPackage,
                    appName         = "",       // no appName field on ScreenSnapshot; registry uses pkg suffix
                    goal            = goal,
                    succeeded       = succeeded,
                    stepsTaken      = stepsTaken,
                    elementsTouched = elementsTouched,
                )
            }.onFailure { e ->
                Log.w("AgentLoop", "AppSkillRegistry update failed: ${e.message}")
            }

            // 2. Emit skill_updated event so JS Modules screen refreshes
            val skill = runCatching { AppSkillRegistry.getInstance(context).get(appPackage) }.getOrNull()
            if (skill != null) {
                val data = mapOf(
                    "appPackage"  to skill.appPackage,
                    "taskSuccess" to skill.taskSuccess,
                    "taskFailure" to skill.taskFailure,
                    "successRate" to skill.successRate,
                )
                onEvent?.invoke("skill_updated", data)
                AgentEventBus.emit("skill_updated", data)
            }

            // 3. Auto-dequeue and start the next queued task — task chaining
            val next = runCatching { TaskQueueManager.dequeue(context) }.getOrNull()
            if (next != null) {
                Log.i("AgentLoop", "Task chaining: starting next queued task \"${next.goal}\" (${next.appPackage})")

                // Notify JS that task chaining is advancing
                val chainData = mapOf(
                    "taskId"     to next.id,
                    "goal"       to next.goal,
                    "appPackage" to next.appPackage,
                    "queueSize"  to TaskQueueManager.size(context)
                )
                onEvent?.invoke("task_chain_advanced", chainData)
                AgentEventBus.emit("task_chain_advanced", chainData)

                // Small delay so the previous task's UI updates settle before starting
                delay(1500L)

                // Start the next task on the main scope
                start(context, next.goal, next.appPackage)
            }
        }
    }
}
