# Agent Protocol Overview

> Op/Event communication, state machine, errors, and utilities.
> Last updated: 2026-03-05 (commit: 0b5b379)

## Overview

Unidirectional flow with a task-based model:

```
┌────────────┐         Op           ┌──────────────┐
│     UI     │ ─────────────────►   │ AgentSession │
│            │ ◄─────────────────   │              │
└────────────┘      AgentEvent      └──────────────┘
```

- **Operations (Op)**: user intents sent via `session.submit(op)`
- **Events (AgentEvent)**: state/progress emitted via `session.events`
- **Task model**: Session (long-lived) → Task (work from `Op.UserInput`) → Turn (Perceive → Think → Act → Observe)

## Operations (Op)

> See: `protocol/Op.kt`

### State Transitions

```
                              ┌─────────┐
                              │ Created │
                              └────┬────┘
                                   │ Op.UserInput
                                   ▼
                    ┌─────────────────────────────┐
            ┌──────►│           Running           │◄──────┐
            │       └──────┬───────────────┬──────┘       │
            │  Op.Takeover │               │ TaskCompleted│
            │              ▼               │              │
            │       ┌──────────────┐       │              │
   Op.Resume│       │TakeoverPending│      ▼              │
            │       └──────┬───────┘ ┌──────────┐         │
            │   safe-point │         │   Idle   │─────────┘
            │              ▼         └────┬─────┘
            │         ┌────────┐          │ Op.UserInput
            └─────────┤ Paused │◄─────────┘
                      └────────┘
                                          │ Op.Shutdown
                                          ▼
                                   ┌───────────┐
                                   │  Shutdown │
                                   └───────────┘
```

| Operation | Valid States | Effect |
|-----------|--------------|--------|
| `UserInput(text)` | Created, Idle | Start a new task |
| `Takeover` | Running | Pause cooperatively |
| `Resume` | Paused | Resume execution |
| `Interrupt` | Running | Stop current task → Idle |
| `Shutdown` | Any | Graceful shutdown |
| `Approve(id, decision, scope, packageName)` | Running | Resolve pending app-level approval |
| `Supplement(text)` | Running, Paused | Inject context mid-task |
| `UserResponse(callId, response)` | Running | Respond to `ask_user` |

### Approval

`ApprovalDecision`: `APPROVED` (execute), `DENIED` (skip current pending tool call), `ABORT` (stop session).

`ApprovalScope`: `SESSION` (allow this package for the current session) or
`ALWAYS` (persist the package allow-list entry). There is no one-shot positive
approval scope.

## Session State Machine

> See: `protocol/SessionState.kt`

| State | Description |
|-------|-------------|
| `Created` | Initialized, not started |
| `Running` | Actively executing a task |
| `Idle` | Between tasks (Hot Idle — lightweight state, expensive resources released) |
| `TakeoverPending` | Pause requested but agent has not yet reached a safe pause point (Resume rejected) |
| `Paused` | Cooperative takeover confirmed |
| `Shutdown` | Terminal — all resources released |

-> See: [Session State Machine](../ui/session/state_machine.md) for formal transition rules and resource ownership.

## Error Handling

Errors surface as `SessionError(message: String)` on the event stream (see
`protocol/SessionLifecycleEvents.kt`). There is no separate sealed error
hierarchy in the protocol layer — error classification and recovery decisions
live with the producing subsystems (LLM client, platform, tool router), and
only a human-readable message is published to the UI. Fatal failures still
emit a `SessionCompleted(reason = INTERRUPTED)` once the session winds down.

## Utilities

**SessionId** (`protocol/SessionId.kt`): `@JvmInline value class SessionId(val value: String)` — UUID-based unique identifier.

**compactThought** (`protocol/TextUtils.kt`): Trims whitespace, truncates to 80 chars with ellipsis. Opt-in only — used by surfaces that explicitly need a single-line preview (capsule reduced-motion fallback, error/status banners). Canonical pipeline preserves full text. Renamed from `sanitizeThought` in uxfb-1 (was 40 chars and called everywhere, silently dropping data).

## Subpages

| Page | Focus |
|------|-------|
| [events.md](events.md) | AgentEvent hierarchy, domain interfaces, key events |
| [config.md](config.md) | SessionConfig, PlatformMode, LLM config |

## File Structure

```
protocol/
├── Op.kt                     # UI→Agent commands
├── AgentEvent.kt             # Base event interface
├── AgentEventDomains.kt      # 11 domain marker interfaces
├── SessionConfig.kt          # Session configuration (ApprovalMode, PlatformMode, LLMBackendType, SessionLlmConfig)
├── SessionState.kt           # 6-state machine (incl. TakeoverPending)
├── SessionId.kt              # Session identifier
├── AppTier.kt                # BLOCKED / CAUTIOUS / NORMAL classification
├── TaskOutcome.kt            # Task-level outcome (GOAL_ACHIEVED / TASK_IMPOSSIBLE / ERROR / USER_STOPPED)
├── SessionEndReason.kt       # Session-level shutdown reason (USER_STOPPED / IDLE_TIMEOUT / INTERRUPTED)
├── CompletionHandoff.kt      # VD-only completion metadata for chat CTA
├── SessionLifecycleEvents.kt # SessionStarted/Completed/Error/Takeover/Resumed/SupplementReceived
├── TaskLifecycleEvents.kt    # TaskStarted/TaskCompleted
├── TurnEvents.kt             # TurnStarted/Completed/PhaseChanged (+ TurnPhase.kt)
├── StreamingEvents.kt        # MessageDelta
├── ActionEvents.kt           # ActionProposed/Executed + ActionOutcome
├── ApprovalEvents.kt         # ApprovalRequired
├── AskUserEvents.kt          # AskUser (+ AskUserType.kt)
├── ThoughtEvents.kt          # ThoughtUpdate
├── SubAgentEvents.kt         # SubAgentStarted/Activity/Completed
├── PerceptionEvents.kt       # ScreenCaptured (+ ScreenStatePhase.kt)
├── StatusEvents.kt           # StatusUpdate
├── ApprovalTypes.kt          # ApprovalDecision, ApprovalScope, ApprovalDetails
├── TodoModels.kt             # Todo, TodoStatus
└── TextUtils.kt              # compactThought (opt-in 80-char preview)
```

## Related Docs

- [Events](events.md) - Full event hierarchy
- [Config](config.md) - Session configuration
- [Session](../infra/session.md) - AgentSession lifecycle
- [Loop](../agent/loop.md) - Turn execution emitting events
- [Platform](../infra/platform.md) - Platform mode configuration
