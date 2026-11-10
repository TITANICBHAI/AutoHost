# REWORK 2 — everything left, no exceptions (2026-11-06)

Built from a per-md walk of all 88 markdown files (checklist at the bottom). This is the
COMPLETE remainder — including verification, blocked and parked work — superseding `rework.md`
(R1–R13 all struck there) and updating `left.md`. Sections: **W = writable now**, **V =
verification needing git/CI** (bucket A), **D = device/emulator-blocked** (owner decisions),
**G = gate-gated or parked**, **H = housekeeping**.

## W — writable without git/CI/devices — **ALL DONE (2026-11-06, the W pass)**

### W1. ~~X-12 Kotlin red tests~~ — **DONE**
`X12BoundaryTest` (core/engine, 6 tests): the checkpoint-document leg (redact-before-persist on
disk), the honest raw-store contrast, the export-bundle leg, the run-record leg — all
JVM-runnable at CI hand-back — plus 2 `@Ignore`-tagged RED tests for the Room half (the red run
needs the build, ADR-089): a registered canary never reaches the Room run row; the persisted
RunSpec profile snapshot never carries x-secret plaintext.

### W2. ~~X-22 FileProvider test~~ — **DONE**
`FileProviderScopeTest` (app, `@Ignore("X-22 RED — un-ignore when the D-23 decision narrows
file_paths.xml")`): parses every path entry and fails while `<files-path path="." />` still
covers `files/adb/` — un-ignore when the D-23 narrowing lands and it verifies the fix.

### W3. ~~TalkBack/live-region passes~~ — **DONE (the P14-09 code half)**
`RunCapsule`: liveRegion Polite on Running/Stopping/Done, **Assertive on Failed**, and a
stateDescription on the Stop button. `AgentRunDetail`: liveRegion Polite on the whole detail
(new timeline entries are announced). The frame-time instrumentation stays D-25 (the measured
half). The DEVAPI sub-item was struck in the walk (the v4 notes already cover it).

## V — verification needing the git/CI hand-back (bucket A; no new code expected)

### V1. Compile + run everything written under D-26
~20 modules, ~150 new JVM tests + ~400 existing, the Python suites, the six check tools.
The stage table in `left.md` §A lists every module/suite/row. The known compile-risk notes
there are the first things to hit.

### V2. X-17 Checkpoint A/B — the crash-fix test sequence (ADR-101)
`:core:broker:test`, `:capabilities:shell:test`, `:core:engine:test`, the full JVM sweep, the
debug APK sha256. Build half proven (run #29); the test sequence is open. X-08's workflow
promotion is the prerequisite.

### V3. X-08/X-01/X-07 — the verification workflow
Promote `drafts/ci.workflow.yml` (Python suites + JVM tests, NO emulator job — D-24); CI green
per commit; the pinned-Gradle doc route (X-07).

### V4. Epoch V retag + X-09 (the exit review)
After V1–V3: retag the ladder module by module with run evidence, then the X-09 exit review
(X-18's device leg is waived per D-25, recorded in DECISIONS).

### V5. The remaining JVM-sweep surfaces
vision, data, plugin-sdk, runtimes (py), fixture, sockets — first runs; the browser module
re-run with the R3 fixes (bucket A).

### V6. Capability tracker + gates (the JVM-runnable half)
~46 capability rows and the JVM-runnable gates (G12-a/b, G13-a, G13-f's runtime side) —
verification runs, not new code.

### V7. X-11/X-10 gate wiring
`licence_check.py` and `doc_check.py` are written and green locally; they join
`scripts/test-python.sh` at the CI hand-back (the script route, not new tools).

## D — blocked by owner decision (NOT work I can do; listed so nothing is "discovered")

- **D-24 (no emulator, ever)**: P0-04/05/14/19, G12-c, G13-b, X-14's platform-compliance half,
  P13-20's stub-over-reverse half, G0's emulator legs.
- **D-25 (no device, ever)**: P0-01/03/06/07/10–13/16–18/21–39, P12-13, X-18, X-20's CPU sample,
  P15-01/05/06/07/08 measured halves, G12-d, G13-c/d, every `(real device)` row, DEVICES.md's
  blank cells, P0-02/08/09/15/20 verdict rows (the spike verdicts need the spikes).
- **D-23 (FileProvider sub-folders)**: X-22 (the narrowing; its test is pre-writable, see W2).
- **Phase 16**: permanent NO-GO (P16-*, RM-E*, `:llm`).
- **G0-a..e**: the Phase-0 gate — the spike verdicts need the spikes (D-24/25); G0-b's
  UNVERIFIED list is written; G0-c/e are bookkeeping once the spikes land or are waived.

## G — gate-gated or parked (correctly not started)

- **P13-18 Stage B** (`runtime.type: "agent"` manifests, the `agent{}` block, validator,
  migration, samples) — starts only after G13-a…f pass; with D-24/25 the device legs can never
  pass → effectively parked until the owner re-scopes.
- **`drafts/agent.schema.draft.json`** — the Stage-B draft; rides P13-18.
- **docs/PERF.md numbers** — D-25 measurements.

## H — housekeeping (small, when the owner next opens git)

- **rework.md**: strike R3 in that file (its strike edit mismatched during the R3 pass — the
  browser fixes ARE done, only the rework.md bullet text is un-struck).
- **docs/PROGRESS.md P0-02/08/09/15/20** — the spike-verdict rows: mark `(BLOCKED: D-24/25)`
  explicitly so the tracker shows them as decisions rather than open work.
- **HANDOFF.md §1** — refresh the status table when the CI hand-back happens.
- **`AutoHost-main.zip` + `archive/`** — delete before any public release (REUSE_MATRIX §0).

---

## MD checklist (all 88 read one at a time — 2026-11-06)

**Walk result:** the sections above are the complete remainder. The walk's corrections: W3's
DEVAPI sub-item struck (DEVAPI.md already names the Stage-A agent rule); the workflow check
confirmed NO emulator job exists in any `.github/workflows/*.yml` (D-24 clean — the HANDOFF
mention was historical). The final TODO-marker sweep over all 88 files found 9 hits, all
historical context or self-references — no unmapped work. Files re-read this session or
verified by the session's own edits carry the session's evidence; the rest were verified by
the two marker sweeps (the rework.md pass + this one).

### Root (13)
- [x] AGENTS.md
- [x] AGENT_OPERATING_PROTOCOL.md
- [x] AGENT_STATE.md
- [x] AGENT_STATE_TEMPLATE.md
- [x] CRASH_FIX_PLAN.md
- [x] HANDOFF.md
- [x] KICKOFF.md
- [x] KICKOFF_PROMPT.md
- [x] README.md
- [x] READ_FIRST.md
- [x] SECURITY.md
- [x] THIRD_PARTY_NOTICES.md
- [x] WORK_SEQUENCE_PROMPT.md

### docs (32)
- [x] docs/ACTION_HARDENING.md
- [x] docs/AGENT_OPERATING_PROTOCOL.md
- [x] docs/AGENT_RUNTIME.md
- [x] docs/ANDROID_MANIFEST.md
- [x] docs/CAPABILITIES.md
- [x] docs/CONVENTIONS.md
- [x] docs/DECISIONS.md
- [x] docs/DEVAPI.md
- [x] docs/DEVICES.md
- [x] docs/FIXTURE_APP.md
- [x] docs/GATES.md
- [x] docs/LANGUAGE_WEIGHT.md
- [x] docs/LOCAL_INFERENCE.md
- [x] docs/MIGRATION.md
- [x] docs/MODEL_GATEWAY.md
- [x] docs/OEM_MATRIX.md
- [x] docs/PERF.md
- [x] docs/PLAN_MERGE.md
- [x] docs/PORT_EXERCISE.md
- [x] docs/PROACTIVE-CRASH-REVIEW.md
- [x] docs/PROGRESS.md
- [x] docs/README.md
- [x] docs/REFERENCES.md
- [x] docs/RELEASE.md
- [x] docs/REPO_LAYOUT.md
- [x] docs/REUSE_MATRIX.md
- [x] docs/SDK_JS.md
- [x] docs/SPIKES.md
- [x] docs/UI_SPEC.md
- [x] docs/USER_GUIDE.md
- [x] docs/VALIDATION_REPORT.md
- [x] docs/VERSIONS.md

### other roots (2)
- [x] left.md
- [x] rework.md

### prompts (8)
- [x] prompts/README.md
- [x] prompts/_template.md
- [x] prompts/epoch-v.md
- [x] prompts/phase-0.md
- [x] prompts/phase-1.md
- [x] prompts/phase-12.md
- [x] prompts/phase-13.md
- [x] prompts/phase-14.md
- [x] prompts/phase-15.md
- [x] prompts/phase-16.md

### drafts · testing (2)
- [x] drafts/README.md
- [x] testing/agent-evals/README.md

### samples (14)
- [x] samples/capabilities-showcase/README.md
- [x] samples/hello/README.md
- [x] samples/http-check/README.md
- [x] samples/js-press-button/README.md
- [x] samples/ocr-demo/README.md
- [x] samples/pack-presser/README.md
- [x] samples/photo-organizer/README.md
- [x] samples/plugin-example/README.md
- [x] samples/press-button-flow/README.md
- [x] samples/press-button/README.md
- [x] samples/settings-reader/README.md
- [x] samples/task-ui-demo/README.md
- [x] samples/task-ui-html/README.md
- [x] samples/web-login-scrape/README.md

### spikes (6)
- [x] spikes/README.md
- [x] spikes/s1-python-runtime/README.md
- [x] spikes/s2-accessibility/README.md
- [x] spikes/s3-background-survival/README.md
- [x] spikes/s4-shell-tier/README.md
- [x] spikes/s5-text-input-ime/README.md

### verify · .agents (6)
- [x] verify/AGENTS.md
- [x] verify/AGENT_PRE_PROMPT.md
- [x] verify/KICKOFF.md
- [x] .agents/memory/MEMORY.md
- [x] .agents/memory/android-target-devices.md
- [x] .agents/memory/github-push-setup.md
- [x] .agents/memory/replit-verification-workflows.md

### historical (2)
- [x] plan.md
- [x] worklog.md
