# Phase 14 — Run UI: capsule, approvals, agent run detail (kickoff prompt, v4)

Read `AGENTS.md`, `plan.md` (§18 Phase 14), `docs/UI_SPEC.md` (§10), `docs/REUSE_MATRIX.md` (§F), `docs/AGENT_RUNTIME.md` (§9–§11), `docs/SPIKES.md` (S7), `docs/PERF.md` (§5), `docs/GATES.md` (Gate 14).

## Goal
ClosePaw's *structure*, AutoHost's identity (ADR-094): design tokens in `core/designsystem`, one `RunCapsule` + one state holder (in-app bar and overlay), approval sheet with notification actions, agent run detail (thought → action timeline, budgets/usage), AI settings with the data-egress disclosure, the Ask bar, optional glow/visualizer (default off), accessibility and frame-time passes, spike S7.

## Tasks (from `docs/PROGRESS.md` Phase 14)
- **P14-01** Design tokens (4 dp grid, radii, motion, `reducedMotion`) in `core/designsystem` [M]
- **P14-02** `RunCapsule` + `RunCapsuleState` holder, in-app bar and overlay; Stop always visible; "Stopping…" immediate [L]
- **P14-03** Approval sheet and notification actions (Allow once / Allow for this run / Deny; timeout → deny + stop) [M]
- **P14-04** Agent run detail: thought → action timeline, budgets and usage panel [M]
- **P14-05** `.aarun` agent section viewer + export redaction (with P13-15) [S]
- **P14-06** AI settings: enable AI features, providers, key entry, data-egress disclosure, test connection [M]
- **P14-07** "Ask" bar/sheet: goal, explicit target apps, provider, tighter budgets; supervised by default [M]
- **P14-08** Optional edge glow and action visualizer, default off [S]
- **P14-09** Accessibility and usability pass (TalkBack, reduced motion, touch targets), frame-time on the Realme C3, spike S7 (overlays vs screenshots and `FLAG_SECURE`) [M]

## Constraints
- Take ClosePaw's *structure*, not its identity or code (RM-F3 is a rewrite); one `RunCapsule` and one state holder; Stop is always visible; the glow is default-off and removed if S7 shows jank or touch loss.
- **Start only after X-09** (Epoch V exit review, ADR-097); Python-only and tooling items may start earlier.
- Tag and tick a tracker item only at the level it really proved (`(WRITTEN)` → `(COMPILED)` → `(UNIT-TESTED)` → `(INTEGRATION-TESTED)` → ticked); `(real device)` items need a `device: <model>, Android <n> / API <n>` evidence clause; emulator and simulator runs are never real-device (ADR-087).
- Predicted defects get a failing test first; if one does not reproduce, record that and keep the test (ADR-089).
- Carry forward `AGENTS.md` §3–§4: fail closed, no app-specific code outside `samples/` and `testing/`, `SDK_INT` only in `ApiGate`/`availability()`, one broker path, secrets only in `SecretStore`, no hand edits to generated files.
- Adapted code follows `docs/REUSE_MATRIX.md` §0 (header, notice row, ported test, ladder evidence); only ClosePaw and opendroid files are eligible.

## Done when
Gate 14 items G14-a … G14-d are ticked with evidence (*real device*: G14-c); `python3 tools/progress.py --check` and `python3 tools/doc_check.py` pass; deviations are reported in `docs/DECISIONS.md`.
