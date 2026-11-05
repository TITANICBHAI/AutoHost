# WORK_SEQUENCE_PROMPT — the remaining code, sequenced (2026-11-05)

> **This file is the prompt for the next working session(s).** It arranges every remaining piece
> of code into a dependency-ordered sequence and is written to be executed **in writing-first
> mode**: **NO git commits/pushes, NO GitHub Actions, NO test execution of any kind.** The only
> verification allowed is static file checking (reading code/tests; `progress.py --check`,
> `doc_check.py`, `licence_check.py`, the architecture scanner, `ast.parse`/brace sanity).
> Every rule here defers to `AGENTS.md` §3 and `docs/AGENT_OPERATING_PROTOCOL.md`.

## 0. Mode rules (owner instructions, 2026-11-04/05 — bind every session on this file)

1. **No git and no verification runs** until the owner explicitly says otherwise again. Writing
   only: code, tests, resources, docs — verified by the static recheck loop.
2. **Recheck loop before calling anything done** (owner rule): re-verify everything touched
   (symbol grep, brace/paren sanity, YAML/XML well-formedness, exact-match edit counts); on any
   error fix it and REPEAT the whole recheck; loop until a round finds zero problems.
3. **Batch the work** in the stage order below; after each stage: `progress.py --check`,
   `doc_check.py`, `licence_check.py`, tracker rows marked `(WRITTEN — D-26)` with static
   evidence, `AGENT_STATE.md` updated. Nothing committed.
4. **Failing-test-first still applies in writing**: for every predicted defect (A1–A9, §5.1
   facts), write the red test AND the fix in the same stage; the red state is asserted by the
   test's own design (a comment names which run will first execute it).
5. **Read before writing**: for each stage, read the docs in its "Read first" line IN FULL
   before touching code.
6. **Skipped items stay skipped**: P12-13, P13-18, P13-20, P15-09, Phase 16, X-18, X-22
   (D-24/D-25/D-23) — annotated in the tracker; do not write them.
7. Generators (`gen_artifacts.py`, `gen_tool_catalog.py --check`, `sync_sdk.py --check`,
   golden-vector regens) may run: they are static code-generation, not verification.
8. The work is tracked in `docs/PROGRESS.md` rows marked `(WRITTEN — D-26)`; `AGENT_STATE.md`
   carries the current stage pointer.

## 1. Read order (every session on this file)

1. `AGENTS.md` → `AGENT_OPERATING_PROTOCOL.md` → this file → `AGENT_STATE.md`.
2. `HANDOFF.md` §0 (background), `docs/PROGRESS.md` Phase 12–15 + X rows (the authoritative states).
3. Per stage: the docs named in that stage's "Read first" line.
4. `docs/REUSE_MATRIX.md` §0 (adaptation rules) before any RM-* adaptation; `schemas/errors.json`
   and `schemas/capability-specs.w1.json` as the wire sources of truth.
5. `worklog.md` last two sessions only for unresolved-attempt context.

## 2. The sequence

### Stage W1 — foundations the agent and the action layer depend on

| # | Task | What to write | Read first | Done when |
|---|---|---|---|---|
| W1.1 | **X-16** | Wall-clock `timeout_minutes` enforced for EVERY runtime in `RunOrchestrator` (`FAILED:TIMEOUT`); idle budget read from the in-memory `requests[runId].manifest` (fixes the §4.1 gap: `idleLimitMsFor` currently falls back to 300 s for non-persisted manifests); `FlowRuntimeProvider` cancellation-as-death fix (catch `CancellationException` first, rethrow — AGENT_RUNTIME §11 names the lines); idle watchdog counts only non-heartbeat activity | ADR-098, `RunOrchestrator.kt`, `FlowRuntimeProvider.kt`, AGENT_RUNTIME §4.1/§11 | watchdog unit tests (fake clock) + the flow fix + tracker row |
| W1.2 | **X-15** | `devMode = false` **by default** (AppGraph:170/:347 currently `true` — the §5.1 fact); persisted user-approved grants from `GrantService`; a test proving a run holds ONLY its declared/approved permissions; the Dev API token keeps its documented restricted grants | ADR-098, `GrantBuilder.kt`, `AppGraph.kt:170/:347`, AGENT_RUNTIME §5 | grant-closure tests + tracker row; the agent's preflight (W3) asserts against the shipped model |

### Stage W2 — the hardened action layer (Phase 12; the agent leans on it)

| # | Task | What to write | Read first | Done when |
|---|---|---|---|---|
| W2.1 | **P12-02** | Additive `effect: changed\|unchanged\|unverifiable` + `warnings: [string]` on `device.ui` action results; spec change through `gen_artifacts.py` regeneration (SDK pyi, `_capabilities_gen.py`, simulator mirror, golden vectors); `verified` stays "accepted" until W2.2 | ACTION_HARDENING §2, `schemas/capability-specs.w1.json`, `ReturnShape.kt` additive list retired | provider emits effect; regeneration green; `ProviderConformanceTest` additive list shrinks |
| W2.2 | **P12-03/04** | State-aware fingerprint (adds checked/selected/enabled/focused to `SettlePolicy.fingerprint`) + three-valued pure `ChangeDetector` (adapt RM-A4); `click`/`long_click` effect verification: settle → compare → `retry_if_no_change` semantics → **no fall-through after `unchanged`** (A2) | ACTION_HARDENING A2, RM-A4, `SettlePolicy.kt`, `A11yUiProvider.kt` | red test (toggle changes only `checked`) + fix; ChangeDetector table tests |
| W2.3 | **P12-05** | `set_text`: refresh-then-verify (perform → **refresh()** → read — the A1 order), cursor/hint handling, no doubled text when the chain falls through (RM-A1) | ACTION_HARDENING A1, RM-A1, `SetTextChain.kt` | JVM test through the NodeHandle seam that fails unless perform→refresh→read |
| W2.4 | **P12-06** | Awaited gesture injection: callback + timeout (5 s) + `dispatched == false` → typed failure + `ViewConfiguration` timings (A3; RM-A2) | ACTION_HARDENING A3, RM-A2, `Actions.kt:81-101` | fake-dispatcher tests: never-fires → timeout; false → failure; `onCancelled` → failure |
| W2.5 | **P12-09/10/11** | Hotspot promotion (container → smallest actionable child, ambiguity guard — RM-A3); IME-Enter through `ApiGate` (API 30) with click fallback; node identity guard before every node action (bounds/id/class/label — replaces the 8-hop ancestor walk; stale/relabelled → `STALE_NODE`, never a wrong-node click) | ACTION_HARDENING A5, RM-A3/RM-A1, `Actions.kt` | HotspotRefiner table tests; identity-guard tests |
| W2.6 | **P12-08** | `ScrollPlanner(display, scrollableBounds, direction)` pure table tests for 720×1600 / 1080×2340 / 1080×2400; accessibility scroll first, gesture INSIDE the scrollable's bounds; real display metrics via `ApiGate` (API 31 `maximumWindowMetrics`, else `getRealMetrics`) — replaces the 1080×2400 defaults (A4) | ACTION_HARDENING A4, RM-A7, `A11yUiProvider.kt:67-68/521-529` | planner table tests pass; provider consumes the planner |
| W2.7 | **P12-07/12/14** | Overlay window-flag audit (bubble/capsule/glow/recorder — a touch gate only if a test shows swallowing); fixture-app surfaces for A1–A5 (plain field, toggle, 700 ms transition, silent-failure field, large container, nested scrollable, slow-callback gesture target) + `docs/FIXTURE_APP.md`; port the RM-G2 upstream unit tests for every adapted unit | ACTION_HARDENING §5, `docs/FIXTURE_APP.md`, RM-G2 | fixture layouts + docs; ported tests in the same modules |

### Stage W3 — the agent runtime core (Phase 13; `:runtime:agent` is scaffolded with P13-08/09 done)

| # | Task | What to write | Read first | Done when |
|---|---|---|---|---|
| W3.1 | **P13-10** | `RunBudget` (turns/wall-clock/model-calls/tokens-reserve/actions/idle → the 6 `BUDGET_*` reasons; reserve prompt-estimate + max_output before dispatch), `StuckDetector` (RM-C2 adapted: 3 same state-aware fingerprints → factual warning; 5, or 3 identical (tool,args,target) → `AGENT_STUCK`), `AgentView` (renders `nodes[]` as `[n]` generation-local lines + `ViewIndex` n→(ref,pkg,flags,bounds); old-gen id → `STALE_OBSERVATION`), `AgentLoop` (the §7.1 turn pseudocode verbatim; extra tool_calls get `ONE_ACTION_PER_TURN` error results; `finish` → completion protocol), stale-output compaction; expose CompactDump's keep rule additively in `core:model` | AGENT_RUNTIME §7 (+ §7.2 messages/untrusted wrapper), `CompactDump.kt`, RM-C2 | loop/budget/stuck/view unit tests with `FakeLlm` (a scripted per-turn assistant) |
| W3.2 | **P13-11** | `ToolPipeline.execute(call)`: validate against the generated arg whitelist → policy → approval → budget reserve → `BrokerTransport.call` → 12k-char cap → log; `ToolPolicy` (RM-B2 structure: (spec-risk floor, sensitivity, action kind, label hints) → allow/ask/deny; label-word hints never the boundary); approvals via `ui.prompt.ask` choice + TOCTOU re-validation (foreground unchanged, target resolves, not BLOCKED) | AGENT_RUNTIME §10, RM-B2, `UiPromptProvider.kt` | pipeline/policy table tests; approval timeout → deny path |
| W3.3 | **P13-12** | `AgentRuntimeProvider` (in-process `scope.launch` like FlowRuntimeProvider; separate 2 s heartbeat), `InProcessAgentHandle` (requestCancel cancels the job immediately — interrupts the in-flight HTTP + broker call; `CancellationException` rethrown, never swallowed — do NOT copy FlowRuntimeProvider's catch-Throwable), synthesized manifest (§4.1 field-by-field: `builtin.agent.adhoc`, `autoResume=false`, `uiExclusive`, idle 180 s, timeout = ceil(max_wall_clock_s/60)), `BrokerTransport` seam (duplicate or lift `FlowBinderTransport` into `:runtime:api` — RECORD the choice), AppGraph registration beside flow, idle-budget wiring preferring `requests[runId]` | AGENT_RUNTIME §3/§4/§11, `FlowRuntimeProvider.kt`, `RunOrchestrator.kt:34-47/242-251` | provider/handle tests; the manifest asserts every §4.1 field |
| W3.4 | **P13-13** | Preflight (§12: AI enabled → profile+key → URL → a11y connected → screen state → targets installed + not BLOCKED → grants package-scoped (`AI_GRANTS_TOO_BROAD` under dev mode too — explicit scopes survive putIfAbsent) → budgets valid → `config.agent` valid → no other UI-exclusive run) + `AgentRunStarter` (synthesized StartRequest) + `builtin.`-id importer rule | AGENT_RUNTIME §4/§5/§12, `GrantBuilder.kt` | preflight table tests: each §12 failure → typed error + fix action + no run |
| W3.5 | **P13-14** | Completion verifier: `finish` → final observation → evidence event (`data.path/kind`) → `ui.prompt.ask` confirm (120 s; template with summary ≤ 120 chars) → confirmed → SUCCEEDED / false → `COMPLETION_REJECTED` / timeout-dismissed → `COMPLETION_UNVERIFIED`; 15 s log events while waiting; claimed status is evidence, never the verdict | AGENT_RUNTIME §9, `PromptActivity.kt` result shapes | verifier tests: confirmed/rejected/timeout paths |
| W3.6 | **P13-15** | `AgentEventLog` (§11 table: thought/action/result/usage/budget/dropped kinds on `log.data.kind`; stage_begin/end observe-think-act; progress turn/max), redaction, trace bounds with VISIBLE drop counters (level=warn `agent.dropped`), `.aarun` agent section shape | AGENT_RUNTIME §11/§13, `RunPersistenceBridge.kt:95-107` | event-log tests incl. drop counters; `.aarun` section writer |

### Stage W4 — agent QA (Phase 13 QA halves)

| # | Task | What to write | Read first | Done when |
|---|---|---|---|---|
| W4.1 | **P13-16** | `testing/agent-evals/` scenarios with `expect` clauses + recorded baselines (JVM-runnable with `FakeLlm`; the fixture-app runs are G13-e's later half) | AGENT_RUNTIME §14a | eval runner + ≥ 3 scenarios + baselines file |
| W4.2 | **P13-17** | Security suite: injection fixtures (screen text tries to widen grants), masking, budget stop for every class, cancel mid-call, approval timeout, secret+token canaries across events/audit/evidence/`.aarun` | AGENT_RUNTIME §5/§8/§10/§13 | suite green-by-design; every canary asserted |
| W4.3 | **P13-19** | USER_GUIDE "Ask" section + the data-egress disclosure text (what is sent/not sent — §13 table) | AGENT_RUNTIME §13 | guide section |

### Stage W5 — run UI (Phase 14)

| # | Task | What to write | Read first | Done when |
|---|---|---|---|---|
| W5.1 | **P14-04** | Agent run detail: thought → action timeline from the W3.6 event kinds, budgets/usage panel, host verdict (confirmed/rejected/unverified) | UI_SPEC §10 S30, AGENT_RUNTIME §11 | state holder + tests + composable |
| W5.2 | **P14-05** | `.aarun` agent-section viewer + export redaction (rides P13-15's section) | UI_SPEC §10 S31 | viewer state + tests |
| W5.3 | **P14-06** | AI settings: master switch (off), provider profiles CRUD, key entry (never shown after save), egress disclosure on first enable, test connection, per-provider screenshot switch (off), keep-transcripts (off), loopback switch under advanced | UI_SPEC §10 S32, MODEL_GATEWAY §3 | state holder + tests + screen |
| W5.4 | **P14-07** | Ask bar/sheet: goal ≤ 2,000 chars, explicit target apps (required ≥ 1), provider, optional tighter budgets, supervised default; refuses without the master switch/preflight | UI_SPEC §10 S33, AGENT_RUNTIME §4/§12 | state holder + tests + sheet |
| W5.5 | **P14-08** | Optional glow/action visualizer, default OFF | UI_SPEC §10 | default-off stub + test |

### Stage W6 — closure (X-items + regeneration)

| # | Task | What to write | Read first | Done when |
|---|---|---|---|---|
| W6.1 | **P12-15/16/17** | Regeneration + USER_GUIDE note on `effect`/`retry_if_no_change`; a11y scope: run id into `NodeRegistry.put/resolve`, act-scope on ref targets, `allow_coordinates`/`allow_global` enforced; `device.app` `list`/`foreground` filtered by granted packages | ADR-098, ACTION_HARDENING A7–A9 | scope-closure tests prove a run cannot act/list outside its grants |
| W6.2 | **X-20** | Idle-cost guard unit-test half (no tree work, no `:w*`, no interpreter while idle — the phone CPU sample is D-25-skipped) | ADR-100 | unit test |
| W6.3 | **X-12 Kotlin half** | `x-secret` valid ONLY under `profile_schema` (split the shared `$defs/objectSchema` in task.schema.json; `config_schema` rejects it); boundary tests: no plaintext secrets in RunSpec snapshots/checkpoints/events/history/exports; RM-D8 note | ADR-099, schemas/task.schema.json | schema split + negative tests |
| W6.4 | **X-14** | NSC/manifest reconciliation — the DOC route (emulator testing D-24/D-25-skipped): either add `network_security_config.xml` with base deny + loopback allow (G13-d needs it later) or correct the documents; record which and why | ADR-096, ANDROID_MANIFEST §2/§5 | docs/manifest consistent |

## 3. Frozen, do not write

`P12-13` (emulator, D-24/D-25) · `P13-18` (Stage B — needs real-device gates) · `P13-20` (device halves) · `P15-09`, `G15-a/d` · Phase 16 (permanent NO-GO) · `X-18` · `X-22` (D-23) · all `G*` device/emulator halves.

## 4. Completion and handoff

- After each stage: tracker rows `(WRITTEN — D-26)` + static evidence; `AGENT_STATE.md` stage pointer; `worklog.md` session entry.
- When ALL stages are done: the code-written metric is 100% of the planned work; verification
  (compile, tests, CI) resumes when the owner says so — the prompt for THAT is the owner's, not
  this file's.
- Report format per batch: *Done* (ids + static evidence) / *Blocked* / *Needs owner* (≤ 1) /
  *Files touched* / *Checks* (progress/doc/licence OK).
