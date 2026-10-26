# Phase 1 — Walking skeleton (kickoff prompt)

Read `AGENTS.md`, `plan.md` (§3.5, §4–§7, §18 Phase 1), `docs/REPO_LAYOUT.md` (§1–§4, §6), `docs/ANDROID_MANIFEST.md`, `docs/CONVENTIONS.md`, `docs/FIXTURE_APP.md`, `docs/GATES.md` (Gate 1), `docs/SPIKES.md` (verdicts decide fallbacks), `schemas/broker.schema.json`, `schemas/capability-specs.w1.json`, `sdk-python/autohost/**/*.pyi`, `samples/`.

## Goal
A Python script, started from the app, **clicks something in another app** on the real phone, with live logs and a working Stop — built on the real architecture (modules, broker, worker process), minimal in features.

## Build order (tasks P1-01 … P1-21 in `docs/PROGRESS.md`)
1. Gradle scaffold + build-logic + flavors `core`/`full`; ktlint/detekt; one-command `check`; CI script incl. `tools/progress.py --check`.
2. `core:model`, `core:common` (`Clock`, `ApiGate`, `AhResult`, `AhLog`, `Ids`, JSON).
3. Broker: AIDL (provided), `RunTokenStore`, registry, `EnvelopeCodec`, **interceptor chain** with Auth/Scope/Audit (scope enforced from day one).
4. `runtime:python`: Chaquopy, `TaskWorkerService0`, `PyBridge`, `WorkerGraph`, `AutoHostApp` branching on process name; SDK v0 implementing the stubs for `ctx`, `log`, `errors`, `device.app`, `device.ui` basics.
5. Run lifecycle v0 + `RunForegroundService` (version-guarded `startForeground`; notification with Stop).
6. `A11yReader` interface + `AutoHostAccessibilityService` v0 (+ dynamic package scope) + `SelectorEngine` v0 + `A11yUiProvider` v0; `AppProvider` v0.
7. Dev-folder loader (app-specific external dir), Library list → Run → live log screen; Setup screen v0.
8. Fixture app v0 + one instrumented E2E test that runs a Python task through the real stack.
9. Make `samples/hello`, `press-button`, `settings-reader` run.

## Constraints
- Create files where `docs/REPO_LAYOUT.md` says. No DI framework. No `SDK_INT` outside `ApiGate`/provider availability. Fakes, not mocks.
- Accessibility reads go behind `A11yReader` now; the hardened implementation (ReadGate etc.) is Phase 3 — don't build it yet, but don't couple callers to the simple one.
- Do not set `isAccessibilityTool`. Do not add features not in the tasks.
- Follow the spike verdicts; if one chose a fallback, implement the fallback and note it in `DECISIONS.md`.

## Done when
Gate 1 items `G1-a … G1-f` are ticked with evidence **on the primary phone**; `spikes/` is deleted; `./gradlew check` and `tools/progress.py --check` pass. Report what changed from the plan, if anything.
