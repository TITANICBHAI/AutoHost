# Phase 1 — Walking skeleton (kickoff prompt)

Read `AGENTS.md`, `HANDOFF.md`, `plan.md` (§3.5, §4–§7, §5.8, §18 Phase 1), `docs/REPO_LAYOUT.md` (§1–§4, §6, §12), `docs/ANDROID_MANIFEST.md`, `docs/CONVENTIONS.md`, `docs/FIXTURE_APP.md`, `docs/GATES.md` (Gate 1), `docs/SPIKES.md` (verdicts decide fallbacks), `docs/REUSE_MATRIX.md` (R-01 to R-03, R-18), `schemas/broker.schema.json`, `schemas/capability-specs.w1.json`, `sdk-python/autohost/**/*.pyi`, `samples/`.

## Goal
A real Python task, started from the app, observes another app's UI, acts through the broker, verifies the changed state, streams logs and stops on request — on the real phone, built on the real architecture (modules, broker, worker process) in the smallest feature set.

## Build order (tasks P1-01 … P1-27 in `docs/PROGRESS.md`)
1. Gradle scaffold, build-logic, flavors `core`/`full`; ktlint/detekt; one-command `check`; CI incl. `progress.py --check` and `check_docs.py`. **Compile every module as you write it.**
2. `core:model`, `core:common` (`Clock`, `ApiGate`, `AhResult`, `AhLog`, `Ids`, JSON).
3. Broker: AIDL (provided), `RunTokenStore` with the run-token invariants (P1-26), registry, `EnvelopeCodec`, interceptor chain (Auth/Scope/Audit; scope enforced from day one).
4. `runtime:python`: Chaquopy, worker services, `PyBridge`, `WorkerGraph`; SDK v0; SDK/simulator persistence boundary (P1-27).
5. Run lifecycle v0 + `RunForegroundService` (version-guarded; notification with Stop).
6. `A11yReader` + `AutoHostAccessibilityService` v0 adapted from the staged ARIA shell (P1-22), gestures and actions adapted from the staged ClosePaw chain (P1-23), `SelectorEngine` v0, `A11yUiProvider` v0, `AppProvider` v0. The read path follows plan §5.7.
7. Dev-folder loader, Library → Run → live log, Setup v0, design tokens adapted from ClosePaw (R-18).
8. Fixture app v0 and one instrumented E2E test running a Python task through the real stack.
9. `samples/hello`, `press-button`, `settings-reader` run.
10. Architecture conformance tests (P1-24) and the idle-cost guard (P1-25).

## Constraints
- No DI framework; no `SDK_INT` outside `ApiGate` and provider availability; fakes, not mocks.
- Imported code: adapt one file at a time, update `docs/migration-manifest.tsv`, never touch `reference/` or `*/imported/`; ARIA files stay locked until D-19 is CONFIRMED (write those parts from the spec otherwise).
- The accessibility read path goes behind `A11yReader`; the hardened implementation is Phase 3, but do not couple callers to the simple one.
- No tree work, worker or interpreter while nothing runs (G1-h). Do not set `isAccessibilityTool`.

## Done when
Gate 1 items `G1-a … G1-k` are ticked with evidence **on the primary phone** where marked *(real device)*; `spikes/` is deleted; `gradle check`, `python3 tools/progress.py --check` and `python3 tools/check_docs.py` pass. Report what changed from the plan.
