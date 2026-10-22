# GATES — how to prove each phase is done
A gate item is **passed** only when the procedure was executed and the **evidence** is recorded next to the item in `docs/PROGRESS.md` (`evidence:` line) — commit/test name, log or screenshot path, **device + API level**. A green build alone never passes a gate. *(real device)* = the primary Android 10 phone unless stated.
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
### G8-a Template
≥12 capabilities shipped through the §6.3 template (spec, provider, availability, stubs, docs, tests, showcase). Evidence: checklist table.
### G8-b Switches
Insecure-feature switches default off and are enforced (tests); CompatReport shows "disabled by setting". Evidence: tests.
### G8-c Raw bridge
Blocked without grant+switch; works with both. Evidence: test.
### G8-d Triggers
Notification-, webhook- and chain-triggered tasks run end to end. Evidence: logs.
### G8-e Rule behaviours
Cooldown, debounce, busy-queue (queue/drop/replace) proven by tests. Evidence: test names.
### G8-f Lazy permissions
Android permissions requested only when needed, with explanation. Evidence: recording.

## Gate 9 — Shell tier
### G9-a Provider swap
Accessibility disabled → `settings-reader` passes via the shell provider. Evidence: run export.
### G9-b Self-heal
Service re-enabled after a forced disable via secure settings. Evidence: log.
### G9-c Allowlist
Allowlist and switches enforced by tests. Evidence: tests.
### G9-d Reboot
Reboot/re-arm behaviour documented and reminder shown. Evidence: screenshot + doc.
### G9-e Exclusivity
UiAutomation provider and accessibility service never active together. Evidence: test.

## Gate 10 — Browser, vision, data/hardware
### G10-a Web login
A task logs into a **local test web page** and scrapes it. Evidence: run export.
### G10-b OCR
OCR reads a screenshot region. Evidence: log.
### G10-c Challenges
Challenge pages are detected and routed to `WAITING_USER`; never bypassed. Evidence: run export.

## Gate 11 — Final acceptance
### G11-a New task from the pack
A brand-new task written from the generated pack with **no Kotlin changes** is installed, profiled and scheduled. Evidence: task + transcript.
### G11-b Three runtimes
Python, flow and JS tasks coexist with independent state. Evidence: screenshots.
### G11-c Compat honesty
Unavailable capability/app → clear CompatReport. Evidence: screenshot.
### G11-d Plugin
A plugin APK/in-app module is used by two tasks with zero task changes. Evidence: logs.
### G11-e Release hygiene
Licence CI green, `THIRD_PARTY_NOTICES.md` complete, release build has the Dev API disabled, upgrade/migration tests pass. Evidence: CI run.
