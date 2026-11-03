package com.ariaagent.mobile.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ariaagent.mobile.ui.ComposeMainActivity
import com.ariaagent.mobile.core.agent.AgentLoop
import com.ariaagent.mobile.core.ai.LlamaProblemSolver
import com.ariaagent.mobile.core.events.AgentEventBus
import com.ariaagent.mobile.core.orchestration.AgentLoopComponent
import com.ariaagent.mobile.core.orchestration.CentralOrchestrator
import com.ariaagent.mobile.core.orchestration.EventRouter
import com.ariaagent.mobile.core.orchestration.LearningSchedulerComponent
import com.ariaagent.mobile.core.orchestration.LlamaEngineComponent
import com.ariaagent.mobile.core.orchestration.OrchestrationEvent
import com.ariaagent.mobile.core.orchestration.PolicyNetworkComponent
import com.ariaagent.mobile.core.orchestration.VisionEngineComponent
import com.ariaagent.mobile.core.rl.LearningScheduler
import com.ariaagent.mobile.core.system.ThermalGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * AgentForegroundService — Long-running foreground service for the agent reasoning loop.
 *
 * Problem: Android's Low Memory Killer (LMK) aggressively terminates background processes
 * to reclaim RAM — especially on 6GB devices running 1.5–1.9 GB LLM models.
 * A 20-second Llama 3.2 reasoning turn CAN be killed mid-inference without warning.
 * When killed, the agent loses its current step, the goal context, and any unsaved data.
 *
 * Solution: Foreground Service Architecture
 *
 * Running the reasoning loop inside a Foreground Service with a persistent notification
 * tells the Android OS that this process is "user-aware" — it is protected from LMK
 * termination at all priority levels short of a system emergency.
 *
 * Additionally, the foreground service survives Activity destruction (app backgrounded,
 * screen off) — the agent can continue reasoning even when the user switches apps.
 *
 * Service lifecycle:
 *   START:  startForegroundService(Intent(ACTION_START, goal, appPackage))
 *             → calls AgentLoop.start(goal, appPackage)
 *             → updates notification to show current task
 *   STOP:   startService(Intent(ACTION_STOP))
 *             → calls AgentLoop.stop()
 *             → calls stopSelf()
 *   PAUSE:  startService(Intent(ACTION_PAUSE))  → calls AgentLoop.pause()
 *   STATUS: AgentEventBus subscriptions update the notification text in real-time
 *
 * The notification shows:
 *   Title: "ARIA Agent — Running"
 *   Text:  "Step 12/50 · Click #4 · 92% success"   (updates every step)
 *   Action: "Stop" button → sends ACTION_STOP intent
 *
 * Context: Screen recording (ScreenCaptureService) runs as a separate foreground service.
 * This service handles ONLY the reasoning loop — not screen capture.
 *
 * Phase: 14.3 (Advanced Architecture — Foreground Service Architecture)
 */
class AgentForegroundService : Service() {

    companion object {
        private const val TAG = "AgentForegroundSvc"
        private const val CHANNEL_ID = "aria_agent_reasoning"
        private const val NOTIF_ID   = 1003

        const val ACTION_START = "com.ariaagent.ACTION_START"
        const val ACTION_STOP  = "com.ariaagent.ACTION_STOP"
        const val ACTION_PAUSE = "com.ariaagent.ACTION_PAUSE"

        const val EXTRA_GOAL        = "goal"
        const val EXTRA_APP_PACKAGE = "appPackage"
        const val EXTRA_LEARN_ONLY  = "learnOnly"

        // FLAWS.md #1 — share the live CentralOrchestrator so other modules
        // (LearningScheduler, AppSkillRegistry, AgentLoop) can publish health
        // signals without holding a Service reference. Set by onCreate, cleared
        // by onDestroy. May be null before the service starts.
        @Volatile
        var sharedOrchestrator: com.ariaagent.mobile.core.orchestration.CentralOrchestrator? = null
            private set

        /**
         * Convenience: start the foreground service with a goal.
         */
        fun startWithGoal(context: Context, goal: String, appPackage: String,
                          learnOnly: Boolean = false) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_GOAL, goal)
                putExtra(EXTRA_APP_PACKAGE, appPackage)
                putExtra(EXTRA_LEARN_ONLY, learnOnly)
            }
            context.startForegroundService(intent)
        }

        /**
         * Convenience: start in learn-only mode (observe + reason, never act).
         */
        fun startLearnOnly(context: Context, goal: String, appPackage: String = "") {
            startWithGoal(context, goal, appPackage, learnOnly = true)
        }

        /**
         * Convenience: stop the service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentGoal       = ""
    private var currentAppPackage = ""
    private var currentStep       = 0

    // LearningScheduler: drives on-device RL during charging/idle windows.
    // Started in onCreate(), stopped in onDestroy().
    private var learningScheduler: LearningScheduler? = null

    // ── CentralOrchestrator (FLAWS.md #1) ─────────────────────────────────────
    // Component-level coordinator: registry, event router, diff engine, health
    // monitor, scheduler, and LLM-backed problem-solving broker. Constructed
    // and started here (the service is the natural Android lifecycle owner)
    // and torn down in onDestroy. Other modules can grab it via the static
    // [orchestrator] accessor.
    private var centralOrchestrator: CentralOrchestrator? = null

    // ── Auto-recovery watchdog ────────────────────────────────────────────────
    // When the agent loop crashes (status = "error"), automatically retry up to
    // MAX_AUTO_RETRIES times with a 5-second cool-down between attempts.
    // Resets to 0 on every successful "done" or user-initiated start.
    private var autoRetryCount = 0
    private val MAX_AUTO_RETRIES = 3
    private val RETRY_DELAY_MS   = 5_000L

    // FLAWS.md #6 — the retry coroutine is launched **separately** from the
    // bus collector so a 5-second cool-down does not block the whole event
    // pipeline (and therefore does not race the user's Stop button).
    @Volatile private var pendingRetryJob: Job? = null
    @Volatile private var stopRequested: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Initialising…"))
        Log.i(TAG, "AgentForegroundService created")

        // ── ThermalGuard: register listener so thermal events reach the notification ─
        ThermalGuard.register(this, object : ThermalGuard.ThermalListener {
            override fun onThermalLevelChanged(level: ThermalGuard.ThermalLevel) {
                Log.i(TAG, "Thermal level changed → $level")
                if (level >= ThermalGuard.ThermalLevel.SEVERE) {
                    updateNotification("⚠ Thermal: $level — inference paused")
                }
            }
        })

        // ── LearningScheduler: start battery-change listener for auto-RL during charging ─
        learningScheduler = LearningScheduler(this).also { it.start() }
        Log.i(TAG, "LearningScheduler started — auto-training active during charging")

        // ── CentralOrchestrator (FLAWS.md #1) ──────────────────────────────────
        // Construct the orchestrator with the on-device LLM as its problem-solver,
        // bridge orchestration events onto the spine's AgentEventBus so the UI can
        // observe component health, register the engines we know about today, then
        // start the periodic audit loop. All of this is launched on a coroutine so
        // that initialise()'s suspend calls don't block the Service main thread.
        val orch = CentralOrchestrator(
            context = this,
            problemSolver = LlamaProblemSolver(),
        )
        centralOrchestrator = orch
        sharedOrchestrator = orch
        serviceScope.launch {
            try {
                orch.initialize()
                orch.eventRouter.subscribe(EventRouter.WILDCARD) { evt ->
                    val payload = mutableMapOf<String, Any>("source" to evt.source)
                    for ((k, v) in evt.data) if (v != null) payload[k] = v
                    AgentEventBus.emit("orchestration.${evt.eventType}", payload)
                }
                // FLAWS.md follow-up — register LIVE ComponentInterface
                // adapters, not name-only entries. The orchestrator's real
                // RegistryStageExecutor (wired in start()) dispatches stage
                // calls straight into these adapters, so any pipeline that
                // names "llama_engine" or "vision_engine" actually runs the
                // engine instead of the old NoopStageExecutor's canned reply.
                orch.registerInstance(LlamaEngineComponent())
                orch.registerInstance(AgentLoopComponent())
                orch.registerInstance(VisionEngineComponent())
                orch.registerInstance(PolicyNetworkComponent())
                // LearningScheduler is already started above (line ~174);
                // wrapping it as a ComponentInterface gives the orchestrator
                // health visibility (so a dead scheduler shows up in audits)
                // without re-arming its broadcast receiver or wake lock.
                learningScheduler?.let { orch.registerInstance(LearningSchedulerComponent(it)) }
                orch.start()
                startOrchestrationHeartbeatLoop(orch)
                Log.i(TAG, "CentralOrchestrator initialised and started")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to bring up CentralOrchestrator", t)
            }
        }

        // Subscribe to AgentEventBus to keep the notification current AND
        // to drive the auto-recovery watchdog on error status.
        //
        // FLAWS.md #6 — this collector MUST stay non-blocking. The watchdog's
        // 5-second cool-down used to live inside this `collect` block, which
        // froze the bus subscriber and let "agent_status_changed=done/idle"
        // and ACTION_STOP races slip through. The retry now runs on its own
        // coroutine and is cancelled when the user (or the OS) stops us.
        serviceScope.launch {
            AgentEventBus.flow.collect { (name, data) ->
                when (name) {
                    "action_performed" -> {
                        currentStep = (data["stepCount"] as? Int) ?: currentStep
                        val tool    = data["tool"]?.toString() ?: "…"
                        val success = data["success"] as? Boolean ?: true
                        updateNotification("Step $currentStep · $tool · ${if (success) "✓" else "✗"}")
                        // Real per-step heartbeat: AgentLoop just finished a tool
                        // call, so the loop is provably alive *right now*. This is
                        // stronger evidence than the 10 s polling backstop, which
                        // can only prove the JVM thread is responsive.
                        centralOrchestrator?.healthMonitor?.recordHeartbeat("agent_loop")
                    }
                    "token_generated" -> {
                        // LlamaEngine just decoded a token — it is not stuck on
                        // the JNI side. The polling backstop calls isHealthy()
                        // (which checks `ready` and `lastUseAt`); this case
                        // proves liveness from the actual hot path.
                        centralOrchestrator?.healthMonitor?.recordHeartbeat("llama_engine")
                    }
                    "agent_status_changed" -> {
                        val status = data["status"]?.toString() ?: return@collect
                        when (status) {
                            "done" -> {
                                autoRetryCount = 0
                                pendingRetryJob?.cancel()
                                pendingRetryJob = null
                                updateNotification("Done — tap to open ARIA")
                            }
                            "idle" -> {
                                updateNotification("Idle — tap to open ARIA")
                            }
                            "paused" -> {
                                updateNotification("Paused")
                            }
                            "error" -> {
                                val err = data["lastError"]?.toString() ?: "unknown"
                                Log.w(TAG, "Agent error: $err (retry $autoRetryCount/$MAX_AUTO_RETRIES)")

                                // Push the failure into the orchestrator so its
                                // ProblemSolvingBroker can ask the on-device LLM
                                // for a diagnosis. This is the bus->orchestration
                                // direction; the orchestration->bus bridge runs
                                // above (the WILDCARD subscriber).
                                centralOrchestrator?.eventRouter?.publish(
                                    OrchestrationEvent(
                                        eventType = OrchestrationEvent.Type.COMPONENT_ERROR,
                                        source = "agent_loop",
                                        data = mapOf(
                                            "error_type" to "agent_loop_error",
                                            "error_message" to err,
                                            "step" to currentStep,
                                            "goal" to currentGoal,
                                        ),
                                    ),
                                )

                                if (!stopRequested &&
                                    autoRetryCount < MAX_AUTO_RETRIES &&
                                    currentGoal.isNotBlank()
                                ) {
                                    autoRetryCount++
                                    val attempt = autoRetryCount
                                    updateNotification("Error: $err — retrying ($attempt/$MAX_AUTO_RETRIES)…")
                                    // Cancel any prior in-flight retry (rare but possible
                                    // if the loop emits two errors in quick succession).
                                    pendingRetryJob?.cancel()
                                    pendingRetryJob = serviceScope.launch {
                                        // Wait off-thread so the bus collector keeps
                                        // pumping events (e.g. user-initiated STOP).
                                        delay(RETRY_DELAY_MS)
                                        if (stopRequested) {
                                            Log.i(TAG, "Auto-recovery attempt $attempt cancelled — stop requested")
                                            return@launch
                                        }
                                        Log.i(TAG, "Auto-recovery attempt $attempt — restarting goal: $currentGoal")
                                        AgentLoop.start(this@AgentForegroundService, currentGoal, currentAppPackage)
                                    }
                                } else {
                                    updateNotification("Error: $err — max retries reached, tap to restart")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            null -> {
                // Service was restarted by Android OS after process death (START_STICKY).
                // New instance starts with empty currentGoal — restore from persistent storage.
                val prefs = getSharedPreferences("aria_service_state", Context.MODE_PRIVATE)
                if (currentGoal.isBlank()) {
                    currentGoal       = prefs.getString("currentGoal", "") ?: ""
                    currentAppPackage = prefs.getString("currentAppPackage", "") ?: ""
                }
                if (currentGoal.isNotBlank() && AgentLoop.state.status == AgentLoop.Status.IDLE) {
                    Log.i(TAG, "OS-restart recovery — resuming goal: $currentGoal")
                    updateNotification("Resuming: $currentGoal")
                    autoRetryCount = 0
                    AgentLoop.start(this, currentGoal, currentAppPackage)
                }
            }
            ACTION_START -> {
                val goal       = intent.getStringExtra(EXTRA_GOAL) ?: return START_NOT_STICKY
                val appPackage = intent.getStringExtra(EXTRA_APP_PACKAGE) ?: ""
                val learnOnly  = intent.getBooleanExtra(EXTRA_LEARN_ONLY, false)
                currentGoal       = goal
                currentAppPackage = appPackage
                currentStep       = 0
                autoRetryCount    = 0
                stopRequested     = false   // FLAWS.md #6 — re-arm the watchdog
                pendingRetryJob?.cancel()
                pendingRetryJob   = null
                // Persist goal so OS-restart recovery can resume it (Bug #5 fix)
                getSharedPreferences("aria_service_state", Context.MODE_PRIVATE).edit()
                    .putString("currentGoal", goal)
                    .putString("currentAppPackage", appPackage)
                    .apply()

                val modeLabel = if (learnOnly) "Learn-only" else "Starting"
                updateNotification("$modeLabel: $goal")
                Log.i(TAG, "Starting agent loop — goal='$goal' learnOnly=$learnOnly")
                AgentLoop.start(this, goal, appPackage, learnOnly = learnOnly)
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stopping agent loop via service command")
                stopRequested  = true             // FLAWS.md #6 — block in-flight retry
                pendingRetryJob?.cancel()
                pendingRetryJob = null
                autoRetryCount = MAX_AUTO_RETRIES // prevent watchdog from re-arming
                AgentLoop.stop()
                // Clear persisted goal so a future OS restart doesn't resume a cancelled task
                getSharedPreferences("aria_service_state", Context.MODE_PRIVATE).edit()
                    .remove("currentGoal")
                    .remove("currentAppPackage")
                    .apply()
                stopSelf()
            }
            ACTION_PAUSE -> {
                Log.i(TAG, "Pausing agent loop via service command")
                AgentLoop.pause()
                updateNotification("Paused")
            }
        }
        return START_STICKY
    }

    /**
     * Periodically poll every registered ComponentInterface and record a
     * heartbeat for the ones that report `isHealthy()`. This is the missing
     * heartbeat producer the HealthMonitor needs — without it every component
     * goes stale after the 30-second timeout window and is marked degraded
     * the first time the health check sweeps.
     *
     * Cheap: just iterates the four-ish registered IDs, no I/O. Runs every
     * 10 s on the service scope; cancelled when the service is destroyed.
     */
    private fun startOrchestrationHeartbeatLoop(orch: CentralOrchestrator) {
        serviceScope.launch {
            // Components we expect to keep alive. The list is small and
            // explicit so we don't accidentally heartbeat stale registry
            // entries that no longer have a live instance.
            val ids = listOf(
                LlamaEngineComponent.ID,
                AgentLoopComponent.ID,
                VisionEngineComponent.ID,
                PolicyNetworkComponent.ID,
            )
            while (true) {
                delay(10_000L)
                for (id in ids) {
                    val component = orch.getInstance(id) ?: continue
                    if (component.isHealthy()) {
                        orch.healthMonitor.recordHeartbeat(id)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // FLAWS.md #6 — make sure the watchdog cannot re-fire after teardown.
        stopRequested = true
        pendingRetryJob?.cancel()
        pendingRetryJob = null

        learningScheduler?.stop()
        learningScheduler = null
        ThermalGuard.unregister(this)

        // FLAWS.md #1 — tear the orchestrator down before cancelling the
        // service scope so its periodic audit and scheduler coroutines exit
        // cleanly. shutdown() is non-suspending and cancels its own internal
        // scope, so we don't need to call stop() first.
        centralOrchestrator?.shutdown()
        centralOrchestrator = null
        sharedOrchestrator = null

        serviceScope.cancel()
        Log.i(TAG, "AgentForegroundService destroyed")
    }

    // ─── Notification helpers ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ARIA Agent Reasoning",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the agent's current reasoning task and step progress"
            setShowBadge(false)
        }
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String) = run {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ComposeMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AgentForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ARIA Agent")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(statusText))
    }
}
