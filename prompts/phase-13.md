# Phase 13 — Agent runtime, Stage A, and the model gateway (kickoff prompt, v4)

Read `AGENTS.md` (rules 10, 12–15), `plan.md` (§7.11, §14.7, §18 Phase 13), `docs/AGENT_RUNTIME.md` (all), `docs/MODEL_GATEWAY.md` (all), `docs/REUSE_MATRIX.md` (§0, §B–§D, §G), `docs/CONVENTIONS.md` (v4 conventions), `docs/GATES.md` (Gate 13), `drafts/agent.schema.draft.json`, `schemas/capability-specs.w1.json`.

## Goal
`:core:llm` (provider-neutral types, OpenAI-compatible non-streaming codec, URL policy, retry, host-held keys), `:runtime:agent` (loop, budgets, stuck detector, `AgentView`, tool catalog generated from the specs, tool pipeline with policy and approvals, host-verified completion, event log and `.aarun` section), `AgentRuntimeProvider` registered beside the flow runtime, preflight, eval harness, security suite; Stage B manifest block last and only after the real-device gates. Details: `docs/AGENT_RUNTIME.md`, `docs/MODEL_GATEWAY.md`.

## Tasks (from `docs/PROGRESS.md` Phase 13)
- **P13-01** `:core:llm` module scaffold: provider-neutral types, OpenAI-compatible codec, `LlmError` [M]
- **P13-02** Reason codes (`BUDGET_*`, `AGENT_STUCK`, `COMPLETION_*`, `APPROVAL_*`, `OBSERVATION_BLOCKED`, `MODEL_*`, `AI_*`) added to `schemas/errors.json` through the generator [S]
- **P13-03** `BaseUrlValidator` (adapt RM-D1) + `ai_cleartext_loopback` insecure-feature switch [S]
- **P13-04** Deadline-aware pure `RetryPolicy` (3 attempts, jitter seam, `Retry-After` cap 30 s) [S]
- **P13-05** Provider profiles, host-held keys under the reserved host scope, `SecretsProvider` stub replaced or bridged [M]
- **P13-06** `HttpUrlConnectionEngine`, fake-server tests, PC `testing/stub-model/stub_model.py` [M]
- **P13-07** `LlmGateway` (validate URL every call, key at call time, retry, usage, no-implicit-fallback) [M]
- **P13-08** `AppSensitivity` + `ObservationFilter` (adapt RM-B1; BLOCKED floor) [M]
- **P13-09** Tool-catalog generator from `capability-specs.w1.json` (byte-stable allowlist, `--check`) [M]
- **P13-10** `AgentLoop`, `RunBudget`, `StuckDetector` (adapt RM-C2), `AgentView`, stale-output compaction [L]
- **P13-11** `ToolPipeline`, `ToolPolicy` (adapt RM-B2), approvals via `ui.prompt.ask` [L]
- **P13-12** `AgentRuntimeProvider`, `AppGraph` registration, synthesized manifest, idle/timeout wiring [L]
- **P13-13** Preflight and `AgentRunStarter` (refuses unrestricted package grants, AI disabled, no budget, BLOCKED target) [M]
- **P13-14** Host-verified completion: final observation, supervised confirm prompt, `COMPLETION_*` reasons [M]
- **P13-15** Agent event log, redaction, trace bounds with visible drop counters, `.aarun` agent section [M]
- **P13-16** Eval harness `testing/agent-evals` + baselines against the fixture app [M]
- **P13-17** Agent security suite (injection fixtures, masking, budgets, cancel, approval timeout, canaries) [L]
- **P13-18** Stage B: `runtime.type: "agent"` + `agent{}` manifest block, validator, migration, samples — only after G13-a…f pass on a real device [M]
- **P13-19** USER_GUIDE "Ask" section and the data-egress disclosure text [S]

## Constraints
- The agent is **optional and off by default**; the model only proposes catalog tool calls through the same broker — it never writes code and there is no second action path.
- `:core:llm` and `:runtime:agent` stay pure JVM (no `android.util.Log`, no `org.json`); keys only in `SecretStore`; https only (loopback http only behind the switch); every budget number is a placeholder until the eval harness tunes it.
- Stage A is an internal, supervised "Ask" run; Stage B (P13-18) starts only after G13-a…f pass on a real device; real-device gates G13-c/d need X-15 and X-14.
- **Start only after X-09** (Epoch V exit review, ADR-097); Python-only and tooling items may start earlier.
- Tag and tick a tracker item only at the level it really proved (`(WRITTEN)` → `(COMPILED)` → `(UNIT-TESTED)` → `(INTEGRATION-TESTED)` → ticked); `(real device)` items need a `device: <model>, Android <n> / API <n>` evidence clause; emulator and simulator runs are never real-device (ADR-087).
- Predicted defects get a failing test first; if one does not reproduce, record that and keep the test (ADR-089).
- Carry forward `AGENTS.md` §3–§4: fail closed, no app-specific code outside `samples/` and `testing/`, `SDK_INT` only in `ApiGate`/`availability()`, one broker path, secrets only in `SecretStore`, no hand edits to generated files.
- Adapted code follows `docs/REUSE_MATRIX.md` §0 (header, notice row, ported test, ladder evidence); only ClosePaw and opendroid files are eligible.

## Done when
Gate 13 items G13-a … G13-f are ticked with evidence (*real device*: G13-c, G13-d); `python3 tools/progress.py --check` and `python3 tools/doc_check.py` pass; deviations are reported in `docs/DECISIONS.md`.
