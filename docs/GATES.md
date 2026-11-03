# GATES — how to prove each phase is done
A gate item is **passed** only when the procedure was executed and the **evidence** is recorded next to the item in `docs/PROGRESS.md` (`evidence:` line) — commit/test name, log or screenshot path, **device + API level**. A green build alone never passes a gate. *(real device)* = a physical phone listed in `docs/DEVICES.md` (ADR-095: the owner's Realme C3 and Galaxy M31 until an Android 10 phone is confirmed) unless stated; the evidence line names it (`device: <model>, Android <n> / API <n>`). An emulator or simulator run is **integration-tested** and never passes a *(real device)* item. Verification ladder (ADR-087, enforced by `tools/progress.py`): planned → `(WRITTEN)` → `(COMPILED)` → `(UNIT-TESTED)` → `(INTEGRATION-TESTED)` → ticked `[x]`; a tick needs evidence; `(FAILING: <reason>)` is never ticked.
IDs match `PROGRESS.md`. Anchors: `### G1-a` etc.

## Gate 0 — Spikes
### G0-a Spike verdicts
Procedure: run S1–S5 (plan §18); record measured results in `SPIKES.md`. Evidence: `SPIKES.md` sections with PASS/FALLBACK/FAIL and numbers.
### G0-b Devices recorded
Procedure: fill `DEVICES.md` (primary, any additional real devices, emulators); mark UNVERIFIED paths. Evidence: file committed.
### G0-c Decisions
Procedure: ADRs for every fallback chosen; **app licence decided**; defaults confirmed. Evidence: `DECISIONS.md` entries.
### G0-d UiAutomation exclusivity
Procedure (S4): start a UiAutomation-based process, observe whether the accessibility service is suppressed. Evidence: logs + verdict.
### G0-e Clean slate
Procedure: confirm no production modules exist yet (only `spikes/`). Evidence: tree listing.

## Gate 1 — Walking skeleton *(real device)*
### G1-a hello logs live
Run `samples/hello` from the dev folder; watch the log screen update live. Evidence: screen recording or log excerpt with timestamps.
### G1-b press-button on fixture
`press-button` launches the fixture app, waits for `btn_continue`, clicks it; fixture counter == 1. Evidence: fixture state readout + run log.
### G1-c third-party app
A script opens a real third-party screen (e.g. Settings → About), reads a visible text node, clicks an item. Evidence: log + screenshot path.
### G1-d Stop within 6 s
Run a `while True: ctx.sleep(1)` script; tap **Stop** in the notification. Worker process gone (`adb shell ps | grep :w`), UI responsive, status CANCELLED within 6 s. Evidence: timestamps + `ps` output.
### G1-e Exception → FAILED
Script raises; run screen shows FAILED with traceback and reason `EXCEPTION`. Evidence: screenshot.
### G1-f Scope rejection
Test asserts a UI call for a package outside the granted scope returns `SCOPE_VIOLATION` and leaks no nodes. Evidence: test name.

## Gate 2 — Task platform
### G2-a Separation
Two tasks × two profiles: distinct config, secrets, KV, history. Evidence: test + screenshots.
### G2-b Broken bundle
Import a deliberately broken bundle → ImportReport lists precise errors; nothing installed. Evidence: report screenshot.
### G2-c Canary secret
Test registers a canary secret value, runs a task that logs/screenshots/exports; grep DB, logs, evidence text, export bundle → zero hits. Evidence: test name + grep output.
### G2-d Scope re-prompt
Change a scoped config value (e.g. target package) → grant is invalidated and re-prompted. Evidence: test.
### G2-e CompatReport
Task requiring an uninstalled app and an unavailable capability → report shows reasons + Fix actions. Evidence: screenshot.
### G2-f Dev API loop
From the PC: `autohost push` → `autohost run` → `autohost logs` with no touch on the phone. Evidence: terminal transcript.

## Gate 3 — Device depth *(real device unless noted)*
### G3-a Instrumented suite
Fixture-app suite covers: click chain, `set_text` outcomes (incl. Unicode, password), `scroll_until`, keyboard states, secure-screen typed failure, stale-node fail-closed, out-of-scope returns nothing, coordinate results flagged, popup guard, settle. Evidence: CI/test report.
### G3-b Reliability
100 consecutive launch → wait → click → verify cycles on the fixture app ≥99 % success. Evidence: result log.
### G3-c settings-reader
`samples/settings-reader` passes on the phone. Evidence: run export.
### G3-d Latency
Publish p50/p95 for `find` on 100/500/2,000-node trees (target p95 < 200 ms at 2,000). Evidence: `docs/PERF.md`.
### G3-e Unattended screenshots
20 consecutive screenshots via MediaProjection with the consent **auto-accepted by the accessibility service**, ≥95 % success; also after killing the main process once. Evidence: log + counts.
### G3-f Wedged read
Test injects a hanging accessibility read → `snapshot` returns a `degraded` last-good snapshot within the budget; recovers when the read completes. Evidence: test name.
### G3-g Text input
Unicode + password text committed via IME with typed outcomes; `ACTION_SET_TEXT` vs IME vs paste coverage table. Evidence: table + log.
### G3-h API-30 screenshot *(emulator)*
Accessibility `takeScreenshot` works and is throttled on an API 30+ emulator. Evidence: emulator log.
### G3-i Fail-closed transitions
`transition()` with an `UNKNOWN` destination fails; coordinate-only success is never reported verified. Evidence: test names.

## Gate 4 — MVP: triggers and scheduling
### G4-a Fake-clock suite
All rule types, limits, conditions, misfire policies covered. Evidence: test report.
### G4-b Soak
3 hours at 10-minute cadence, two profiles, fixture app: ≥95 % on time. Evidence: soak report (`tools/soak_report`).
### G4-c Ordering and locks
Per-profile order correct; never two UI runs at once. Evidence: report section.
### G4-d Recovery
Forced worker kill and a reboot: schedules re-armed; accessibility service returns without manual steps when the secure-settings grant exists. Evidence: logs.
### G4-e Late badge
Exact alarms denied → "timing may be late" badge shown. Evidence: screenshot (emulator API 34 acceptable).
### G4-f Reconcile
Delete the armed alarm deliberately → reconcile worker re-arms and applies the misfire policy. Evidence: log.
### G4-g ScreenGuard
Screen off + non-secure keyguard: guard wakes and dismisses; with a secure lock: degrades with reason and raises `WAITING_USER(unlock)`. Evidence: logs.

## Gate 5 — Reliability and authoring loop
### G5-a Resume
A 3-stage task killed mid-stage resumes without repeating completed stages. Evidence: counters test.
### G5-b WAITING_USER
Script raises `NeedsUser`; user acts; run continues from the same stage. Evidence: recording/log.
### G5-c Export bundle
Bundle alone shows stage, reason code, incident, compact dump, redacted evidence. Evidence: bundle + reviewer note.
### G5-d Canary
Canary secret absent from all outputs. Evidence: grep.
### G5-e Idle watchdog
A script that stops calling the broker is warned then cancelled (`IDLE_TIMEOUT`). Evidence: log.
### G5-f Agent loop
A fresh agent session using only MCP + the agent pack inspects the phone, tries selectors, writes a task, runs it, reads a failure bundle and fixes it. Evidence: **transcript** committed under `docs/evidence/`.
### G5-g Doctor
`autohost doctor` returns 0 when healthy and 127 with a deliberately disabled required check. Evidence: terminal output.
### G5-h Drift
Generated artifacts match specs (drift test green). Evidence: test name.

## Gate 6 — Flows and recorder
### G6-a No worker
Flow `press-button-flow` runs with **no `:w*` process** (`ps`), memory recorded vs the Python version. Evidence: `ps` + `dumpsys meminfo`.
### G6-b Replay
A flow recorded from a fixture interaction replays successfully. Evidence: flow file + run log.
### G6-c Guard
Change the fixture screen deliberately → guarded replay stops with evidence (`on_mismatch: stop`). Evidence: run export.
### G6-d Coexistence
A flow task and a Python task run with independent schedules/histories. Evidence: screenshots.
### G6-e Coordinates need confirmation
A recorded coordinate step is flagged and blocks Save until confirmed. Evidence: screenshot/test.

## Gate 7 — Task UI and surfaces
### G7-a UI tiers
Samples for Tier A/B/C trigger Python handlers and show live state. Evidence: recordings.
### G7-b Tile and shortcut
Each starts a run. Evidence: log.
### G7-c Stop controls
Bubble Stop and volume-key stop each halt a run while another app is foreground. Evidence: recording.
### G7-d Inspector
Inspector overlay copies a selector that works in a script. Evidence: selector + run log.
### G7-e External intent
`RUN_TASK` without the token is rejected; with it, runs. Evidence: adb transcript.

## Gate 8 — v1: capability breadth
*Written (session 14) — all code + tests exist; device items need the phone (see docs/PROGRESS.md P8).*
### G8-a Template
≥12 capabilities shipped through the §6.3 template (spec, provider, availability, stubs, docs, tests, showcase). Evidence: checklist table (`docs/CONVENTIONS.md` § Capability module template checklist) + `samples/capabilities-showcase` (18 spec'd capabilities; sim-run sweep in `test_samples.py::CapabilitiesShowcaseSample`).
### G8-b Switches
Insecure-feature switches default off and are enforced (tests); CompatReport shows "disabled by setting". Evidence: `InsecureFeaturesTest` (8), `CapabilityResolverTest` (DENIED row + relaxed-mode row, feature-id semantics), `InsecureFeatureInterceptor` after Scope.
### G8-c Raw bridge
Blocked without grant+switch; works with both. Evidence: SDK gate tests (`test_p8_part2.AndroidDirectTests`: switch off → INSECURE_FEATURE_DISABLED, no grant → PERMISSION_DENIED, authorize unlocks); import guard: `_scan.raw_bridge_calls` + `ImportPipeline` RAW_BRIDGE_UNDECLARED.
### G8-d Triggers
Notification-, webhook- and chain-triggered tasks run end to end. Evidence (device): inject a notification while a `notification.posted` rule is armed; `curl -X POST -H "X-AutoHost-Token: …" http://127.0.0.1:8082/hook/<name>`; a `task.chain` rule after a run. Webhook server JVM-tested (`WebhookServerTest`); notification delivery rides P8-01/02.
### G8-e Rule behaviours
Cooldown, debounce, busy-queue (queue/drop/replace) proven by tests. Evidence: `TriggerRulesTest` (17, P8-03).
### G8-f Lazy permissions
Android permissions requested only when needed, with explanation. Evidence: `LazyPermissionsTest` + `PermissionRequestActivity`/`FixHub` (device recording pending).

## Gate 9 — Shell tier
*Parts 1+2 written (sessions 15–16): the transport layer, the allowlist and the shell providers; `device.app.manage`/`device.logcat`/radio-display-mock, the `device.settings` write door (ADR-067), the exclusive UiAutomation link (ADR-068), self-heal (shell fallback, IME auto-select, keep-awake), the pairing guide UI and the boot reminder. Device items need the phone (Android 10: `adb tcpip 5555` per boot).*
### G9-a Provider swap
Accessibility disabled → `settings-reader` passes via the shell provider. Evidence (device): run export. Written side: `ShellUiProvider` registered after a11y (first-available rule); `AdbClientTest` proves the transport.
### G9-b Self-heal
Service re-enabled after a forced disable via secure settings. Evidence (device): log. Written side (session 16): `ShellSelfHeal.ensureAccessibility` (P4-14 direct path + the ADR-067 shell fallback), `SecureSettingsHelper` (one write door), IME auto-select + keep-awake helpers; JVM tests in SettingsWriteTest/UiAutomationControllerTest cover the policy seams.
### G9-c Allowlist
Allowlist and switches enforced by tests. Evidence (written, session 15): `CommandAllowlistTest` (7: fail-closed grants, metacharacter deny even on pattern match, globs), `ShellProviderTest` (7: grant/switch-order gates, typed errors), sim mirror + `test_p9_part1` (13); `InsecureFeaturesTest` (P8-13) pins the `shell` switch. Device pass: a live allowlisted + a refused command in one transcript.
### G9-d Reboot
Reboot/re-arm behaviour documented and reminder shown. Evidence (device): screenshot + doc. Written side (session 16): `RearmReminder` (boot + app-start, `shell.was_live` memory, the `shell_reminders` channel) wired in BootReceiver + AppGraph init; the Setup guide documents the per-boot ritual (S24).
### G9-e Exclusivity
UiAutomation provider and accessibility service never active together. Evidence (device, S4): the a11y service stays connected while the provider is off. Written side (session 16, ADR-068): the pure `UiAutomationExclusivity` policy (start refuses on a11y-connected or toggle-off; reconnect closes the session — UiAutomationExclusivityTest), the controller lifecycle tests (UiAutomationControllerTest) and the AppGraph collector that feeds every a11y connect event into `onA11yEvent`.

## Gate 10 — Browser, vision, data/hardware
### G10-a Web login
A task logs into a **local test web page** and scrapes it. Evidence: run export.
Written side (session 17, ADR-070): `browser.webview` opens `task:` bundle pages without any network grant (BrowserPolicy task-path guard), the visible-surface controller + activity, and the sim-tested open→type→click→extract story — **`samples/web-login-scrape` runs unchanged in the simulator** (session 17 part 2: SUCCEEDED with the session cookie true, secret never echoed; the `open_challenge` switch pauses at WAITING_USER:SECURITY_CHALLENGE). Device run export pending.
### G10-b OCR
OCR reads a screenshot region. Evidence: log.
Written side (session 17, ADR-072): `vision.ocr` with region clipping over the screenshot chain + the pure TemplateMatcher golden vectors (TemplateMatcherTest); **`samples/ocr-demo` runs in the simulator** (hint:true rides the run result; S28 measures engine cost at the phone). Device log pending.
### G10-c Challenges
Challenge pages are detected and routed to `WAITING_USER`; never bypassed. Evidence: run export.
Written side (session 17, ADR-070): the pure ChallengeDetector (captcha/anti-bot/rate-limit/denied markers, login pages deliberately NOT challenges) + SDK NeedsUser(SECURITY_CHALLENGE) on every browser link by default + the challenge fixture page in the sample bundle — **sim-proven** (the sample run ends WAITING_USER with the reason code). Device run export pending.

## Gate 11 — Final acceptance
### G11-a New task from the pack
A brand-new task written from the generated pack with **no Kotlin changes** is installed, profiled and scheduled. Evidence: task + transcript.
Written side (session 18): the JS sample (`samples/js-press-button`) proves a task that ships with **zero Kotlin changes** runs through the whole chain in the simulator; `docs/SDK_JS.md` + the prelude complete the generated-pack story for the third runtime. The install→profile→schedule transcript is a device item (with the repo-index consumer landing in part 2).
### G11-b Three runtimes
Python, flow and JS tasks coexist with independent state. Evidence: screenshots.
Written side (session 18, ADR-076/077): **sim-proven** — `test_p11_part1.ThreeRuntimesCoexist` runs a Python task (`run_task`), a flow task (`run_flow_task`) and a real JS task under node (`run_js_task`) against one FakeDevice; all three succeed with distinct store keys (`py_marker`/`js_marker` + the flow result). Device screenshots pending.
### G11-c Compat honesty
Unavailable capability/app → clear CompatReport. Evidence: screenshot.
Written side: unchanged — availability/CompatReport machinery from P2/P8 covers all runtimes through the same broker envelopes (the JS sample's launch failure path keeps evidence honest). Device screenshot pending.
### G11-d Plugin
A plugin APK/in-app module is used by two tasks with zero task changes. Evidence: logs.
Written side (session 19, ADR-079): the FULL contract is written and JVM-tested — `ICapabilityProvider` AIDL + the dependency-free `PluginCapabilityService` base (`:plugin-sdk`), the pure `PluginHost` (api band, fail-closed describe(), host-collision refusal, `PermissionIndex.mergedWith`), app `PluginController` discovery/binding, and `samples/plugin-example` (standalone buildable APK, README with the two-task F4 recipe). The device transcript (install example → two tasks declare `plugin.example.clock` → upgrade the plugin → re-run unchanged) is the phone item.
### G11-e Release hygiene
Licence CI green, `THIRD_PARTY_NOTICES.md` complete, release build has the Dev API disabled, upgrade/migration tests pass. Evidence: CI run.
Written side (sessions 18–19): `UpgradeMigrationTest` (manifest migration chain, HOST_TOO_OLD, SemVer §11 ordering, purity) + `SchemaMigrationTest` (Room) exist; `THIRD_PARTY_NOTICES.md` covers every catalog dependency — enforced by `tools/licence_check.py`; the release signing config reads env vars with the keystore outside the repo and `docs/RELEASE.md` carries the hardening checklist (Dev API off, reverse bridge opt-in per session, switches off, backup secret-free by ADR-082). The previously reported `.github/workflows/ci.yml` is absent from the current checkout (only `android-debug-apk.yml` is tracked; ADR-085), so the CI run URL remains blocked until a verification workflow is restored and run.

## Epoch V — verify what exists (v4; ADR-097, ADR-101; tasks X-17, X-18, X-07, X-08, X-09)
Not a numbered gate: the exit criterion of Epoch V is **X-09**. Order: (1) `scripts/test-jvm.sh` — finish the JVM sweep and record every failing test by name (the owner's 2026-10-26 run reported 5 failing `:core:model` tests and a stop in `:core:engine:testDebugUnitTest`; those reports are unverified here); (2) `scripts/build-android-debug.sh` — `app-core-debug.apk` builds; (3) `scripts/test-python.sh` with PyYAML installed — the three environment-dependent failures resolve or are retagged with a real reason; (4) retag the ladder module by module, each tag with evidence (command, date, environment); (5) restore a verification workflow (X-08) and add a Gradle wrapper (X-07). The three scripts are the canonical commands (ADR-096); no Gradle wrapper and no `ci.yml` are in the checkout (ADR-085). Phase 12+ implementation starts after X-09 is recorded; Python-only and tooling items may start earlier.

**Step 0 — the crash-fix build (X-17, then X-18; ADR-101).** The app crashed at launch and the crash-fix pass (`CRASH_FIX_PLAN.md`, `worklog.md` Session 20) was applied as text edits that were never compiled. Only with the owner's explicit approval: Checkpoint A (`:core:broker:test :capabilities:shell:test :core:engine:test`, then `:app:assembleCoreDebug :testing:fixture-app:assembleDebug`, record the APK sha256) and Checkpoint B (every JVM test, in the order of `scripts/test-jvm.sh`; Windows commands in `HANDOFF.md` §0.4). Then the owner's phone test (X-18, `CRASH_FIX_PLAN.md` §8): uninstall the old build, install, the app opens with no crash report, Setup and Library open, the bubble appears during a UI run, the Inspector survives an app switch; evidence names `device: <model>, Android <n> / API <n>`. Steps (1)–(3) above follow X-17, not before it.

**Work that needs no Gradle (may run while waiting for the owner's approval):** X-19 (`tests/test_architecture.py`) and the SDK/simulator half of X-12 (failing test first, then the fix, then `python tools/sync_sdk.py` and both Python suites). **After the sweep:** the JVM halves of X-12 and X-13, then X-21, X-14, X-15, X-16, X-20 — each starts with a failing test (ADR-089, ADR-098, ADR-099). X-22 waits for the owner's decision D-23.

## Gate 12 — Hardened actions and scope closure *(v4; real device where marked; see `docs/ACTION_HARDENING.md`)*
*Every finding A1–A9 is a prediction from reading code until a test or an emulator run reproduces it (ADR-089). Failing test first.*
### G12-a Conformance and vocabulary
Procedure: the `ReturnShape` parser reads every `returns` string in `schemas/capability-specs.w1.json` and compares it with what the provider returns (JVM test in `capabilities/a11y`); the first red case is `device.ui.click` `method` (A6). After the fix and the additive `effect`/`warnings` fields: `python3 tools/gen_artifacts.py --check`, `python3 tools/sync_sdk.py --check`, the SDK suite, the simulator suite and the flow/schedule vector drift checks are green. Evidence: test names + command output.
### G12-b Pure units
Procedure: `ChangeDetector` (changed / unchanged / unverifiable), hotspot refinement, node identity guard and the ported upstream tests (REUSE_MATRIX RM-G2) plus new red-then-green tests run on the JVM. Evidence: test names and run output (tag `(UNIT-TESTED)` only for the units actually run).
### G12-c Emulator reproduce-or-refute
Procedure: API 30, 31 and 34 images, fixture app with the Phase 12 surfaces (plain field, toggle, delayed transition, silent-failure field, large clickable container with an off-centre child, nested scrollable, slow gesture target). For each of A1–A5: run **before** the fix and record *reproduces* or *does not reproduce*, then after. Evidence per item: image, API level, test name, before/after result. Integration-tested only.
### G12-d Real-device fixture action suite *(real device)*
Procedure: on **both** owner phones run the fixture action suite: `set_text` on a plain field (no doubled text), one click on the toggle (state flips once, never twice), `scroll_until` to a target in a nested scrollable, awaited tap/swipe/long-press complete; record `adb shell wm size` (settles A4). Evidence: run logs + `device: <model>, Android <n> / API <n>` for each phone. With one phone only, record that phone's result and leave the item unticked.
### G12-e Overlay and touch audit
Procedure: audit every overlay window (bubble, capsule, glow) for touchable/focusable flags against injected gestures (REUSE_MATRIX RM-F4, two-window rule); the tap-through test injects a tap at a point under the bubble and asserts it reaches the app below (emulator). Evidence: audit table + test name.
### G12-f Scope closure
Procedure (P12-16, P12-17): JVM tests — a ref minted in run A does not resolve in run B; `device.ui.act` with a ref whose package is outside the grant fails with `SCOPE_VIOLATION`; `allow_coordinates=false` refuses a coordinate click; `allow_global=false` refuses a global action; `device.app` `list` and `foreground` return only granted packages. Repeat on the emulator with the fixture app plus a second app. Evidence: test names.

## Gate 13 — Agent runtime, Stage A *(v4; optional feature; see `docs/AGENT_RUNTIME.md` §14a)*
*Never count a scripted-model run as a real model. A `(real device)` item without a `device:` clause is not passed.*
### G13-a JVM integration
Procedure: real `RunOrchestrator` + `BrokerPipeline` + a fake provider + `FakeLlm`. For each budget class (turns, wall clock, model calls, tokens, actions, consecutive failures, idle) a scripted runaway ends with that class's reason code; cancel during a model call and during a broker call completes within 2 s; the secret and token canaries are absent from events, audit and evidence. Evidence: test names.
### G13-b Injection and BLOCKED fixtures *(emulator)*
Procedure: the PC `testing/stub-model/stub_model.py` over `adb reverse` (loopback, `ai_cleartext_loopback` on). The fixture app shows on-screen text that tries to widen grants or call tools outside the catalog — the run's tool calls stay inside the catalog and the grant; a fixture in a BLOCKED package is never shown to the model (check the stub's request log) and an action there is refused. Evidence: stub request log + run export + test names.
### G13-c Cloud-model run *(real device; needs X-15)*
Procedure: with the owner's own provider key, a 5-step fixture task succeeds under the **shipped** grant model (the evidence states the grant mode; dev-mode grant-all does not count); Stop completes within 6 s; the supervised confirm path ends the run only after the user confirms. Evidence: run export + `.aarun` + `device:` clause.
### G13-d Loopback cleartext *(real device; needs X-14)*
Procedure: with `ai_cleartext_loopback` off an `http://127.0.0.1` endpoint is refused with a typed reason; with it on, the stub model over `adb reverse` works; a non-loopback `http://` URL is refused in both states. Evidence: logs + `device:` clause.
### G13-e Eval baselines
Procedure: `testing/agent-evals` scenarios (each with `expect` clauses) run against the fixture app; baselines are recorded once; only movement from the baseline fails the run. Evidence: eval report + the recorded baselines file.
### G13-f Canaries
Procedure: a canary secret and a canary run token are registered; run an agent task; grep logs, events, audit, evidence text, `.aarun` and a backup export — zero hits. Evidence: test name + grep output.

## Gate 14 — Run UI: capsule, approvals, agent run detail *(v4)*
### G14-a Capsule states
Procedure: every capsule state is reachable in state-holder tests (one holder serves the in-app bar and the overlay); Stop is visible in every running state. Evidence: test names + screenshots.
### G14-b Approval flow *(emulator)*
Procedure: a risky action raises an approval (`ui.prompt.ask kind:"choice"`); approve and deny from the sheet and from the notification action; no answer within the window → deny. Evidence: recording + test names.
### G14-c Frame time *(real device)*
Procedure: capsule, run detail and Ask sheet on **both** owner phones: frame-time and jank numbers (`dumpsys gfxinfo`) recorded in `docs/PERF.md`. Evidence: numbers + `device:` clause per phone.
### G14-d Accessibility pass
Procedure: TalkBack walk-through of Ask, approval and capsule recorded; spike S7 (overlay indicator on the owner phones) recorded in `docs/SPIKES.md`. Evidence: recording + SPIKES section.

## Gate 15 — Footprint: measure first *(v4)*
### G15-a Harness numbers *(real device)*
Procedure: the measurement harness records, per flavor and ABI, APK size, cold start, resident memory idle and during a run, and worker spawn time on **both** phones; numbers go to `docs/PERF.md`. No percentage targets (ADR-093). Evidence: numbers + `device:` clauses.
### G15-b Lite flavor
Procedure: flavor dimension `runtime` = `py` | `lite`; `lite` builds; a Python task on `lite` gets a typed CompatReport row instead of a crash; flow, JS and agent runs work (JVM + emulator). Evidence: build output + test names.
### G15-c 16 KB audit
Procedure: for every shipped native library record the ELF segment alignment (e.g. with `llvm-readelf -lW` or `zipalign -c -P 16`) and the Chaquopy wheel status; the audit table lists each library and the verdict. Evidence: audit table. *(Play's 16 KB requirement applies from 2027-02-01 for targetSdk ≥ 35 — verify the current rule before acting on it.)*
### G15-d Tuning before/after *(real device)*
Procedure: each tuning change (worker-pool size, R8 rules, event-mask narrowing) records before and after numbers from the harness on the same phone. Evidence: before/after table + `device:` clause.

## Gate 16 — Optional local inference *(v4; NO-GO for agent loops until S6 passes; see `docs/LOCAL_INFERENCE.md`)*
*Phase 16 can be dropped without affecting Phases 12–15; a recorded NO-GO is a valid outcome for G16-a and G16-d.*
### G16-a S6 verdict *(real device)*
Procedure: run spike S6 (`docs/SPIKES.md`) on **both** owner phones against the recorded criteria (peak memory on AVAILABLE RAM, tokens/s, time-to-first-token, thermal behaviour, survival of a background kill). Evidence: S6 section with numbers per phone.
### G16-b Model artifact pipeline *(real device)*
Procedure: download a small model, verify its hash, kill the app mid-download, resume, install. Evidence: log + hash + `device:` clause.
### G16-c Process isolation *(real device)*
Procedure: kill the `:llm` process (and, separately, drive it out of memory); the app and its runs stay alive and report a typed local-model failure. Evidence: `adb shell ps` before/after + run export.
### G16-d Helper use *(real device)*
Procedure: one local helper use (classification or extraction) runs inside the latency profile recorded in G16-a, **or** the NO-GO is recorded with the reason. Evidence: latency table or the NO-GO entry in `docs/SPIKES.md`.
