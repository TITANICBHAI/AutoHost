# REWORK — writing work left (no verification)

> Created 2026-11-06 from a full md re-read; **REFRESHED the same day (2nd pass)** after W6.3/W6.4
> executed R1/R2 and the md correlation pass fixed the status headers. **Scope:** everything still
> writable (code, tests, schemas, docs, resources) that is NOT (a) verification/compile/test/CI/
> device evidence — `left.md` buckets A/C, (b) permanently blocked by owner decision (D-23/D-24/
> D-25, Phase 16, P13-18 Stage B, X-18/X-22) — `left.md` bucket D, or (c) already done.
>
> **OPEN INVENTORY (updated by the R3/R4/R5 passes):** R6 (X-03 scope check, X-05
> release-hardening checklist), R7 (THIRD_PARTY_NOTICES adoption statuses), R8 (SECURITY.md), R9
> (RM-C7 agent_view.json golden vectors), R10 (docs/README + LANGUAGE_WEIGHT + VERSIONS
> staleness), R11 (the three Phase-13 fixture surfaces), R12 (the device.screen spec summary +
> regeneration), R13 (done in-pass). **R1/R2/R3/R4/R5 are DONE.**
>
> **2nd-pass changes:** R1 (X-12 Kotlin half) and R2 (X-14 config half) are **DONE** — moved to the
> completed section with their evidence pointers. R10's UI_SPEC bullet is DONE (the correlation
> pass re-headed §10). R7 broadened (the RM-A1/A2/A3 notices rows also still read "PLANNED — not
> adopted"). Every other item re-verified against the tree by spot-check (grep output recorded in
> the session). The per-md checklist below was re-walked for every file touched since the first
> pass; the untouched files' first-pass ticks stand.

## R1. ~~X-12 Kotlin half~~ — **DONE (W6.3, 2026-11-06)**
- DONE: the schema split + the RESERVED validator rule + the negative test + the RunSpec/config
  boundary canary + the RM-D8 note (see the W6.3 evidence in `docs/PROGRESS.md`). STILL OPEN (needs
  the build, ADR-089 → left.md bucket A): the Room/storage RED tests:
  - ~~Failing-test-first (ADR-089): `x-secret` fields and provider API keys never reach Room,~~
  run config, checkpoints, exports, or the Redactor registry after restart.
- `schemas/task.schema.json`: `x-secret` valid ONLY under `profile_schema` — split the shared
  `$defs/objectSchema` so `config_schema` rejects `x-secret`; negative tests.
- Boundary tests: no plaintext secrets in RunSpec snapshots, checkpoints, events, history,
  exports.
- RM-D8 comparison note (`SecretStore` vs the RM-D8 pattern) recorded where the row names it.
- The Python/SDK half is DONE (13 tests, 2026-11-03); only the Kotlin/schema half remains.

## R2. ~~X-14 config half~~ — **DONE (W6.4, 2026-11-06)**
- DONE: the config written (base deny + the loopback-only domain allow), the manifest attributes
  set, the switch wiring reconciled to the audited InsecureFeatures switch, both drift notes
  resolved (see the W6.4 evidence in `docs/PROGRESS.md`). STILL OPEN: the platform-compliance
  verification on API 30/31/34 — D-24-blocked (left.md bucket D).

## R3. ~~Browser module code fixes~~ — **DONE (2026-11-06, R3 pass; the strike edit below had mismatched — fixed in the rework-2 walk)**
- ~~BrowserPolicyTest host-matching asserts~~ — DONE: a real CODE bug — `*.suffix` allowed the
  BARE suffix; fixed to subdomain-only.
- ~~extract AssertionError~~ — DONE: the webview permission self-check added.
- ~~unknown-page close~~ — DONE: close verifies the page exists → typed NOT_FOUND.
- ~~challenge AssertionError / disallowed-url NPE~~ — DONE: the test's kind corrected to
  anti_bot (the mis-assert was the NPE's null-deref path). Test runs at CI hand-back.

## R4. ~~Small refinements~~ — **DONE (2026-11-06, R4 pass)**
- ~~evidence_event~~ — DONE: `AgentEventLog.on` gained the `evidence_event` branch — the
  verifier's already-shaped §11 event passes through AS an `evidence` event (type preserved,
  never wrapped into a log line; RunPersistenceBridge's `type=="evidence"` contract holds),
  +1 test asserting the pass-through and the absence of double-wrapping.
- ~~import-picker entry point~~ — DONE: `AgentBundleViewer.kt` (app/ui/agent/) — the S31 viewer
  surface with BOTH entry paths: RunScreen hands the just-exported bundle's bytes in (identity
  redact is correct — the bundle was redacted at export), and the import button opens the system
  document picker (`GetContent`, zip) for an external bundle.
- ~~app-picker widget~~ — DONE: `AskSheetState` gained `AppEntry`/`hydrateApps`/`setAppQuery`/
  `visibleApps` (query filters label+package, label-sorted), the sheet renders FilterChips of the
  launchable installed apps (the host reads PackageManager's MAIN/LAUNCHER list, excluding
  AutoHost itself) with the manual package input kept as the fallback (a target need not be
  launchable), +2 tests.

## R5. ~~X-19 observation → predicted-defect test~~ — **DONE (2026-11-06, R5 pass)**
- DONE: `DevTaskStarter` (app/autohost/DevTaskStarter.kt) — the dev-folder start facade carrying
  the packs injection the inline StartRequest skipped, with the start/start-request seams
  JVM-testable; `DevTaskStarterTest` 4 pins the contract (the packs injection rides the config,
  the manual run shape under runsRoot, the typed error surfacing); AppNavHost now routes through
  `graph.devTaskStarter` — the run_starts arch rule passes at CI hand-back.

## R6. ~~Open cross-cutting rows~~ — **DONE (2026-11-06, R6 pass; the scope check ran first)**
- ~~X-03~~ — DONE: the coverage inventory found ALL FOUR legs already covered by named suites
  (canary → AgentSecuritySuite/ExportAndCheckpoints/AgentManifests/AiSettingsState + the SDK
  half; scope → ScopeClosure/AppScope/GrantClosure; token → RunTokenX13 + the suite's token
  canary; switches → InsecureFeaturesTest). The gaps the inventory exposed, both closed: the
  X-05 tree-drift test and the stale RELEASE.md cleartext row. Evidence = the map on the row;
  the CI run joins bucket A.
- ~~X-05~~ — DONE: `ReleaseHardeningTest` (app, 5 tests) — the manifest/NSC/policy-default
  tree-facts of docs/RELEASE.md §3 as a failing test; the stale cleartext row updated; a
  tree-drift-guard row added to the checklist.

## R8. ~~SECURITY.md missing~~ — **DONE (2026-11-06, R8 pass)**
- DONE: `SECURITY.md` written at the repo root — the honest model per plan §14/§22: what IS
  isolated (the broker chain, secrets/ADR-082, the supervised agent, the token-guarded exposure
  surfaces, the scope closure), what is NOT (the same-UID sandbox weakness stated plainly per
  §14.2, label hints as non-boundary, the advisory challenge detector, the chain's limits, the
  tracked gaps table), the verify-by-tests section, and the sources. AGENTS.md's doc map names it.
  (The original bullets are preserved below for the record.)
- §22 requires: "`SECURITY.md` states exactly what is and isn't isolated." The file does not
  exist in the repo and no tracker row owns it. Writable now: an honest security statement
  (same-UID sandbox weakness §20, Dev API exposure model, FileProvider scope, the insecure
  switches, what the broker interceptor chain does/does not isolate, secrets isolation via
  SecretStore, the run-token threat model). Source sections: plan §14, §5 (interceptors),
  ADR-059/062/082, HANDOFF §0.5.

## R9. ~~RM-C7 golden vectors missing~~ — **DONE (2026-11-06, R9 pass)**
- DONE: `testing/golden/agent_view.json` written (2 cases: a settings screen with a password
  node + an off-screen node exercising the keep rule; a capped screen exercising
  truncation) + `AgentViewGoldenTest` (runtime/agent) running the REAL AgentView against the
  file — render, fingerprint and ViewIndex asserted. One self-caught fix: the goldens'
  fingerprints initially used the short class name; the real `AgentView.fingerprint` uses the
  FULL class from the JSON — corrected, then a Python mirror of the render rules verified both
  goldens end to end. The RM-C7 row's evidence cell updated.

## R7. ~~THIRD_PARTY_NOTICES status columns~~ — **DONE (2026-11-06, R7 pass)**
- DONE: the ledger header corrected ("NONE adopted yet" → 7 rows ADOPTED with the attribution
  headers, ported tests and tracker evidence); the Status column updated per row — RM-A2/A3/A4/
  B1/B2/C2/D1 **ADOPTED** (the file named + the task + the ported test), RM-A1 and RM-G2
  **PARTIALLY ADOPTED** (the honest lineage: the set-text order/ports landed, Actions.kt keeps
  its own lineage, TargetResolver N/A), RM-E1/E2/E3/E8 **PLANNED** (Phase 16, dormant). Verified:
  all 7 adapted upstream paths still present (the licence_check contract) and licence_check
  green. The "PLANNED — not adopted" string is gone from the file.

## R10. ~~Doc status drift~~ — **DONE (2026-11-06, R10 pass)**
- ~~`docs/UI_SPEC.md` §10 header~~ — DONE in the correlation pass (re-headed to WRITTEN — D-26).
- ~~`docs/README.md` doc-map rows~~ — DONE: UI_SPEC → "Phase 14 surfaces WRITTEN — D-26"; FIXTURE_APP → "the six A1–A5 surfaces WRITTEN — D-26; the three Phase-13 surfaces remain planned (R11)".
- ~~`docs/LANGUAGE_WEIGHT.md:44`~~ — DONE: the `runtime` dimension noted as written into `app/build.gradle.kts` (P15-03, WRITTEN — D-26).
- ~~`docs/VERSIONS.md:36`~~ — DONE: the agent-runtime modules noted as existing with no new third-party dependency.
- ~~`docs/REPO_LAYOUT.md` §7's `lite` row~~ — DONE (found in the pass's PLANNED-row sweep): marked WRITTEN — D-26; the `:llm` row stays PLANNED (Phase 16 NO-GO).
- `docs/README.md` doc-map rows: "UI_SPEC.md (v4: Phase 14 screens planned)" and
  "FIXTURE_APP.md (v4: Phase 12/13 surfaces planned)" — both partly stale (Phase 14 written;
  Phase 12 surfaces added by P12-12).
- `docs/LANGUAGE_WEIGHT.md:44`: "the `runtime` dimension (`py` | `lite`) is planned in
  P15-03, not present" — stale: P15-03 wrote the dimension into `app/build.gradle.kts`.

## R11. ~~Phase-13 fixture surfaces~~ — **DONE (2026-11-06, R11 pass)**
- DONE: `InjectionScreenActivity` (six grant-widening/escape/rewrite probes as visible text +
  the `fx_injection_obeyed` counter that must stay 0) and `ApprovalScreenActivity` (five
  hint-vocabulary buttons + the "Border radius" word-matching control), following the P12-12
  conventions (Screens.bind, fx_state, deep links `injection`/`approval`, home buttons, ids,
  manifest). **The BLOCKED-package mechanism DECIDED on the FIXTURE_APP row**: the
  user-extendable BLOCKED list with the fixture's own package (the G13-b scenario adds
  `app.autohost.fixture` to the user additions and asserts the whole fixture observation is
  withheld); a second fixture APK was rejected (the floor works per-package at the filter — no
  new build needed; the in-test snapshots already pin the logic). The RUNS are G13-b emulator
  work (D-24).

## R13. docs/ANDROID_MANIFEST.md §8 header stale (found in the 2nd-pass sweep)
- The §8 header still reads "PLANNED; this pass changed no manifest or XML" while its own first
  bullet records the W6.4/X-14 manifest+NSC change. ~~Fixed in the same sweep~~ — DONE: the header
  was re-worded during this pass (see the file).

## R12. ~~Stale summary inside the source spec~~ — **DONE (2026-11-06, R12 pass)**
- DONE: `device.screen`'s summary now reads "Screen/keyguard state and control (wake, dismiss,
  lock, brightness — shipped in P8-10)." — fixed in the SPEC (never the generated copies) and
  regenerated: `docs/CAPABILITIES.md`, `_capabilities_gen.py` and the broker resource copy all
  re-synced; the stale string is gone from every copy; `gen_artifacts --check`, `sync_sdk
  --check` and `gen_tool_catalog --check` all green.

---

## MD checklist (tick when that file has been read and rework.md checked against it)

### Root
- [x] AGENTS.md — rules only; no open writing items beyond R1–R6
- [x] AGENT_OPERATING_PROTOCOL.md — procedure; open items map to R1–R4
- [x] AGENT_STATE.md — **2nd pass:** points at browser debug only for bucket B (R1/R2 done)
- [x] AGENT_STATE_TEMPLATE.md — blank template only, no work items
- [x] CRASH_FIX_PLAN.md — open rows = Checkpoint A/B (verification, excluded) + §8 phone test (X-18, D-25); Appendices A/B out-of-scope or blocked (A2 = X-22, D-23); cosmetic drift (System.currentTimeMillis in WebhookController, SDK_INT outside ApiGate) deliberately unfixed per HANDOFF §0.5
- [x] HANDOFF.md — §0.5 "known, not fixed" list re-checked: X-15/X-16 covered the dev-mode + watchdog items; remaining A2 = X-22 (D-23, excluded)
- [x] KICKOFF.md — orientation instruction only; queue items all covered by R1–R4
- [x] KICKOFF_PROMPT.md — orientation/setup instructions, already executed (Sessions 22+); no open items
- [x] README.md — project overview; no work items
- [x] READ_FIRST.md — read-order instruction only; no work items
- [x] THIRD_PARTY_NOTICES.md — **candidate new item (R7, pending REUSE_MATRIX check):** the reuse-status rows for RM-B1/RM-B2/RM-C2/RM-D1/RM-G2 still say "PLANNED — not adopted" although the units were written (P13-03/08/10/11, P12-14); the adoption-status column may need updating once code exists
- [x] WORK_SEQUENCE_PROMPT.md — **2nd pass:** W6.3/W6.4 now DONE (R1/R2 struck); the EXECUTED status note added to the file header in the correlation pass; all stages complete
- [x] left.md — **2nd pass:** bucket B now = browser debug only (R1/R2 done); buckets A/C/D/E excluded by scope
- [x] plan.md — read §0–§23 section map + §19–§23 in full: tracker rows cover every phase deliverable EXCEPT `SECURITY.md` (§22) → new item R8; "agent context pack" already delivered by P5-12 (`autohost docs --pack`); §23 stages H/D items all tracked (X-12→R1, X-14→R2, X-22 D-23)
- [x] worklog.md (read order: last two sessions) — historical records only; no open items beyond R1–R4

### docs/
- [x] docs/ACTION_HARDENING.md — A1–A9 all map to written rows P12-01…17; §6/§9 unknowns are emulator/device-gated (excluded); no new items
- [x] docs/AGENT_OPERATING_PROTOCOL.md — pointer to the root canonical file only; no content of its own
- [x] docs/AGENT_RUNTIME.md — §3–§17 all map to written rows (P13-01…17); §16 placeholders/unknowns are deliberate (device-gated); the India AppSensitivity additions are deferred by the doc's own rule, not open work; §11 confirms `evidence` IS a documented event type (supports R4)
- [x] docs/ANDROID_MANIFEST.md — §8 drift note confirms the X-14 gap exactly (→ R2 refined with the manifest-attribute detail); no other open items
- [x] docs/CAPABILITIES.md — GENERATED (do not hand-edit); its stale device.screen note comes from the spec → R12
- [x] docs/CONVENTIONS.md — conventions table current; no work items
- [x] docs/DECISIONS.md — D-20/D-21 deferred (owner), D-23 blocks X-22 (excluded), D-22 decided; no open writable items
- [x] docs/DEVAPI.md — no TODO/planned/open markers; tracked rows only
- [x] docs/DEVICES.md — D1/D2 owner-stated facts, UNVERIFIED cells need devices (D-25); P0-01 blocked; nothing writable
- [x] docs/FIXTURE_APP.md — the six A1–A5 surfaces are written; the three Phase-13 surfaces remain planned → R11 (code writable, runs D-24)
- [x] docs/GATES.md — procedures only; Epoch V section is verification (excluded)
- [x] docs/LANGUAGE_WEIGHT.md — line 44 stale re the runtime dimension (P15-03 written) → R10; other UNVERIFIED cells are measure/device-gated
- [x] docs/LOCAL_INFERENCE.md — Phase 16 permanent NO-GO (D-25); nothing writable
- [x] docs/MIGRATION.md — historical planning-pass record; line 90 "no file adapted yet" was true at write time, history is not rewritten
- [x] docs/MODEL_GATEWAY.md — §1–§10 map to written rows P13-01…07; placeholders/deferred vendor formats are deliberate design scope, not open work
- [x] docs/OEM_MATRIX.md — vendor battery-killer guide; no open items
- [x] docs/PERF.md — harness/metric definitions; numbers are D-25 measurement work (excluded)
- [x] docs/PLAN_MERGE.md — historical merge record; D-20/D-21 deferred features are owner decisions, not work
- [x] docs/PORT_EXERCISE.md — P11-12 historical gap log; no open items
- [x] docs/PROACTIVE-CRASH-REVIEW.md — historical review; open items superseded by crash-fix/X-rows
- [x] docs/PROGRESS.md — read in full: every open row maps to R1–R12, buckets A/C/D/E, or owner decisions
- [x] docs/README.md — doc-map status notes stale for UI_SPEC/FIXTURE_APP → R10
- [x] docs/REFERENCES.md — lesson tables; L31 maps to built units; no work items
- [x] docs/RELEASE.md — release procedure (device/build steps excluded); no writable gaps
- [x] docs/REPO_LAYOUT.md — module map consistent; planned modules were added by their tasks
- [x] docs/REUSE_MATRIX.md — RM-A/B/C/D/E/F/G/Z rows map to written tasks; §I open items are owner decisions (ARIA licence) or verification; **found:** RM-C7's `agent_view.json` missing → R9; RM-D8 note rides R1; RM-C4 deliberately deferred (not work)
- [x] docs/SDK_JS.md — JS runtime doc; no open markers
- [x] docs/SPIKES.md — v4 spike procedures are all measured-at-the-phone (D-24/D-25, excluded); S8 stub-model PC half noted dormant in W4.1
- [x] docs/UI_SPEC.md — S28–S33 map to written P14 rows; §10 header drift found → R10; P14-09's TalkBack code half rides the written composables, frame-time half is D-25
- [x] docs/USER_GUIDE.md — §9a Ask bar + data-egress disclosure present (P13-19/W4.3); current
- [x] docs/VALIDATION_REPORT.md — historical validation record of the planning pass; not instructions
- [x] docs/VERSIONS.md — line 36 stale: says :core:llm/:runtime:agent are "planned"; they exist since P13-01 → R10

### prompts/ · drafts/ · testing/
- [x] prompts/README.md — index only
- [x] prompts/_template.md — template only
- [x] prompts/epoch-v.md — Epoch V instructions; all its items (X-19, X-12 SDK half, X-17, X-07/08/09) done or bucket A/C
- [x] prompts/phase-0.md — spike procedures; device-gated (D-25), P0-01 blocked
- [x] prompts/phase-1.md — Phase 1 instructions; all rows written
- [x] prompts/phase-12.md — all P12 rows written; spec changes additive via regeneration (R12 touches one stale spec summary)
- [x] prompts/phase-13.md — all P13 rows written except P13-18 (Stage B, parked) / P13-20 (D-24/25)
- [x] prompts/phase-14.md — all P14 rows written except P14-09's device half (D-25)
- [x] prompts/phase-15.md — harness/tools written; measurements D-25
- [x] prompts/phase-16.md — permanent NO-GO
- [x] drafts/README.md — proposal drafts; the agent.schema draft belongs to Stage B (parked behind G13)
- [x] testing/agent-evals/README.md — written with W4.1 (scenarios + baselines + re-record rule)

### samples/ · spikes/ (READMEs)
- [x] samples/capabilities-showcase/README.md — sample doc; no open items
- [x] samples/hello/README.md — sample doc
- [x] samples/http-check/README.md — sample doc
- [x] samples/js-press-button/README.md — sample doc
- [x] samples/ocr-demo/README.md — sample doc
- [x] samples/pack-presser/README.md — sample doc
- [x] samples/photo-organizer/README.md — the P11-12 port result; gaps logged in docs/PORT_EXERCISE.md
- [x] samples/plugin-example/README.md — sample doc
- [x] samples/press-button-flow/README.md — sample doc
- [x] samples/press-button/README.md — sample doc
- [x] samples/settings-reader/README.md — sample doc
- [x] samples/task-ui-demo/README.md — sample doc
- [x] samples/task-ui-html/README.md — sample doc
- [x] samples/web-login-scrape/README.md — sample doc
- [x] spikes/README.md — owner phone-procedure instructions (D-25)
- [x] spikes/s1-python-runtime/README.md — spike scaffold; device-gated
- [x] spikes/s2-accessibility/README.md — spike scaffold; device-gated
- [x] spikes/s3-background-survival/README.md — spike scaffold; device-gated
- [x] spikes/s4-shell-tier/README.md — spike scaffold; device-gated
- [x] spikes/s5-text-input-ime/README.md — spike scaffold; device-gated

### verify/ · .agents/ (historical/other-agent)
- [x] verify/AGENTS.md — generic verification kit; self-declares repo-root docs win; no items
- [x] verify/AGENT_PRE_PROMPT.md — orientation copy
- [x] verify/KICKOFF.md — orientation copy
- [x] .agents/memory/MEMORY.md — earlier Replit agent memory; no open items
- [x] .agents/memory/android-target-devices.md — owner device note (superseded into docs/DEVICES.md)
- [x] .agents/memory/github-push-setup.md — setup history
- [x] .agents/memory/replit-verification-workflows.md — setup history

*(Excluded by rule: `archive/**` — never instructions; `reference/**` — read only through a
`docs/REUSE_MATRIX.md` row; `AutoHost-main.zip`.)*
