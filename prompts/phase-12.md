# Phase 12 — Hardened actions and scope closure (kickoff prompt, v4)

Read `AGENTS.md` (rules 10, 12–15), `plan.md` (§3.5, §5.7, §18 Phase 12), `docs/ACTION_HARDENING.md`, `docs/REUSE_MATRIX.md` (§0, §A, §G), `docs/CONVENTIONS.md` (v4 conventions), `docs/FIXTURE_APP.md` (v4 additions), `docs/GATES.md` (Epoch V, Gate 12), `docs/DEVICES.md`, `schemas/capability-specs.w1.json`, `capabilities/a11y/src/**`.

## Goal
Make the act side of `device.ui` as trustworthy as its read side before the agent leans on it: provider↔spec conformance (the `click.method` drift A6), additive `effect`/`warnings` results, a state-aware change detector, effect-verified click, refresh-then-verify `set_text`, awaited gestures, scroll inside bounds with real display metrics, hotspot promotion, IME-Enter via `ApiGate`, a node identity guard, fixture surfaces, emulator runs, and the scope closure of ADR-098 (ref scoping, `allow_coordinates`/`allow_global`, `device.app`). Every finding is a prediction until reproduced. Sources adapted from ClosePaw per `docs/REUSE_MATRIX.md` §A. Details: `docs/ACTION_HARDENING.md`.

## Tasks (from `docs/PROGRESS.md` Phase 12)
- **P12-01** Provider↔spec conformance test (`ProviderConformanceTest` over `schemas/capability-specs.w1.json` return types); first case = A6 (`click.method` drift `NODE|PARENT|COORDINATE|NONE` vs spec `NODE_ACTION|ANCESTOR_ACTION|COORDINATE`) [S]
- **P12-02** Additive `effect` (`changed|unchanged|unverifiable`) and `warnings` on `device.ui` action results — spec change through regeneration (SDK pyi, simulator, golden vectors, `schemas/errors.json`) [M]
- **P12-03** State-aware snapshot fingerprint + three-valued pure `ChangeDetector` (adapt RM-A4); failing test first for A2 [M]
- **P12-04** `click`/`long_click` effect verification: settle, compare, `retry_if_no_change` semantics, no fall-through to another channel after `unchanged` (A2) [M]
- **P12-05** `set_text`: refresh-then-verify, cursor/hint handling, no doubled text when the chain falls through (A1; adapt RM-A1) [M]
- **P12-06** Awaited gesture injection: callback + timeout + `dispatched == false` + `ViewConfiguration` timings (A3; adapt RM-A2) [M]
- **P12-07** Overlay vs injected gestures: audit `BubbleOverlay`, `TaskOverlaySink`, `RecorderOverlay`, `UiTextOverlay` window flags; add a touch gate only if a test shows an overlay swallowing an injected tap [S]
- **P12-08** Scroll: accessibility scroll first, gesture inside the scrollable's bounds, real display metrics instead of the 1080×2400 defaults (A4) [M]
- **P12-09** Hotspot promotion for the coordinate fallback (A5; adapt RM-A3) [S]
- **P12-10** IME-Enter through `ApiGate` (API 30) with a click fallback (adapt RM-A1) [S]
- **P12-11** Node identity guard before a node action: a stale or relabelled node gives typed `STALE_NODE`, never a click on the wrong node (adapt RM-A1) [S]
- **P12-12** Fixture-app surfaces for A1–A5 (toggle, slow transition, silent-failure text field, large clickable container, nested scrollable) + `docs/FIXTURE_APP.md` [M]
- **P12-13** Emulator runs on API 30/31/34: reproduce or refute A1–A5, before/after evidence per item (needs X-08) [M]
- **P12-14** Port the upstream unit tests of every adapted unit (RM-G2) as AutoHost tests [S]
- **P12-15** Regenerate and sync generated docs/SDK; USER_GUIDE note on `effect` and `retry_if_no_change`; DEVICES rows [S]
- **P12-16** a11y scope enforcement: run id into `NodeRegistry.put/resolve`, `device.ui.act` packages enforced on ref targets, `allow_coordinates`/`allow_global` enforced (A7, A8; ADR-098) [M]
- **P12-17** `device.app` scope: filter `list` and `foreground` by the granted packages (A9; ADR-098) [S]

## Constraints
- Findings A1–A9 are **predictions from reading code** — reproduce or refute each on the emulator (and `adb shell wm size` on the phones) before fixing.
- Spec changes are additive (`effect`, `warnings`) and go through regeneration (`tools/gen_artifacts.py`, `tools/sync_sdk.py`); selector language, ref format and the broker envelope do not change.
- **Start only after X-09** (Epoch V exit review, ADR-097); Python-only and tooling items may start earlier.
- Tag and tick a tracker item only at the level it really proved (`(WRITTEN)` → `(COMPILED)` → `(UNIT-TESTED)` → `(INTEGRATION-TESTED)` → ticked); `(real device)` items need a `device: <model>, Android <n> / API <n>` evidence clause; emulator and simulator runs are never real-device (ADR-087).
- Predicted defects get a failing test first; if one does not reproduce, record that and keep the test (ADR-089).
- Carry forward `AGENTS.md` §3–§4: fail closed, no app-specific code outside `samples/` and `testing/`, `SDK_INT` only in `ApiGate`/`availability()`, one broker path, secrets only in `SecretStore`, no hand edits to generated files.
- Adapted code follows `docs/REUSE_MATRIX.md` §0 (header, notice row, ported test, ladder evidence); only ClosePaw and opendroid files are eligible.

## Done when
Gate 12 items G12-a … G12-f are ticked with evidence (*real device*: G12-d); `python3 tools/progress.py --check` and `python3 tools/doc_check.py` pass; deviations are reported in `docs/DECISIONS.md`.
