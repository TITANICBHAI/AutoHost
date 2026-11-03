# AGENT_RUNTIME — the optional, host-owned AI agent runtime (Phase 13)

> **Status: DESIGN. Nothing in this document is built, compiled or verified.** It specifies what the implementing agent builds in Phase 13 (`docs/PROGRESS.md`), under ADR-086…098 (`docs/DECISIONS.md`). Facts about the *existing* code carry a path and were read on 2026-10-29 (static reading only — Gradle and Android were unavailable). Numbers marked **(placeholder)** are tuned by the eval harness (P13-16), never taken as measured.
> Precedence: `plan.md` §3.2/§7.12/§18 > this file. If they disagree, log it in `docs/DECISIONS.md`.

Related: `docs/MODEL_GATEWAY.md` (the model client), `docs/ACTION_HARDENING.md` (the layer the agent leans on), `docs/LOCAL_INFERENCE.md` (Phase 16), `docs/REUSE_MATRIX.md` (what may be adapted), `docs/UI_SPEC.md` §RunCapsule (Phase 14), `drafts/agent.schema.draft.json` (Stage B manifest block).

---

## 1. What it is, and what it is not

**Is:** a fourth runtime (`runtime.type: "agent"`) next to Python, Flow and JavaScript. A user states a goal ("Open Settings and turn on Do Not Disturb"); a model proposes **one tool call per turn**; the host validates it, applies policy, performs it through the **capability broker**, observes the result, and repeats until the goal is finished, a budget is hit, or the user stops it.

**Is not:** a second automation stack. There is no second action path, no model-written code, no memory across runs, no multi-agent, no voice, no outbound MCP client, no app-specific automators (ADR-086 (6)). Python stays the user-automation language; the agent adds **no interpreter and no native code** — its CPU cost is network wait plus JSON.

**Staged (ADR-086):**

| Stage | Scope | Gate |
|---|---|---|
| **A** | Internal, user-started, *supervised* "Ask" run. Task id `builtin.agent.adhoc`; parameters in reserved run-config key `config.agent`; schedules, triggers and the Dev API **cannot** start agent runs | G13-a…f |
| **B** | `runtime.type: "agent"` + `agent{}` manifest block, `verify` clause, optional unattended scheduling with declared budgets and a per-task user grant | only after G13-a…f pass **on a real device** |

Off until the user enables **AI features** in Settings (data-egress disclosure) and adds a provider profile (D-15).

---

## 2. Where it sits

```
 Ask sheet / capsule (:app)                                  LlmGateway (:core:llm) ───► HTTPS / loopback endpoint
        │  AgentRunRequest                                          ▲  chat(ChatRequest)        (docs/MODEL_GATEWAY.md)
        ▼                                                           │
 AgentRunStarter (:app) ── preflight ── synthesized TaskManifest    │
        │  StartRequest                                             │
        ▼                                                           │
 RunOrchestrator.start ─► GrantBuilder.build ─► RunTokenStore.issue ─► RuntimeRegistry.get("agent")
                                                                        │  start(RunSpec)
                                                                        ▼
                                         AgentRuntimeProvider (:runtime:agent, in-process, no :wN worker)
                                           ├─ AgentLoop ─► ToolPipeline ─► BrokerTransport ─► Broker interceptor chain ─► providers
                                           ├─ heartbeat coroutine (separate from the loop)
                                           └─ RunBudget · StuckDetector · ObservationFilter · CompletionVerifier · EventLog
```

Existing pieces it reuses **unchanged** (verified in this tree):

| Piece | Where | Used for |
|---|---|---|
| `RuntimeProvider` / `RunHandle` / `RuntimeRegistry` | `runtime/api/src/main/kotlin/app/autohost/runtime/api/` | the provider contract (`type`, `suspend start(RunSpec): RunHandle`, `setDeathListener`; handle: `requestCancel/Pause/Resume`, `kill`, `release`) |
| `FlowRuntimeProvider` pattern | `runtime/flow/.../FlowRuntimeProvider.kt` | in-process run as a coroutine; separate heartbeat coroutine every 2 s; events through `FlowBinderTransport{call, emit}` (`runtime/flow/.../FlowBinderTransport.kt`); `finished` event `{type:"finished", data:{status, reason?, message?, result?{code,data}}}` |
| `RunOrchestrator` | `core/engine/.../orchestrator/RunOrchestrator.kt` | run record, token issue/revoke, heartbeat-loss and idle watchdogs, cooperative cancel then `kill()` after 5 s (`CANCEL_KILL_DELAY_MS`) |
| Broker interceptor chain | `core/broker/.../interceptor/` | Auth, Scope, Grant, InsecureFeature, Provider, Audit — unchanged |
| Perception hardening | `capabilities/a11y/` (ReadGate, TraversalGuard, RootResolver, NodeRegistry, SettlePolicy…) and `core/model/CompactDump.kt` | what the model sees |
| `ui.prompt` | `app/.../prompt/UiPromptProvider.kt`, `PromptActivity.kt` | confirmations and approvals (§9, §10) |

---

## 3. Modules and dependency rules

| Module | Pure JVM | May depend on | Must not depend on | Contains |
|---|---|---|---|---|
| `:core:llm` (new, P13-01) | yes | `:core:common`, kotlinx serialization/coroutines | `:core:data`, `:core:engine`, any Android API, any `:runtime:*` | provider-neutral types, OpenAI-compatible codec, `LlmError`, `BaseUrlValidator`, `RetryPolicy`, `LlmGateway`, `HttpUrlConnectionEngine` (JDK `java.net`, so JVM-testable), seams `LlmHttpEngine`, `SecretReader`, `SecretRegistrar`, `AuditSink` |
| `:runtime:agent` (new, P13-10…15) | yes | `:runtime:api`, `:core:model`, `:core:common`, `:core:llm` | `:runtime:flow/js/python` (sibling runtimes), `:app`, Android | `AgentRuntimeProvider`, `AgentLoop`, `RunBudget`, `StuckDetector`, `AgentView`, `ToolCatalog` (generated), `ToolPipeline`, `AgentPolicy`, `AppSensitivity`, `ObservationFilter`, `CompletionVerifier`, `AgentEventLog`, seam `BrokerTransport` |
| `:app` | no | everything | — | `AgentRunStarter`, `AgentGraph` wiring inside `AppGraph`, `PromptApprovalGate`, the AI settings screens (Phase 14), `HttpUrlConnectionEngine` instantiation, `SecretReader` over `SecretStore` |

Why `:core:llm` may not use `SecretStore`/`Redactor`: both live in `:core:data` (`core/data/.../secrets/SecretStore.kt`, an Android/Room module). `:core:llm` sees only the seams; `:app` implements them.

`BrokerTransport` is the same shape as `FlowBinderTransport` (`call(runToken, capability, method, argsJson): String`, `emit(runToken, eventJson)`). `:runtime:agent` cannot depend on `:runtime:flow`, so P13-12 either duplicates the 2-method interface or lifts it into `:runtime:api` (implementer's choice; record it in the task evidence).

Fakes live in each module's test sources (or `testFixtures`): `FakeLlm` (scripted replies), `FakeBroker`, `FakeClock`, `FakeApprovalGate`. `:testing:fakes` is *planned* in `settings.gradle.kts` but is not a module in this checkout.

---

## 4. Start path (Stage A)

1. **Ask sheet** (`:app`, UI in Phase 14; P13-13 can start with a debug entry point): goal (≤ 2,000 chars), **target apps** (explicit, ≥ 1, chosen by the user from installed apps; default: none), provider profile, optional tighter budgets. The foreground app at Ask time is only a *suggestion*; the user is usually inside AutoHost then.
2. **`AgentRunStarter.start(AgentRunRequest)`** runs **preflight** (§12) and, on success, builds a `StartRequest` for `RunOrchestrator.start` (`core/engine/.../orchestrator/RunOrchestrator.kt:34-47`) with a **synthesized in-memory `TaskManifest`** (below). The orchestrator is not changed for this: it issues the token from `grants.build(manifest, config, profile)` and looks up the provider by `manifest.runtime.type`.
3. `RunOrchestrator` moves the run QUEUED → STARTING and calls `AgentRuntimeProvider.start(spec)`; the provider's heartbeat coroutine flips it to RUNNING (`onHeartbeat`, `RunOrchestrator.kt:242-251`).

### 4.1 The synthesized manifest (every field of `core/model/.../TaskManifest.kt`)

| Field | Value | Why |
|---|---|---|
| `schema` | `1` | |
| `id` | `builtin.agent.adhoc` | internal; the importer must refuse ids starting `builtin.` (small validator rule in P13-13) |
| `name` | `Ask` | the goal text is shown separately in the UI |
| `version` | `1.0.0` | |
| `runtime` | `RuntimeSpec(type = "agent", entry = "agent")` | `entry` is required but unused by the agent |
| `needs.capabilities` | `device.ui.observe`, `device.ui.act`, `device.app.query`, `device.app.launch`, each with scope `{"packages": P}`; `ui.prompt` with `{}` | `GrantBuilder.grant()` accepts **permission ids** as well as capability ids (`GrantBuilder.kt:24-35`). `P` = the user-chosen packages. `ui.prompt` is not implicit, so it must be listed |
| `needs.optional`, `needs.apps` | empty / `AppNeed(p)` for each `p ∈ P` | |
| `execution.uiExclusive` | `true` | one UI run at a time |
| `execution.needsUnlockedScreen` | `true` | |
| `execution.pausable` | `true` | pause is honoured between turns (§11) |
| `execution.timeoutMinutes` | `ceil(max_wall_clock_s / 60)` | **the agent enforces it itself** — see ADR-098 (5): nothing else does until X-16 |
| `execution.idleTimeoutSeconds` | `180` | must exceed the confirm window (120 s) plus a model-call timeout margin; heartbeats are **not** activity (`RunOrchestrator.kt:219-222`), so the loop emits an activity event at every turn and while waiting for the user |
| `execution.maxAttempts` / `autoResume` | `1` / `false` | an interrupted agent run is **never** silently re-run (`autoResume` defaults to `true` in `Execution`, `TaskManifest.kt:61`) |
| `execution.minIntervalMinutes`, `ui`, `configSchema`, `profileSchema`, `usesPacks` | null / empty | |

**Known gap to close in P13-12:** `AppGraph.kt:1358-1369` reads the idle budget from the *database* manifest (`data.tasks.latestVersion(taskId)`) and falls back to 300 s, so a non-persisted manifest silently gets 300 s. The orchestrator already holds the in-memory `StartRequest` (`requests[runId]`); `idleLimitMsFor` must prefer `requests[runId].manifest.execution` (X-16 covers the general fix).

### 4.2 `config.agent` (reserved run-config key; ADR-080 precedent — no AIDL / `RunSpec` change)

```jsonc
{
  "goal": "Open Settings and turn on Do Not Disturb",
  "packages": ["com.android.settings"],          // = scope of the four package-scoped grants
  "provider": "<profileId>",                     // AI provider profile (docs/MODEL_GATEWAY.md §3)
  "model": "<model id>",                         // from the profile; may be overridden
  "supervised": true,                            // Stage A: always true
  "budgets": {                                   // may only TIGHTEN the host ceilings (§7)
    "max_turns": 30, "max_wall_clock_s": 600, "max_model_calls": 40,
    "max_total_tokens": 200000, "max_output_tokens": 1024,
    "max_actions": 60, "max_consecutive_failures": 3
  },                                             // all numbers: placeholder
  "tools": ["ui_snapshot", "ui_click", "…"],     // names from the generated catalog; may only NARROW the default set
  "observation": { "max_nodes": 120, "max_chars": 12000, "screenshots": false },
  "confirm_timeout_s": 120,
  "keep_transcript": false
}
```

Rules: host-written only; for Stage B the manifest `agent{}` block is translated into this key; a task's own `config` property named `agent` is rejected by the validator, and the host payload wins with a logged warning (same rule as `config.packs`, `docs/CONVENTIONS.md`). The record may contain the goal text (user data) but **never a key, token or secret**; `RunSpec` carries the bearer `runToken` and its KDoc says "never contains secrets" — X-13 narrows that wording.

---

## 5. The grant model (read this before anything else)

### 5.1 What the code does today (static, ADR-098)

- `GrantBuilder(index, devMode = true)`; `AppGraph.kt:170` and `:347` pass `devMode = true`. In dev mode `build()` adds **every permission with an empty scope** (`GrantBuilder.kt:45-47`); explicit scopes from `needs` stay (`putIfAbsent`).
- `CallContext.scopePackages(permission)` returns `null` (= unrestricted) for a missing grant, a grant without a `packages` key, an unparsable value, or the literal `"*"`; an empty `{}` grant is therefore **unrestricted** (`core/broker/.../CallContext.kt:24-33`). A `packages: []` grant is the empty set (everything outside is a violation).
- `ScopeInterceptor` only checks a `package` / `packages` / `scope.packages` target **present in the call args**; a restricted grant with no target in the args passes and the provider is expected to enforce at read time (`ScopeInterceptor.kt:18-40`). `A11yUiProvider` does so for **reads** (`requestedPackages` intersects any explicit scope with the granted set, `A11yUiProvider.kt:617-622`).
- Not enforced anywhere: act scope on `ref` targets, `NodeRegistry` run scoping, `allow_coordinates`/`allow_global`, `device.app` scope (ADR-098 (2)–(4)).
- The Dev API's synthetic token is built the same way (`AppGraph.kt:453-467`).

### 5.2 What the agent does about it

1. **Always explicit, scoped grants** (table below). Never "all".
2. **Preflight refuses to start** when any of `device.ui.observe`, `device.ui.act`, `device.app.query`, `device.app.launch` resolves — in the map `GrantBuilder` returns — to a grant with `scopePackages == null`. This works under dev mode too, because explicit scopes survive `putIfAbsent`. Reason code `AI_GRANTS_TOO_BROAD`.
3. The agent's reachable surface is its **generated catalog** (§6) — extra permissions that dev mode adds (shell, raw, sms …) are unreachable from the model. Insecure-feature switches still run in the broker.
4. **Defence in depth in the pipeline** (cheap, consumer-side): the loop keeps `ref → pkg` from its own snapshots (`UiNode.summary` carries `pkg`, `core/model/.../UiNode.kt:43-53`) and refuses an act whose target package is outside the run's packages, so the ADR-098 (2) gap cannot be exercised *by the agent* even before P12-16 lands. It also treats `device.app.foreground` results as an observation (ADR-098 (4)).
5. **Real-device agent gates (G13-c/d) require X-15** so they prove the grant model that ships. JVM and emulator work may proceed under dev mode.

### 5.3 Default Stage-A grants

| Permission | Scope | Notes |
|---|---|---|
| `device.ui.observe` | `{"packages": P}` | observation floor applies on top (§8) |
| `device.ui.act` | `{"packages": P}` — **no** `allow_coordinates`, **no** `allow_global` | the flags are not enforced in code today (ADR-098 (3)); the catalog omits coordinate and global tools |
| `device.app.query` | `{"packages": P}` | `list` is excluded from the catalog |
| `device.app.launch` | `{"packages": P}` | launch by package; the agent never navigates the launcher |
| `ui.prompt` | `{}` | `ask` only |
| implicit | `store.profile, store.task, state.checkpoint, secrets.read, system.info` (`GrantBuilder.IMPLICIT`) | unused by the loop; `secrets.read` is never exposed as a tool |

If the task needs another package (a system permission dialog, Settings), the model sees an empty/blocked screen and the run reports `SCOPE_NOT_GRANTED` with the fix action "Add this app to this run" — the agent **cannot** widen its own scope mid-run (ADR-091 (5)).

---

## 6. Tool catalog (generated, allowlisted, byte-stable)

Generated by P13-09 from `schemas/capability-specs.w1.json` (the canonical spec; `core/broker/.../autohost/capability-specs-w1.json` is a copy kept in sync by `tools/gen_artifacts.py`, which this task extends with `--check` for the catalog). Rules: sorted keys, no timestamps, stable tool names (`device.ui.click` → `ui_click`), a **snapshot test** over the generated JSON, an allowlist (a method not listed is absent), and a per-tool **argument whitelist** (the model never sees `strategy`, `retry_if_no_change`, `secret`). Argument schemas come from the spec's `params`; the model sees descriptions written for it, not the spec text.

| Tool | Maps to | Permission | Spec risk | Policy (Stage A) | Model-visible args |
|---|---|---|---|---|---|
| `ui_snapshot` | `device.ui.snapshot` | observe | low | allow, filtered (§8) | — (host picks scope/compact) |
| `ui_find` | `device.ui.find` | observe | low | allow | `selector`, `limit?` |
| `ui_wait_for` | `device.ui.wait_for` | observe | low | allow | `selector`, `timeout_ms ≤ 10000` |
| `ui_wait_for_settle` | `device.ui.wait_for_settle` | observe | low | allow | `timeout_ms ≤ 5000` |
| `ui_click` | `device.ui.click` | act | low | NORMAL allow · CAUTIOUS ask · BLOCKED deny | `id` (observation id, §7.3) **or** `selector`; `expect?` |
| `ui_long_click` | `device.ui.long_click` | act | low | as `ui_click` | `id` / `selector` |
| `ui_set_text` | `device.ui.set_text` | act | low | NORMAL allow unless the node is a password field (deny) · CAUTIOUS ask · BLOCKED deny | `id` / `selector`, `text`, `verify?` |
| `ui_scroll_until` | `device.ui.scroll_until` | act | low | allow | `selector`, `direction`, `max_swipes ≤ 10` |
| `ui_press` | `device.ui.press` | act | low | `back` only in Stage A (`home`/`recents`/`notifications` need `allow_global`, unenforced today) | `key` ∈ {`back`} |
| `ui_hide_keyboard` | `device.ui.hide_keyboard` | act | low | allow | — |
| `app_launch` | `device.app.launch` | launch | low | allow for `p ∈ P` | `package` (enum of `P`) |
| `app_foreground` | `device.app.foreground` | query | low | allow; result filtered (§8) | — |
| `app_query` | `device.app.query` | query | low | allow for `p ∈ P` | `package` |
| `app_wait_foreground` | `device.app.wait_foreground` | query | low | allow | `package`, `timeout_ms ≤ 15000` |
| `ask_user` | `ui.prompt.ask` | ui.prompt | low | allow; counts as an action | `message`, `kind` ∈ {`text`,`choice`,`confirm`}, `choices?` |
| `finish` | host | — | — | host-verified (§9) | `status` ∈ {`done`,`blocked`,`failed`}, `summary` |
| `todo_write` | host | — | — | allow (a scratchpad kept in context; no side effects) | `items[]` |
| `wait` | host | — | — | allow | `seconds ≤ 10` |

**Excluded by default** (adding one needs the Stage-B allowlist, its insecure switch and per-call approval — ADR-091 (10)): `ui_tap`, `ui_swipe` (coordinates; spec risk *medium*), `ui_dump`, `app_list` (reveals every installed app and is not scope-filtered, ADR-098 (4)), `app_stop` (medium), anything under shell, raw API, sms, telephony, app.manage/install, `secrets.get`, clipboard get, file write, notifications read, net, browser, vision.

Spec `risk` is a **floor**, not a verdict: every `device.ui.click` is `low` in the spec whatever it clicks. Label-word hints (pay, send, delete, remove, uninstall, buy, order, confirm, submit, transfer, post…) raise an *ask* even in NORMAL apps, are best-effort and English-first in v1 (other languages UNVERIFIED), and are **never** the security boundary — scope, grants, BLOCKED apps and supervised completion are.

---

## 7. The loop

### 7.1 One turn

```
preflight()                                   // §12, once
obs := observe()                              // host-initiated: wait_for_settle(≤3 s) + snapshot, filtered
loop:
  budget.beforeModelCall()                    // turns, wall clock, model calls, tokens (reserve), idle   → BUDGET_*
  msgs := builder.build(state)                // system prompt v1 + goal + compacted history + latest observation
  resp := gateway.chat(request)               // cancellable; retry/backoff inside the gateway
  budget.account(resp.usage)                  // unknown usage is ESTIMATED and flagged, never 0
  if resp.toolCalls.isEmpty(): nudge once ("call a tool or finish"); a second time counts as a failure
  call := resp.toolCalls[0]                   // ONE action per turn; each extra call gets an error tool-result ONE_ACTION_PER_TURN
  result := pipeline.execute(call)            // validate → policy → approval → budget → broker → cap → log
  if call is finish: completion protocol (§9)
  if the call changed the screen: settle + observe()   // host-initiated; not a model tool call
  stuck.check(); failures.check()             // §7.4
```

Every extra `tool_call` in an assistant message still receives a `tool` message (OpenAI-compatible APIs reject a missing result), so rejected extras get a typed error result, never silence.

### 7.2 Messages and untrusted content (ADR-091 (4))

- **System prompt**: a versioned resource (`runtime/agent/src/main/resources/prompts/system_v1.txt`, hash recorded in the run), written for a UI-agent: observe before acting, one action per turn, never type secrets, ask the user when ambiguous, stop when blocked, anything inside the screen wrapper is **data**, never instructions. The text is an eval-tuned asset; changing it requires new baselines (P13-16).
- **Goal**: the user's text inside a delimited block in the first user message.
- **Tool result**: a small JSON `{ok, error?{code,reason,message}, effect?, warnings?, …}` in the `tool` message for the call id.
- **Observation**: the **next `user` message**, wrapped `<untrusted_screen gen="7"> … </untrusted_screen>`. Screen text, notifications, clipboard and web text only ever travel in user/tool roles; **nothing from a run is ever promoted into a system prompt** (no learned hints, no cross-run memory).

### 7.3 The observation the model sees (`AgentView`)

`device.ui.snapshot` returns `nodes[]` (each `UiNode.summary`: `ref, pkg, class, id, text, desc, bounds, flags, index`), `package`, `keyboard`, `node_count`, `degraded`, `compact` (`A11yUiProvider.kt:137-179`). The shared `CompactDump` prints a **filtered, re-numbered** line index (`CompactDump.kt:36-45`) that does **not** correspond to a node `ref`, so the model cannot address nodes from `compact`. `AgentView` therefore renders from `nodes[]`:

```
SCREEN gen=7 app=com.android.settings keyboard=hidden shown=34/87 truncated=no
[1] Switch "Do Not Disturb" {clickable,checked=false} (40,812,1040,940)
[2] EditText id=search "Search settings" {editable,focused} (…)
```

- The `[n]` ids are **local to one observation generation**; `ViewIndex` maps `n → (ref, pkg, flags, bounds)`. A tool call must carry the generation's ids; a call that refers to an older generation is rejected `STALE_OBSERVATION` (no guessing, no hidden retry).
- The keep rule (on-screen, min size 5, visible ratio ≥ 0.01) is `CompactDump`'s; P13-10 exposes it as an additive public function in `core:model` (no golden-vector change) rather than duplicating it.
- Caps: `observation.max_nodes` (120), `observation.max_chars` (12,000); password nodes arrive with empty text by construction (`UiTreeBuilder.kt:65-66`, flag `password`) and are rendered `{password}` with no value. `degraded`/`truncated` are shown to the model as facts.
- On `STALE_NODE` the loop re-observes and returns the error with the new observation. It does **not** auto-retry through another channel (ADR-089: "unchanged ⇒ no fall-through").

### 7.4 Stop conditions and detection (ADR-091 (1), (12))

| Condition | Result | Reason |
|---|---|---|
| model `finish` | completion protocol (§9) | — |
| user Stop / kill switch | `CANCELLED` | `USER_CANCELLED` |
| any budget | `FAILED` | `BUDGET_TURNS`, `BUDGET_TIME`, `BUDGET_MODEL_CALLS`, `BUDGET_TOKENS`, `BUDGET_ACTIONS`, `BUDGET_CONTEXT` |
| `max_consecutive_failures` (3) tool/model failures | `FAILED` | `AGENT_FAILURES` |
| 3 consecutive steps with the same **state-aware** observation fingerprint → inject a factual warning; 5, or 3 identical (tool, args, target) actions → stop | `FAILED` | `AGENT_STUCK` |
| unexpected exception | `FAILED` | `AGENT_INTERNAL_ERROR` (class name only) — **not** worker death/INTERRUPTED |

The state-aware fingerprint (the A2 lesson, `docs/ACTION_HARDENING.md`) must include checked/selected/enabled/focused state, not only `index:id:text:desc:bounds` as `SettlePolicy.fingerprint` does.

---

## 8. Observation floor and app sensitivity (ADR-091 (6))

`AppSensitivity` (BLOCKED / CAUTIOUS / NORMAL) is a flat `package → tier` data file adapted from ClosePaw's `app_tiers.json` under ADR-088 (P13-08; `THIRD_PARTY_NOTICES.md` row; India-specific payment/UPI/banking additions only after their package ids are verified from store listings). The bundled BLOCKED list is a **floor**: the user can add apps, never remove.

| Check | Rule |
|---|---|
| Preflight | a target package that is BLOCKED → refuse (`OBSERVATION_BLOCKED`) |
| Every snapshot/find/foreground result | if the foreground package or **any node's `pkg`** is BLOCKED or outside `P`, the observation is **withheld** (the model gets `{withheld: true, reason}`) and act calls toward it are refused |
| Password nodes | empty at source; rendered `{password}` |
| Screenshots | not sent unless the user enables them per provider (default off; Stage A has no screenshot tool) |
| `device.app.foreground` | the real foreground package is returned by the provider even when out of scope → the filter replaces it with `{in_scope:false}` |

---

## 9. Completion is verified by the host (ADR-091 (3))

Stage A (supervised): on `finish`, the loop does **not** end.
1. Take a final observation → evidence file (`paths.evidenceDir`), announced with an `evidence` event (`data.path`, `data.kind` — the contract `RunPersistenceBridge` persists, `app/.../bridge/RunPersistenceBridge.kt:95-107`).
2. Call `ui.prompt.ask` `kind:"confirm"`, `timeout_ms = confirm_timeout_s·1000`, message from a template ("The assistant says it is done: <summary ≤ 120 chars>. Is it?"). Results (verified in `PromptActivity.kt`): `{answered:true, confirmed:true|false}`; timeout `{answered:false, reason:"timeout"}`; dismissed `{…, reason:"dismissed"}`; kill switch/terminal `{…, reason:"cancelled"}`.
3. `confirmed:true` → `SUCCEEDED`; `confirmed:false` → `FAILED:COMPLETION_REJECTED`; timeout/dismissed → `FAILED:COMPLETION_UNVERIFIED`. The model's claimed status is stored as evidence, never as the verdict.
4. While waiting, the runtime emits a `log` event every 15 s so the idle watchdog does not expire the run.

`WAITING_USER` is **not** used in Stage A: it is non-terminal here, `finishRun(WAITING_USER)` does not revoke the token (`RunOrchestrator.kt:305` calls `cleanup` only for terminal states), the idle watchdog still counts a parked run, and "resume" is a new attempt with a new token (ADR-098 (6)).

Stage B: a manifest `verify` clause (selector/state checks evaluated through broker **reads** after `finish`) decides; without `verify` an unattended agent task is refused by the validator.

The prompt text is posted as a heads-up notification by `UiPromptProvider.notifyUser` (`setContentText(prompt.message)`) and can show on the lock screen: prompt messages are short templates; node labels are truncated to 40 chars and omitted for CAUTIOUS apps.

---

## 10. One call path and approvals (ADR-091 (7), (8))

`ToolPipeline.execute(call)` is the **only** way a model call reaches the device:

`validate args against the generated schema → policy(risk, sensitivity, action kind, label hints) → approval (if ask) → budget reserve → BrokerTransport.call → cap what the model sees (12,000 chars; the full result may go to evidence) → log/audit`

Approvals use `ui.prompt.ask` `kind:"choice"`, `choices:["Allow once","Allow for this run","Deny"]`, a per-call `timeout_ms` (default 60 s; `ask` honours it, the `dialog` method does not — it is fixed at 300 s, `UiPromptProvider.kt:132`, so use `ask`). Timeout or dismissal → **deny and stop the run** (`APPROVAL_TIMEOUT`); Deny → the model gets `APPROVAL_DENIED` and may choose another path; no "Always" in Stage A. After an approval, **re-validate** before acting: foreground package unchanged, target still resolves, sensitivity not BLOCKED (TOCTOU). Approval text is built from the node label (truncated), never "node 17".

---

## 11. Lifecycle, cancellation, events

- **`AgentRuntimeProvider.start(spec)`** launches the loop with `scope.launch(dispatchers.default)` exactly like `FlowRuntimeProvider.kt:69-103`, with a **separate heartbeat coroutine** (every 2 s; the process is alive even if a call wedges). It returns an `InProcessAgentHandle`.
- **Cancel**: `requestCancel()` cancels the run job *immediately* — it must interrupt the in-flight HTTP call (`disconnect()`) and the in-flight broker call, not wait for a checkpoint (the flow runtime's cooperative flag is checked only between steps). Target: Stop → `finished(CANCELLED, USER_CANCELLED)` ≤ 2 s on the JVM harness; ≤ 6 s on a real device (G13-a/c). The orchestrator still kills after 5 s (`CANCEL_KILL_DELAY_MS`).
- **`CancellationException` is never swallowed** (ADR-091 (9)). Do **not** copy `FlowRuntimeProvider.kt:93-99`, whose `catch (e: Throwable)` also catches coroutine cancellation and reports worker death (an INTERRUPTED race against the orchestrator's CANCELLED on the kill path — predicted, low severity). Catch `CancellationException` first and rethrow; classify everything else as `AGENT_INTERNAL_ERROR`.
- **Pause** is cooperative at turn boundaries (the in-flight model call finishes; no new call starts; the 2 s heartbeat continues).
- **Death listener**: used only if the provider itself is torn down unexpectedly.
- **Events** (`BrokerEvent{type, ts, level, message, stage, data}`; types are the documented set `log, progress, stage_begin, stage_end, evidence, result, next_run, heartbeat, finished` — no new event type, no schema change). Agent detail rides in `log.data.kind`:

| Event | When | Notes |
|---|---|---|
| `heartbeat` | every 2 s | not activity for the idle watchdog |
| `stage_begin` / `stage_end` | `observe` / `think` / `act` | updates `RunRecord.stage` |
| `progress` | each turn | `data.progress = turn / max_turns` |
| `log` kind=`agent.thought` | model text alongside a call | capsule "thought" line (Phase 14) |
| `log` kind=`agent.action` | before a call | tool name, redacted args summary |
| `log` kind=`agent.result` | after a call | ok/error code/effect |
| `log` kind=`agent.usage` | per model call | tokens (flagged `estimated`), latency, attempt |
| `log` kind=`agent.budget` | at 80 % of any budget and at stop | which budget |
| `log` level=warn kind=`agent.dropped` | when the trace cap drops entries | visible drop counters (ADR-091 (13)) |
| `evidence` | final observation, optional per-turn dumps | `data.path`, `data.kind` |
| `result` | at the end | `{code, summary, claimed_status, verified_by}` |
| `finished` | once | `data = {status, reason?, message?, result?}` |

Anything the orchestrator would treat as activity: every non-heartbeat event and every broker call (via the audit sink) — so each turn emits at least one `progress`/`log`.

---

## 12. Preflight (before the first model call; P13-13)

AI features enabled · provider profile and key present · URL policy passes (`docs/MODEL_GATEWAY.md` §4) · accessibility service connected (`A11yAvailability`) · screen state acceptable (`needs_unlocked_screen`) · every target package installed and **not BLOCKED** · grants non-empty **and package-scoped** (§5.2) · budgets valid (a run with no effective budget refuses to start) · `config.agent` valid · no other UI-exclusive run active. Each failure is a typed error with a fix action (`OPEN_AI_SETTINGS`, `OPEN_ACCESSIBILITY_SETTINGS`, `OPEN_PERMISSION_REVIEW`, …) and **starts no run**.

---

## 13. Data egress and privacy

| Sent to the provider | Not sent |
|---|---|
| goal text; system prompt; tool schemas; filtered observations (class, id, text/desc ≤ 80 chars, flags, bounds, package names); tool results; the user's `ask_user` answers | screenshots (off by default), password text, API keys, run tokens, secrets, clipboard (no tool), notification contents, installed-app list, anything from a BLOCKED app |

First use shows a data-egress disclosure (what is sent, to which host, that it leaves the device). Logs and audit carry **sizes and hashes**, not content. Full transcripts are stored only when `keep_transcript` is on (default off) and then only in the run directory, bounded, redacted, and included in `.aarun` exports only with an explicit user choice.

---

## 14. Failure taxonomy → reason codes

`schemas/errors.json` keeps `brokerErrors`, `reasonCodes` (each with kind/params/userMessage/fixAction), `fixActions`, `runStatuses`. P13-02 adds these through the generator (never by hand):

| Reason | Status | Meaning | Fix action |
|---|---|---|---|
| `AI_DISABLED` | start refused | AI features off | `OPEN_AI_SETTINGS` |
| `AI_NOT_CONFIGURED` | start refused | no provider/key | `OPEN_AI_SETTINGS` |
| `AI_GRANTS_TOO_BROAD` | start refused | package-scoped grant resolved unrestricted (§5.2) | `OPEN_PERMISSION_REVIEW` |
| `OBSERVATION_BLOCKED` | start refused / action refused | BLOCKED app | — |
| `MODEL_AUTH` / `MODEL_RATE_LIMITED` / `MODEL_UNREACHABLE` / `MODEL_BAD_RESPONSE` / `MODEL_CONTEXT_OVERFLOW` / `MODEL_FILTERED` | FAILED | mapped from `LlmError` (`docs/MODEL_GATEWAY.md` §6) | `OPEN_AI_SETTINGS` where applicable |
| `BUDGET_TURNS` / `_TIME` / `_MODEL_CALLS` / `_TOKENS` / `_ACTIONS` / `_CONTEXT` | FAILED | a budget was hit | — |
| `AGENT_FAILURES`, `AGENT_STUCK`, `AGENT_INTERNAL_ERROR` | FAILED | §7.4 | — |
| `APPROVAL_DENIED` | tool error | the user denied one action | — |
| `APPROVAL_TIMEOUT` | FAILED | no answer in time → stop | — |
| `COMPLETION_REJECTED` / `COMPLETION_UNVERIFIED` | FAILED | §9 | — |
| `STALE_OBSERVATION`, `ONE_ACTION_PER_TURN`, `TOOL_NOT_ALLOWED` | tool error (returned to the model) | pipeline validation | — |
| `SCOPE_NOT_GRANTED` | tool error | existing reason (`ScopeInterceptor`) | `OPEN_PERMISSION_REVIEW` |

---

## 14a. Test strategy (what proves each claim)

| Layer | What | Where |
|---|---|---|
| Pure JVM | loop, budgets, stuck detector, policy table, catalog snapshot, AgentView, compaction, URL policy, retry, codec golden vectors | `:core:llm` / `:runtime:agent` tests with `FakeLlm` (a JSON "script": per turn the assistant message to return) |
| JVM integration | real `RunOrchestrator` + `BrokerPipeline` + fake provider + `FakeLlm`: each budget class stops a runaway; cancel mid-call ≤ 2 s; no secret/token in events, audit, evidence | G13-a |
| Emulator | fixture app with injection text and a BLOCKED-package fixture; the PC `testing/stub-model/stub_model.py` over `adb reverse` (loopback, `ai_cleartext_loopback`) | G13-b, G13-d |
| Real device | cloud-model 5-step run, Stop ≤ 6 s; the supervised confirm path | G13-c (needs X-15) |
| Eval | `testing/agent-evals` scenarios against the fixture app; baselines recorded, regressions fail | G13-e |
| Canary | secret + token canaries absent from every artifact | G13-f |

Never count a scripted-model run as a real model; never tick a `(real device)` item without a `device:` evidence clause (ADR-087).

---

## 15. Stage B sketch (not built in Stage A)

`task.json` gains `runtime.type: "agent"` and an `agent` block (draft in `drafts/agent.schema.draft.json`): `goal` (templated from `config`), `tools` (allowlist ⊆ catalog), `budgets`, `verify` (required for unattended), `supervised`. Requires: `schemas/task.schema.json` + `ManifestValidator` + `TaskManifest` fields + migrations + regeneration (P13-18), a per-task user grant at install ("this task may use the assistant on <apps>"), the same preflight, and the G13 gates passing on a real device. Scheduling an agent task additionally requires declared budgets, `verify`, and a visible notification with Stop (AGENTS §3.7).

---

## 16. Assumptions and unknowns (do not treat as facts)

- All budget numbers, the 120 s confirm window, the 12,000-char cap and `max_nodes` are **placeholders**.
- That non-OpenAI models are reachable through OpenAI-compatible endpoints is **UNVERIFIED** per provider.
- Token estimation by characters (≈ ÷ 4) is crude; usage returned by the endpoint wins when present.
- Observation size for a typical app screen on the owner's devices is **unmeasured**; Phase 13's eval records it.
- The English label-word hints are not a security control; localisation is out of scope for v1.
- Whether a snapshot can contain nodes of several packages when the grant is restricted is not exercised by any test read; the observation filter checks per-node `pkg` regardless.

---

## 17. Task map (authoritative list: `docs/PROGRESS.md` Phase 13)

| ID | Delivers | Sections |
|---|---|---|
| P13-01 | `:core:llm` scaffold, types, codec, `LlmError` | MODEL_GATEWAY §2–§6 |
| P13-02 | reason codes via generator | §14 |
| P13-03 / P13-04 | `BaseUrlValidator` + `ai_cleartext_loopback`; `RetryPolicy` | MODEL_GATEWAY §4, §5 |
| P13-05 | provider profiles, host-held keys, `SecretsProvider` stub replaced/bridged | MODEL_GATEWAY §3 |
| P13-06 / P13-07 | `HttpUrlConnectionEngine` + fake server + stub model; `LlmGateway` | MODEL_GATEWAY §7 |
| P13-08 | `AppSensitivity` + `ObservationFilter` | §8 |
| P13-09 | tool catalog generator | §6 |
| P13-10 | `AgentLoop`, `RunBudget`, stuck detector, `AgentView`, compaction | §7 |
| P13-11 | `ToolPipeline`, policy, approvals | §10 |
| P13-12 | `AgentRuntimeProvider`, registration, synthesized manifest, idle/timeout wiring | §3, §4, §11 |
| P13-13 | preflight + `AgentRunStarter` | §4, §5, §12 |
| P13-14 | completion verifier + supervised confirm | §9 |
| P13-15 | event log, redaction, trace bounds, `.aarun` section | §11, §13 |
| P13-16 | eval harness + baselines | §14a |
| P13-17 | security suite | §5, §8, §10 |
| P13-18 | Stage B manifest block | §15 |
| P13-19 | USER_GUIDE "Ask" section | §13 |
