# `core/orchestration/` — ported from AII donors

This package closes **GAP_AUDIT.md item #3** ("No central orchestrator —
`AgentEventBus` is just a topic bus").

## What's in here

| File | Ported from | Role |
|---|---|---|
| `ComponentInterface.kt` | `donors/orchestration-java/ComponentInterface.java` | Contract every plug-in component must satisfy. |
| `ComponentStateSnapshot.kt` | `…/ComponentStateSnapshot.java` | Immutable point-in-time component state + content hash. |
| `StateDiff.kt` | `…/StateDiff.java` | Per-field deltas + severity tier. |
| `ValidationContract.kt` | `…/ValidationContract.java` | Optional output validator. |
| `OrchestrationEvent.kt` | `…/OrchestrationEvent.java` | Event payload + canonical event-type constants. |
| `ProblemTicket.kt` | `…/ProblemTicket.java` | Mutable problem report submitted to the broker. |
| `ComponentRegistry.kt` | `…/ComponentRegistry.java` | Thread-safe registry, capability lookup, status tracking. |
| `EventRouter.kt` | `…/EventRouter.java` | Topic + wildcard subscriptions, async dispatch on `Dispatchers.Default`. |
| `CircuitBreaker.kt` | `…/CircuitBreaker.java` | Three-state breaker (CLOSED → OPEN → HALF_OPEN). Donor package typo fixed. |
| `DiffEngine.kt` | `…/DiffEngine.java` | Snapshot comparison, throttled per-component, severity heuristic. |
| `HealthMonitor.kt` | `…/HealthMonitor.java` | Heartbeats, error/success counters, owns one circuit breaker per component. |
| `ProblemSolvingBroker.kt` | `…/ProblemSolvingBroker.java` | LLM-backed diagnosis. Cloud Groq replaced with `ProblemSolver` interface. |
| `OrchestrationScheduler.kt` | `…/OrchestrationScheduler.java` | Pipelines + trigger rules. Stage execution delegated to `StageExecutor`. |
| `CentralOrchestrator.kt` | `…/CentralAIOrchestrator.java` | The coordinator. **Not** an Android `Service` — plain coroutine class. |

## What changed during the port

1. **No `android.app.Service` base class.** The donor's
   `CentralAIOrchestrator` *was* the Android service. The spine already has
   `AgentForegroundService`, so `CentralOrchestrator` here is a regular
   long-lived object the foreground service can hold.
2. **`ExecutorService` / `Handler` → `CoroutineScope` + `Dispatchers`.** All
   periodic work, fan-out, and async dispatch is now coroutine-based.
3. **Donor `Groq`/`FeedbackSystem`/`ErrorResolutionWorkflow` deps dropped.**
   Those classes don't exist in the curated donor subset and the spine is
   on-device-only. `ProblemSolvingBroker` now takes a
   `ProblemSolver` interface — the spine plugs `LlamaEngine` in via an
   adapter (TODO: `LlamaProblemSolver` next to `LlamaEngine.kt`).
4. **`CircuitBreaker.java`'s package typo fixed.** Donor file declares
   `package com.aiassistant.core/orchestration;` (slash instead of dot).
   The Kotlin port uses the correct dotted path.
5. **`OrchestrationScheduler` stage execution** is now pluggable via a
   `StageExecutor` interface. The donor stubbed every stage with a fake
   "success" result; the spine will install a real executor that calls
   registered components by id.
6. **Idiomatic Kotlin throughout:** `data class` for immutable carriers
   (`ComponentStateSnapshot`, `StateDiff`, `OrchestrationEvent`), `enum class`
   for state machines, `@Volatile` + atomics for cheap mutable shared state,
   `Mutex` for state-machine lifecycle transitions.

## Wiring it into the spine

Pseudo-code, to be expanded in a follow-up port:

```kotlin
val orchestrator = CentralOrchestrator(
    context = appContext,
    problemSolver = LlamaProblemSolver(),  // wraps LlamaEngine.infer()
)

// Bridge orchestration events onto the spine's UI-facing AgentEventBus
orchestrator.eventRouter.subscribe(EventRouter.WILDCARD) { evt ->
    AgentEventBus.emit(
        name = "orchestration.${evt.eventType}",
        data = mapOf("source" to evt.source) + evt.data,
    )
}

// Register the LLM, perception, RL, and gesture engines as components
orchestrator.registry.registerComponent(
    componentId = "llama_engine",
    componentName = "Llama Engine",
    capabilities = listOf("inference", "embedding"),
)
// …same for ObjectDetectorEngine, PolicyNetwork, GestureEngine, AgentLoop

scope.launch { orchestrator.start() }
```

## Open follow-ups

- ~~Implement `LlamaProblemSolver` adapter that calls `LlamaEngine.infer()`.~~
  **Done** — `core/ai/LlamaProblemSolver.kt`. Defaults to `maxTokens=256`,
  `temperature=0.3` (conservative diagnostics). Throws when the model is not
  loaded so the broker escalates the ticket honestly.
- ~~Bridge `EventRouter` ↔ `AgentEventBus` so orchestration health events show
  up in the Compose UI's Activity / Modules screens.~~ **Done (2026-04-27)** —
  `AgentForegroundService.onCreate()` subscribes a wildcard listener on
  `EventRouter` and re-emits each event onto `AgentEventBus` under the
  `"orchestration.<eventType>"` namespace, with the originating component id
  copied into the payload as `"source"`. The Compose UI subscribes to that
  same bus, so any orchestration event is one `flow.collect { … }` away.
- ~~Wire `CentralOrchestrator` into the Android lifecycle.~~ **Done
  (2026-04-27)** — constructed with `LlamaProblemSolver()` in
  `AgentForegroundService.onCreate()`, started after registering the four
  current engines (`llama_engine`, `agent_loop`, `vision_engine`,
  `policy_network`), and torn down in `onDestroy()`. The instance is exposed
  to other modules as `AgentForegroundService.sharedOrchestrator`.
- ~~Implement a real `OrchestrationScheduler.StageExecutor` that resolves
  `componentId` → `ComponentInterface` and invokes `execute()`.~~ **Done
  (2026-04-27)** — `CentralOrchestrator` now owns a `componentInstances`
  map and a private `registryStageExecutor` that looks up the live adapter
  by id and calls its `execute()`. The executor is plugged into the
  scheduler in `start()` *before* trigger evaluation begins, so the old
  `NoopStageExecutor` is never reached once `start()` returns. Unknown
  component ids return `null`, which trips the scheduler's existing
  circuit-breaker recording.
- ~~Migrate `AgentLoop`, `LlamaEngine`, `ObjectDetectorEngine`, `PolicyNetwork`,
  `LoraTrainer`, `GestureEngine` to implement `ComponentInterface`.~~ **Done
  for the four engines that ship today (2026-04-27)** — `EngineComponents.kt`
  defines `LlamaEngineComponent`, `VisionEngineComponent`, `AgentLoopComponent`,
  `PolicyNetworkComponent`. They are thin adapters (engines stay decoupled
  from the orchestration package) and are registered via
  `orch.registerInstance(...)` in `AgentForegroundService.onCreate()`.
  `LoraTrainer` / `GestureEngine` adapters can be added by the same pattern
  when those engines land in the spine.
- ~~Heartbeat producer for live components.~~ **Done (2026-04-27)** —
  `AgentForegroundService.startOrchestrationHeartbeatLoop()` polls each
  registered live instance every 10 s and records a heartbeat when
  `isHealthy()` returns true. Without this, every component would go stale
  after the 30 s window and be flipped to `DEGRADED` on the first health
  sweep.
- ~~Bus → orchestration error bridge.~~ **Done (2026-04-27)** — when the
  agent loop emits `agent_status_changed status=error` on `AgentEventBus`,
  `AgentForegroundService` publishes a matching `COMPONENT_ERROR`
  `OrchestrationEvent` on the orchestrator's `EventRouter`. The
  orchestrator's existing `handleComponentError` then submits a
  `ProblemTicket` to `ProblemSolvingBroker`, which calls
  `LlamaProblemSolver.solve()` (i.e. real on-device `LlamaEngine.infer`)
  for a diagnosis. End-to-end: agent failure → ticket → LLM diagnosis,
  no fake hops.
- ~~Feed `DiffEngine` real snapshots so STATE_DIFF_DETECTED can fire.~~
  **Done (2026-04-27)** — `CentralOrchestrator.registerInstance()` now seeds
  `diffEngine.setExpectedState(component.captureState())` and an initial
  `latestSnapshot` at registration time, so the first registered state
  becomes the baseline. `performPeriodicAudit()` then iterates
  `componentInstances`, calls each `captureState()`, and feeds the result
  to `diffEngine.captureSnapshot(...)` *before* `performPeriodicDiffCheck()`
  runs. Result: a real divergence (e.g. `LlamaEngine` `loaded` flips to
  `false` mid-run) emits `STATE_DIFF_DETECTED` on the event router, which
  the bus bridge already re-broadcasts as `orchestration.STATE_DIFF_DETECTED`
  for the UI.
- ~~Promote `LearningScheduler` to an orchestration component.~~ **Done
  (2026-04-27)** — `EngineComponents.kt` adds `LearningSchedulerComponent`
  (constructor takes the live `LearningScheduler` so we don't double-arm
  its broadcast receiver or wake lock). `AgentForegroundService.onCreate()`
  registers it after the four engines via
  `orch.registerInstance(LearningSchedulerComponent(it))`. The scheduler
  now appears in audits with `state = { is_training: Bool }` and shows up
  in `status()` as `"training"` or `"idle (waiting for charge)"`.
- ~~Bus-event-driven heartbeats from real engine activity.~~ **Done
  (2026-04-27)** — the existing `AgentEventBus.flow.collect` block in
  `AgentForegroundService` now records a heartbeat for `agent_loop` on
  every `action_performed` event (proves the loop just ran a tool call)
  and for `llama_engine` on every `token_generated` event (proves the
  GGUF decoder is alive on the JNI side). The 10 s polling backstop
  remains for `vision_engine`, `policy_network`, `learning_scheduler`,
  which don't emit per-step bus events.
