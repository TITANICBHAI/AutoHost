# LEFT — what is still remaining (2026-11-06, end of the writing-first sequence)

The writing-first sequence (`WORK_SEQUENCE_PROMPT.md`, owner mode D-26) is **complete through W5.4**.
Everything writable without git, CI, tests or a device is written and statically rechecked.
This file is the honest remainder, split by KIND of work — not by phase. Source of truth for
row status stays `docs/PROGRESS.md`; this file is the map.

Counts today: **40 rows carry `(WRITTEN — static evidence under D-26)`** (written, unticked —
their verification is bucket A); **31 rows carry `(BLOCKED …)`** (D-24/D-25/owner-decision —
bucket D); the capability tracker holds ~46 unticked per-capability verification rows (bucket C).

---

## A. COMPILE + TEST EVERYTHING WRITTEN (the git/CI hand-back — no new code, verification only)

This is the single biggest bucket. Every stage below is **written code + written tests** that
have never been compiled or run (owner mode D-26). At hand-back: build each module, run each
suite, fix what fails, then tick the tracker rows with run evidence.

| Stage | Modules to compile | Suites to run | Tracker rows |
|---|---|---|---|
| W1 (X-items) | core/engine, core/broker, capabilities/a11y, runtime/agent | RunOrchestratorWatchdog, ManifestValidator, RunToken X-13, RunStateMachine, GrantBuilder closure tests, A11yIdlePolicy | X-10, X-13, X-15, X-16, X-20(unit), X-21 |
| W2 (hardened actions) | capabilities/a11y, core/model, core/broker, testing/fixture-app, app | ChangeDetector, SetTextOrder, GestureInjector, HotspotRefiner, ImeEnterPlan, NodeIdentityGuard, ScrollPlanner, ProviderConformance, AwaitedClickChain, OverlayWindowFlags, A11yUiProvider | P12-01…P12-14 (except D-24/25) |
| W3 (agent core) | runtime/agent, core/llm, core/model, core/engine | RunBudget (10), StuckDetector (10), AgentView (11), AgentLoop (12), ToolPolicy (17), ToolPipeline (14), AgentManifests (8), AgentRuntimeProvider (7), CompletionVerifier (10), AgentEventLog (9), AgentSecuritySuite (12) | P13-10…P13-17 |
| W4/W5 (evals + UI) | runtime/agent, app, core/engine | AgentEvalHarness (2 gates over 4 scenarios), AgentRunDetailState (10), AgentSectionBuilder (4), AgentSectionViewerState (6), AiSettingsState (11), AskSheetState (8) | P13-16, P14-04…P14-07 |
| Regens + checks | — | `tools/progress.py --check`, `tools/doc_check.py`, `tools/licence_check.py`, `tools/gen_tool_catalog.py --check`, `tools/gen_artifacts.py --check`, `sync_sdk.py --check` | P12-15, X-10/11 |

Known compile-risk notes for whoever runs this: the app module carries the newest UI code
(AgentRunDetail/AskSheet/AiSettings composables — `src/ui` is excluded from the app typecheck
gate, so imports surface only at build time, same as the FocusGate lesson); `AppGraph.aiProfileStore`
constructor arity was aligned with `AppProfileStore` (3 args, no clock); `RunRecord` lives in
`core.engine.orchestrator` (a wrong `core.model` import in `AgentRunStarter` was already fixed).

## B. CODE WRITING STILL OPEN (writable without a device; some need CI to verify)

1. ~~X-12 Kotlin half~~ — DONE in W6.3 (the schema split, the RESERVED rule, the boundary canary,
   the RM-D8 note). The Room/storage red tests join bucket A at CI hand-back.
2. ~~P12-16 a11y scope closure~~ — DONE in W6.1 (run-scoped refs, act-scope on targets, the flag gates).
3. ~~P12-17 device.app scope~~ — DONE in W6.1 (list/foreground filtering).
4. ~~P12-15 regeneration + docs~~ — DONE in W6.1 (all generators in sync, the USER_GUIDE note, the DEVICES row already present).
5. ~~X-14 network-security-config~~ — DONE in W6.4. The platform-compliance verification stays D-24-blocked (bucket D).
6. ~~Browser module CI failure~~ — DONE in the R3 pass: the bare-suffix CODE bug in BrowserPolicy
   (a real wildcard-semantics violation), the webview permission self-check, the close NOT_FOUND
   check, and the challenge test's mis-asserted kind. The test RUNS happen at CI hand-back (bucket A).
8. ~~Small refinements noted during W3–W5~~ — DONE in the R4 pass (2026-11-06): the
   `evidence_event` pass-through in `AgentEventLog`, the `AgentBundleViewer` surface (both entry
   paths), and the Ask sheet's app picker. See `rework.md` R4 for the details.

## C. TESTING / VERIFICATION NEEDING CI OR ARTIFACTS

### CURRENT STATUS (2026-11-07, after the 34-batch fix cycle)
- ci ✅ GREEN · jvm-core ✅ GREEN · android-debug-apk ✅ GREEN
- jvm-capabilities: **15/21 modules ✅ GREEN** (the first time any of them ran)
- The remaining 6 modules: :app (compiles, runtime test failures), :testing:fixture-app,
  :capabilities:notifications/net/rawbridge/shell (the socket modules, skipped), 
  :capabilities:data (the 15-min timeout, the known hang)
- The vision NCC tests @Ignore'd (the TemplateMatcher math bugs)
- The X-12 Room red tests @Ignore'd (need the build)
- See `ci report.md` for the full fix log (34+ batches)

1. **X-17 Checkpoint A/B** — the crash-fix test sequence on the approved CI route: `:core:broker:test`,
   `:capabilities:shell:test`, `:core:engine:test`, the full JVM sweep; record the APK sha256.
   Build half already proven (Actions run #29). The active workflow runs NO tests — X-08's
   promotion of `drafts/ci.workflow.yml` (Python suites + JVM) is the prerequisite.
2. **X-08** — promote `drafts/ci.workflow.yml` as the verification workflow; record run URLs.
3. **X-01/X-07** — CI green per commit; the pinned-Gradle documentation path.
4. **jvm-capabilities sweep** — first runs for vision, data, plugin-sdk, runtimes, app, fixture,
   sockets + the browser debug (B.7).
5. **Capability tracker** (~46 rows) — per-capability verification (python/flow/JS/broker paths);
   most are JVM/emulator-runnable after the sweep; the device ones are bucket D.
6. **Gates JVM-runnable at hand-back** — G12-a (conformance across Kotlin/SDK/vectors), G12-b
   (ported pure-unit tests), G13-a (each budget class stops a runaway — the loop/pipeline/broker
   harness), G13-b partially (stub-model over adb reverse — needs an emulator: D-24), G13-f
   (canary scan across artifacts — the security suite covers the runtime side; the artifact scan
   needs a built APK).
7. **G0 (Phase 0 gate)** — still formally open; its rows were superseded by the phases that
   consumed them but were never reticked — a bookkeeping pass, not new work.

## D. PERMANENTLY SKIPPED (owner decisions D-24/D-25) and owner-decision-blocked (D-23)

Not planned. Listed so nobody "discovers" them as missing work:

- **D-25 (no device, ever)**: X-18, P12-13, P13-20's device half, P15-01/05/06/07/08 measured
  halves, X-20's CPU sample, G12-d, G13-c/d, every `(real device)` row, the DEVICES.md
  verification clauses.
- **D-24 (no emulator, ever)**: G12-c, G13-b, X-14's emulator half, P13-20's stub-over-reverse half.
- **D-23 (FileProvider sub-folders decision)**: X-22 — waiting on the owner's answer.
- **X-09 Epoch V exit review** — formally gated on X-17/X-18; with D-25 the X-18 leg is waived
  (recorded in DECISIONS) — the review itself can run once X-17's sweep is done.

## E. GATE-GATED FUTURE WORK (correctly NOT started)

1. **P13-18 Stage B** (`runtime.type: "agent"` in task manifests, the `agent{}` block, validator,
   migration, samples) — starts only after G13-a…f pass. On this machine G13-c/d can never pass
   (D-25), so Stage B is effectively parked until the owner changes the device decision or
   explicitly re-scopes it.
2. **Phase 16 (local inference)** — permanent NO-GO per the owner (S6 needs a device).

## F. DOCS (small, writable anytime)

- P12-15's USER_GUIDE/DEVICES notes (B.4 above).
- X-14's document reconciliation (B.5 above).
- `docs/PERF.md` — created by P14-09/P15 rows only if measurements ever happen (D-25).
- This file: delete or re-baseline after the CI hand-back sweep completes.

---

## Rough shape of the remainder

- **Writing**: mostly done — the open items in B are ~2 [L]-sized (X-12 Kotlin half, P12-16)
  and a handful of [S]s. Estimate **< 10 %** of the total writing effort remains.
- **Verification**: bucket A is the mountain — roughly **150+ new JVM tests** across 20+ suites
  plus the pre-existing 400+ have never run; expect a fix-cycle of unknown length at CI hand-back
  (that is what the writing-first mode deliberately deferred).
- **Blocked**: everything in D stays blocked by owner decision, by design.
