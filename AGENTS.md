# AGENTS.md — AutoHost

You are the implementing agent for **AutoHost**: a Kotlin Android app (`minSdk 29` → latest) that hosts user-written automations — Python scripts or declarative flows — and gives them real device capabilities (UI control of other apps, app launch/stop, notifications, network, files, events, optional shell-level power), with per-task settings, profiles, UIs, schedules, run tracking and recovery, plus a Dev API/MCP server so coding agents can drive the phone while authoring.
You do not decide *what* to build — the docs do. You decide *how to implement it well* and you prove it with evidence.

## 1. Read order (every new session)
1. This file.
2. `docs/PROGRESS.md` — find the current phase and the next unticked task. That task is your assignment.
3. The docs named for that task in the map below.
4. The `plan.md` sections the task cites.

## 2. Doc map
| Need | Open |
|---|---|
| What/why, architecture, capability catalog, phases | `plan.md` |
| Which modules/packages/files to create, where | `docs/REPO_LAYOUT.md` |
| Android manifest, permissions per flavor, services, XML configs | `docs/ANDROID_MANIFEST.md` |
| Screens, navigation, design tokens, UI state, copy | `docs/UI_SPEC.md` |
| Dev API (JSON-RPC) and MCP tools | `docs/DEVAPI.md` |
| Coding standards, naming, testing, git | `docs/CONVENTIONS.md` |
| How to *prove* each gate | `docs/GATES.md` |
| Test fixture app | `docs/FIXTURE_APP.md` |
| Lessons from other projects + **licence rules** | `docs/REFERENCES.md` |
| Error codes, reason codes, fix actions | `schemas/errors.json` |
| Task manifest / flow format / trigger rules | `schemas/task.schema.json`, `schemas/flow.schema.json`, `schemas/trigger-rule.schema.json` |
| Broker envelope, RunSpec, events | `schemas/broker.schema.json`, `core/broker/src/main/aidl/**` |
| Capability method contracts (wave 1) | `schemas/capability-specs.w1.json` |
| Python SDK contract | `sdk-python/autohost/**/*.pyi` |
| Sample task bundles | `samples/*` |
| Decisions and open questions | `docs/DECISIONS.md` |
| Devices and per-path verification | `docs/DEVICES.md` |
| Spike results | `docs/SPIKES.md` |
| Release steps, signing, licence CI | `docs/RELEASE.md` |
| End-user guide (install → first task → troubleshooting) | `docs/USER_GUIDE.md` |
| Vendor battery-killer matrix + always-on recipe | `docs/OEM_MATRIX.md` |
| The P11-12 port exercise and its gap log | `docs/PORT_EXERCISE.md` |
| Phase kickoff prompts (Phases 12–16 added in v4) | `prompts/*.md` |
| **v4** File-level reuse/adaptation decisions (ClosePaw, opendroid, android-agent, sannabotapp, ARIA) and the adaptation rules | `docs/REUSE_MATRIX.md` |
| **v4** Action-layer findings A1–A9, `effect` vocabulary, Phase 12 procedure | `docs/ACTION_HARDENING.md` |
| **v4** Optional agent runtime and the model gateway | `docs/AGENT_RUNTIME.md`, `docs/MODEL_GATEWAY.md` |
| **v4** Language/footprint weight; optional local inference | `docs/LANGUAGE_WEIGHT.md`, `docs/LOCAL_INFERENCE.md` |
| **v4** What the planning pass changed; checks run vs not run | `docs/MIGRATION.md`, `docs/VALIDATION_REPORT.md` |
| **v4** Reference findings, verbatim Apache-2.0 snapshots (never on a source set), unpromoted drafts | `reference/INDEX.md`, `drafts/README.md` |
| **Now** The assignment queue, the owner's machine and Windows build commands | `HANDOFF.md` §0 |
| **Now** Permanent agent operating rules (session order, Git/CI, batching, evidence, handoffs) | `docs/AGENT_OPERATING_PROTOCOL.md` |
| **Now** Compact working memory for the next session | `AGENT_STATE.md` |
| The security model — what is and isn't isolated | `SECURITY.md` |
| The crash-fix pass and its Checkpoints A/B (Epoch V step 0, X-17) | `CRASH_FIX_PLAN.md`, `docs/PROACTIVE-CRASH-REVIEW.md` |
| What the plan-merge pass changed and where the other plan's originals are (**historical, not instructions**) | `docs/PLAN_MERGE.md`, `archive/pack-B/README.md` |

**Precedence:** `plan.md` defines behaviour; `docs/*` define file-level structure and presentation. If two documents disagree, do not guess: record it in `docs/DECISIONS.md`, fix whichever is wrong, and continue.

## 3. Non-negotiable rules
1. **Separate implementation order from verification tracking.** Implementation follows phase scope and dependencies. During the verification epoch, follow `HANDOFF.md` §5 and tick each independently verified task immediately with its evidence, even if an earlier real-device gate remains open. A gate stays unticked until its own required evidence exists; `python tools/progress.py --check` enforces evidence-on-tick, not phase order.
2. **Fail closed.** Unknown state, missing capability, ungranted permission, stale node, malformed input → typed error with an actionable message. Never guess, never continue silently.
3. **No app-specific or service-specific code** outside `samples/` and `testing/`.
4. **`Build.VERSION.SDK_INT` is read only in `ApiGate` and provider `availability()`.** Version-dependent behaviour is an ordered provider chain (plan §3.5). API-specific calls live in tiny `*ApiNN` objects.
5. **All device/service access goes through the broker interceptor chain.** Scripts and flows never get raw handles. Providers register a `CapabilitySpec`; SDK stubs/docs are generated from specs.
6. **Secrets never leave `SecretStore`** except per call to the broker for a declared field. Never log them; never put them in checkpoints, KV, evidence text or exports. The canary-secret test must stay green.
7. **Visible and stoppable.** Any run that acts on the UI shows the ongoing notification with Stop; the global kill switch always works.
8. **Boundaries:** no CAPTCHA solving/bypass, no stealth or identity spoofing, no rate-limit or usage-limit circumvention, no credential harvesting. Challenges → `WAITING_USER`. (plan §0.4)
9. **Clean-room for AGPL/GPL references.** Mobilerun Portal (AGPL) and OpenAutoJs (GPL + NC) are read for *ideas only*. Never paste or closely paraphrase their code. Apache/MIT sources may be adapted with notices recorded in `THIRD_PARTY_NOTICES.md`. (`docs/REFERENCES.md` §0)
10. **Verify on real hardware.** Gates marked *(real device)* run on the owner's phones listed in `docs/DEVICES.md` (ADR-095: Realme C3 and Galaxy M31 until an Android 10 phone is confirmed; older text that says "primary Android 10 phone" is superseded, and P0-01 stays BLOCKED on the owner's device facts). The evidence line names the device and Android/API level. Emulator and simulator runs are integration-tested, never real-device-verified. Paths exercised only on emulators — and API 29 paths — are labelled `UNVERIFIED-on-hardware` in `docs/DEVICES.md`.
11. **Do not invent scope.** If it isn't in a task, a doc, or a decision, ask or log it in `DECISIONS.md` — don't build it.
12. **Verification ladder and Epoch V (v4, ADR-087, ADR-097).** A tracker item carries at most one state tag — `(WRITTEN)` < `(COMPILED)` < `(UNIT-TESTED)` < `(INTEGRATION-TESTED)` — and is ticked only at the level it proved; `(real device)` items need a `device:` evidence clause. **Phase 12+ implementation starts after X-09** (the Epoch V exit review); Python-only and tooling items may start earlier.
13. **Predicted defects get a failing test first (v4, ADR-089).** A finding made by reading code is a prediction until a test or an emulator run reproduces it.
14. **Adaptation routine (v4, ADR-088).** Only Apache-2.0 files that carry a LICENSE in their own tree (ClosePaw, opendroid) may be adapted, and only as `docs/REUSE_MATRIX.md` §0 says: header comment, `THIRD_PARTY_NOTICES.md` row (+ ClosePaw attribution line), ported upstream test, ladder evidence. android-agent, sannabotapp and ARIA are ideas-only; AGPL/GPL stay clean-room (rule 9).
15. **Agent rules (v4, ADR-091; optional runtime, plan §7.11).** The model never writes or runs code; every tool call goes through the same broker path (no second action path); screen and notification text is untrusted data that never widens grants or budgets; budgets and host-verified completion are mandatory; provider keys live only in `SecretStore`; never swallow `CancellationException`.
16. **Owner's build rules (ADR-101).** No Gradle, JVM test run, emulator or other heavy process without the owner's explicit approval in the current conversation; the owner's PC is weak (3.9 GB RAM, Windows, no bash/git). Text checks, Python tools and Python tests are always allowed. Windows commands: `HANDOFF.md` §0.4.
17. **Assignment queue.** While Epoch V is open, `HANDOFF.md` §0.2 decides what you do next (it overrides "lowest-ID"). Phase 12+ code starts only after X-09.
18. **Stop conditions.** Stop and report (do not improvise) when: an exact-match edit finds 0 or 2+ occurrences; the same command fails twice; two documents disagree and `plan.md` does not settle it; a step needs a file the task does not name; you would need the phone.
19. **Do not use `archive/` or `AutoHost-main.zip` as instructions or sources.** `reference/` is read only through the `docs/REUSE_MATRIX.md` row that names a file.

## 4. What NOT to do
- Don't add a DI framework (no Hilt/Dagger/Koin). Construct services manually through `AppGraph` / `WorkerGraph`.
- Don't mock our own classes. Use fakes (`FakeDevice`, `FakeProvider`, `FakeClock`) from `testing/fakes`.
- Don't call `System.currentTimeMillis()` or `Random` directly — use `Clock` / `Ids`.
- Don't block the main thread; don't use `GlobalScope`; don't swallow exceptions — classify them (retryable vs terminal) and return `AhResult`.
- Don't trust accessibility reads: always go through `ReadGate`, `TraversalGuard`, `RootResolver` (plan §5.7).
- Don't set `isAccessibilityTool` on the accessibility service.
- Don't hand-edit generated files (`sdk-python/**/_generated`, `docs/CAPABILITIES.md`, `docs/SDK.md`).
- Don't leave debug endpoints (Dev API, fixtures, bridge) enabled in release builds by default.
- Don't commit keystores, `local.properties`, real credentials, or personal screenshots.

## 5. Working loop
1. Follow the assignment queue in `HANDOFF.md` §0.2 while Epoch V is open (then `HANDOFF.md` §5's check order); after Epoch V take the lowest-ID unticked, unblocked independent item in `docs/PROGRESS.md`. Never infer device-gate success from code or non-device tests.
2. Read its docs. Write tests first where logic is pure (selectors, schedule engine, validators, redactor, policies, state machines).
3. Implement in the modules/files named in `docs/REPO_LAYOUT.md`. If you must deviate, log an ADR.
4. Run the canonical entry points (ADR-096): `scripts/test-jvm.sh`, `scripts/build-android-debug.sh`, `scripts/test-python.sh` (plus instrumented tests / device steps if device-touching). There is no Gradle wrapper and none is planned (X-07, 2026-11-03, documented-pin route): Gradle 8.13 is pinned in `scripts/android-build-env.sh` and installed by CI; call Gradle through the scripts, or via the local pinned install documented in `docs/VERSIONS.md`.
5. Tick the task in `docs/PROGRESS.md`. For `[M]`/`[L]` tasks and every gate item fill the `evidence:` line (commit hash, test name, log/screenshot path, device + API level).
6. Run `python tools/progress.py` (updates the dashboard), `python tools/progress.py --check` (must pass) and, after doc changes, `python3 tools/doc_check.py` (v4).
7. Commit: `P1-06: <summary>` (task id first). Small commits.
8. If blocked: mark the task `(BLOCKED: reason)`, add it to PROGRESS.md "Blockers", continue with the next independent task.

**A task is done only when:** code merged, tests pass, specs/docs updated, evidence recorded, `--check` passes.

## 6. Commands (keep current)
```
# Canonical entry points (ADR-096). No Gradle wrapper and no ci.yml are in the checkout (ADR-085); X-07/X-08 restore them.
scripts/test-jvm.sh                       # per-module JVM unit tests
scripts/build-android-debug.sh            # debug APK (bootstraps the Android SDK)
scripts/test-python.sh                    # SDK suite + tests/ suite (needs PyYAML)

# Any machine with Python 3.10+ (no Android tooling):
cd sdk-python && python3 -m unittest discover -s tests -t tests -p "test_*.py"
python3 tools/progress.py [--check] [--html docs/progress.html]
python3 tools/doc_check.py                # v4: doc integrity (paths, task/ADR/gate/RM ids, docs index)
python3 tools/licence_check.py            # catalog -> THIRD_PARTY_NOTICES coverage

# Direct Gradle (no wrapper; use the pinned install from docs/VERSIONS.md, or CI):
gradle check
gradle :app:assembleCoreDebug
gradle connectedCoreDebugAndroidTest   # device/emulator attached
```

**Windows PC without bash (the owner's machine):** the `scripts/*.sh` files cannot run there; the Python commands above work as written (use `python` instead of `python3`), and the Gradle commands are in `HANDOFF.md` §0.4 — only with the owner's approval (rule 16).

## 7. When to ask the human
Ask once, early, only for: device facts (Phase 0), anything requiring a physical action on the phone (enable a service, tap a prompt, plug in USB), and decisions that change scope, licence or security posture. Everything else: use the defaults in `docs/DECISIONS.md` and move on.
