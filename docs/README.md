# docs/ index
| File | Purpose | Updated by |
|---|---|---|
| `PROGRESS.md` | Tickable tracker: status, weighted %, evidence, verification ladder | agent, every task |
| `GATES.md` | Exact procedure + evidence for every gate (Gates 0–16) and the Epoch V exit | planner; agent may refine steps |
| `REPO_LAYOUT.md` | File-level layout: modules, packages, resources, on-device paths (v4: planned modules marked) | planner; agent updates with an ADR |
| `ANDROID_MANIFEST.md` | Manifest, permissions per flavor, services, XML | planner; agent on change |
| `UI_SPEC.md` | Screens, navigation, tokens, UI state, copy (v4: Phase 14 screens planned) | planner; agent on change |
| `DEVAPI.md` | Dev API JSON-RPC methods + MCP tools | planner; agent on change |
| `CONVENTIONS.md` | Standards | planner |
| `FIXTURE_APP.md` | Test app contract (v4: Phase 12/13 surfaces planned) | agent keeps in sync with code |
| `REFERENCES.md` | Lessons from reference projects, licence rules | planner |
| `REUSE_MATRIX.md` | **v4** File-level reuse/adaptation decisions over ClosePaw, opendroid, android-agent, sannabotapp, ARIA; adaptation rules | planner |
| `ACTION_HARDENING.md` | **v4** Findings A1–A9 on the action layer, `effect` vocabulary, conformance test, Phase 12 procedure | planner |
| `AGENT_RUNTIME.md` | **v4** Optional host-owned agent runtime: loop, grants, safety envelope, Stage A/B, tests, task map | planner |
| `MODEL_GATEWAY.md` | **v4** The host's model client (`:core:llm`): wire format, URL policy, retry, keys, errors | planner |
| `LANGUAGE_WEIGHT.md` | **v4** Kotlin/Python/C/JS weight on Android, measured line counts, options, what to measure | planner |
| `LOCAL_INFERENCE.md` | **v4** Optional on-device models: NO-GO until S6; design; S6 criteria | planner |
| `PROACTIVE-CRASH-REVIEW.md` | Startup and resource-loading crash review from the crash-fix pass (Session 20); the fixes are tracked by X-17, X-18 and `../CRASH_FIX_PLAN.md` | agent (crash-fix pass) |
| `PLAN_MERGE.md` | **v4+** The 2026-11-03 merge pass: which planning pack won and why, what was ported from the other, what was left out, checks run | planner |
| `MIGRATION.md` | **v4** What this planning pass changed versus the code the owner attached; deviations | planner |
| `VALIDATION_REPORT.md` | **v4** Checks run vs not run, with exact results | planner |
| `DECISIONS.md` | ADRs + open decisions (ADR-086…098 are v4) | agent + planner |
| `VERSIONS.md` | Pinned versions and compatibility notes (v4 notes appended) | agent |
| `RELEASE.md` | Release steps, signing, licence CI, hardening checklist | agent |
| `USER_GUIDE.md` | End-user guide: install → first task → troubleshooting | agent |
| `OEM_MATRIX.md` | Vendor battery-killer matrix and the always-on recipe | agent |
| `PORT_EXERCISE.md` | The P11-12 port exercise and its gap log | agent |
| `SDK_JS.md` | JavaScript SDK notes for the third runtime | agent |
| `DEVICES.md` | Devices, verification matrix (v4: owner baseline, new rows) | agent (with user input) |
| `SPIKES.md` | Phase 0/10 results, v4 spikes S6 and S7 | agent |
| `PERF.md` | Latency numbers; v4 footprint and frame-time harness definitions | agent |
Other: `../reference/` (findings and verbatim Apache-2.0 snapshots of reference projects — **never on a Gradle source set**), `../drafts/` (unpromoted drafts: the Stage-B `agent{}` schema and a CI workflow draft), `../archive/` (the second planning pack, verbatim — **historical, not instructions**; see `PLAN_MERGE.md`).
**Returning agent, read order (2026-11-03):** `../HANDOFF.md` §0 (state, assignment queue, Windows commands) → `../AGENTS.md` → `PROGRESS.md` (Epoch V first: X-17, X-18, then X-07…X-09) → `../CRASH_FIX_PLAN.md` (Checkpoints A and B) → `../plan.md` §7.11, §14.7, §23, Phases 12–16 → `REUSE_MATRIX.md` §0 before touching any adapted file. What the merge pass changed: `PLAN_MERGE.md`.
Generated (do not hand-edit; created in Phase 5): `CAPABILITIES.md`, `SDK.md`, `capabilities.json`, the Skill-format agent pack.
