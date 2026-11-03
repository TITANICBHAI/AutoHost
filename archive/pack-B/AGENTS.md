> **ARCHIVED COPY — pack B, not instructions.** The current instructions are `HANDOFF.md` §0 and `AGENTS.md` in the repository root (two folders up). See `archive/README.md`.

# AGENTS.md — AutoHost

You are the implementing agent for **AutoHost**: a Kotlin Android app (`minSdk 29` → latest) that hosts user-written automations — Python scripts, declarative flows or an LLM agent loop — and gives them real device capabilities (UI control of other apps, app launch/stop, notifications, network, files, events, optional shell-level power), with per-task settings, profiles, UIs, schedules, run tracking and recovery, plus a Dev API/MCP server so coding agents can drive the phone while authoring.
You do not decide *what* to build — the docs do. You decide *how to implement it well* and you prove it with evidence.

## 1. Read order (every new session)
1. This file.
2. `HANDOFF.md` — repository state, blockers, build instructions and the exact next task.
3. `docs/PROGRESS.md` — find the current phase and the next unticked task. That task is your assignment.
4. The docs named for that task in the map below (for any ARIA/ClosePaw reuse: `docs/REUSE_MATRIX.md`).
5. The `plan.md` sections the task cites.

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
| What to reuse from ARIA and ClosePaw, per file, and how it is proven | `docs/REUSE_MATRIX.md` |
| Staged-import path map, hashes and statuses | `docs/MIGRATION_NOTES.md`, `docs/migration-manifest.tsv` |
| Checks actually run vs not run | `docs/VALIDATION_REPORT.md` |
| Repository state and the exact next task | `HANDOFF.md` |
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
| Phase kickoff prompts | `prompts/*.md` |

**Precedence:** `plan.md` defines behaviour; `docs/*` define file-level structure and presentation. If two documents disagree, do not guess: record it in `docs/DECISIONS.md`, fix whichever is wrong, and continue.

## 3. Non-negotiable rules
1. **Separate implementation order from verification tracking.** Implementation follows phase scope and dependencies. During the verification epoch, follow `HANDOFF.md` §5 and tick each independently verified task immediately with its evidence, even if an earlier real-device gate remains open. A gate stays unticked until its own required evidence exists; `python tools/progress.py --check` enforces evidence-on-tick, not phase order. Tag the highest ladder state the evidence proves (`(COMPILED)`, `(UNIT)`, `(INTEG)`, `(DEVICE)`); a failed verification keeps the item unticked with `(FAILING: reason)` and the command and error on its evidence line (ADR-086).
2. **Fail closed.** Unknown state, missing capability, ungranted permission, stale node, malformed input → typed error with an actionable message. Never guess, never continue silently.
3. **No app-specific or service-specific code** outside `samples/` and `testing/`.
4. **`Build.VERSION.SDK_INT` is read only in `ApiGate` and provider `availability()`.** Version-dependent behaviour is an ordered provider chain (plan §3.5). API-specific calls live in tiny `*ApiNN` objects.
5. **All device/service access goes through the broker interceptor chain.** Scripts and flows never get raw handles. Providers register a `CapabilitySpec`; SDK stubs/docs are generated from specs.
6. **Secrets never leave `SecretStore`** except per call to the broker for a declared field. Never log them; never put them in checkpoints, KV, evidence text or exports. The canary-secret test must stay green.
7. **Visible and stoppable.** Any run that acts on the UI shows the ongoing notification with Stop; the global kill switch always works.
8. **Boundaries:** no CAPTCHA solving/bypass, no stealth or identity spoofing, no rate-limit or usage-limit circumvention, no credential harvesting. Challenges → `WAITING_USER`. (plan §0.4)
9. **Clean-room for AGPL/GPL references.** Mobilerun Portal (AGPL) and OpenAutoJs (GPL + NC) are read for *ideas only*. Never paste or closely paraphrase their code. Apache/MIT sources may be adapted with notices recorded in `THIRD_PARTY_NOTICES.md`. **ClosePaw** is Apache-2.0: keep file headers and NOTICE, never use its name or mascot, never the proprietary LiquidAI Leap SDK. **ARIA** has no licence file: its staged files stay locked until D-19 says CONFIRMED (`tools/check_docs.py` enforces it). (`docs/REFERENCES.md` §0, ADR-093)
10. **Verify on real hardware.** Gates marked *(real device)* run on the primary Android 10 phone. Paths only exercised on emulators are labelled `UNVERIFIED-on-hardware` in `docs/DEVICES.md`.
11. **Do not invent scope.** If it isn't in a task, a doc, or a decision, ask or log it in `DECISIONS.md` — don't build it.
12. **Compile early.** Build and unit-test the module after every task (`gradle :<module>:check`) and fix errors in that task; never batch build problems. Real-device verification is tracked separately and is the only thing that earns `(DEVICE)` (ADR-086).
13. **Imported is not integrated.** Never edit or build anything under `reference/` or `*/imported/`. Adapt one file at a time into `src/main/kotlin/app/autohost/…`, update its row in `docs/migration-manifest.tsv`, keep `python3 tools/check_docs.py` green. No `com.ariaagent`, `ai.closepaw` or `ai.liquid` package in compiled code (ADR-091).
14. **One lifecycle, one broker, one action path.** Runs start only through `RunOrchestrator`; every device effect goes through the broker and a provider; the agent is a `RuntimeProvider`, never a second orchestrator (ADR-089).
15. **No fake success.** An unavailable provider, model or capability returns a typed error (`CAPABILITY_UNAVAILABLE`, `MODEL_UNAVAILABLE`). Stubs and fabricated actions exist only in test source sets; a stub is never counted as inference (ADR-090).
16. **Languages.** Kotlin for the platform and every hot path; Python only for user scripts, the SDK and PC tools; C++ only for the optional llama.cpp bridge; nothing heavy on the main thread (plan §5.8, ADR-088).
17. **Screen text is untrusted.** Model output and anything read from the screen can never grant a permission, approve an action, change the tool list or raise a limit (plan §14.7). The run token never appears in a log, event, export or prompt (ADR-094).

## 4. What NOT to do
- Don't add a DI framework (no Hilt/Dagger/Koin). Construct services manually through `AppGraph` / `WorkerGraph`.
- Don't mock our own classes. Use fakes (`FakeDevice`, `FakeProvider`, `FakeClock`, `FakeModelProvider`) from `testing/fakes`; ClosePaw tests that use MockK are ported to fakes.
- Don't copy a subsystem because it exists, and don't keep a second copy of anything the matrix marks *Reference only*.
- Don't call `System.currentTimeMillis()` or `Random` directly — use `Clock` / `Ids`.
- Don't block the main thread; don't use `GlobalScope`; don't swallow exceptions — classify them (retryable vs terminal) and return `AhResult`.
- Don't trust accessibility reads: always go through `ReadGate`, `TraversalGuard`, `RootResolver` (plan §5.7).
- Don't set `isAccessibilityTool` on the accessibility service.
- Don't hand-edit generated files (`sdk-python/**/_generated`, `docs/CAPABILITIES.md`, `docs/SDK.md`).
- Don't leave debug endpoints (Dev API, fixtures, bridge) enabled in release builds by default.
- Don't commit keystores, `local.properties`, real credentials, or personal screenshots.

## 5. Working loop
1. During the verification epoch, follow `HANDOFF.md` §5's check order, then take the lowest-ID unticked, unblocked independent item in `docs/PROGRESS.md`. Never infer device-gate success from code or non-device tests.
2. Read its docs. Write tests first where logic is pure (selectors, schedule engine, validators, redactor, policies, state machines).
3. Implement in the modules/files named in `docs/REPO_LAYOUT.md`. If you must deviate, log an ADR.
4. Build and test the module now (`gradle :<module>:check`, plus instrumented tests / device steps if device-touching). Fix build problems before moving on.
5. Set the highest ladder tag the evidence proves — `(WRITTEN)` → `(COMPILED)` → `(UNIT)` → `(INTEG)` → `(DEVICE)` — and fill the `evidence:` line (command, commit hash, test name, log/screenshot path, device + API level). Tick `[x]` only when the item's required level (default UNIT, or `(needs: …)`) is reached, its `(deps: …)` are ticked and its gate rules allow it.
6. Run `python3 tools/progress.py` (updates the dashboard), `python3 tools/progress.py --check` and `python3 tools/check_docs.py` (all must pass).
7. Commit: `P1-06: <summary>` (task id first). Small commits.
8. If blocked: mark the task `(BLOCKED: reason)`, add it to PROGRESS.md "Blockers", continue with the next independent task.

**A task is done only when:** code merged, tests pass at the required level, specs/docs updated, evidence and ladder tag recorded, dependencies ticked, both checks pass.

## 6. Commands (fill in during P1-01 and keep current)
```
# Works on any machine with Python 3.10+ (no Android tooling):
cd sdk-python && python3 -m unittest discover -s tests -t tests -p "test_*.py"

./gradlew check                           # unit tests + lint + detekt/ktlint
./gradlew :app:assembleCoreDebug          # debug APK (flavor core)
./gradlew connectedCoreDebugAndroidTest   # instrumented tests (device/emulator attached)
python3 -m unittest discover -s tests -p "test_*.py"   # schemas + tracker/docs tools
python3 tools/sync_sdk.py --check
python3 tools/progress.py [--check] [--stats] [--html docs/progress.html]
python3 tools/check_docs.py               # ids, links, paths, staged-import hashes, Gradle isolation
```

## 7. When to ask the human
Ask once, early, only for: device facts (Phase 0), anything requiring a physical action on the phone (enable a service, tap a prompt, plug in USB), and decisions that change scope, licence or security posture (D-15 model provider and key, D-16 sensitive apps, D-19 ARIA authorship and licence). Everything else: use the defaults in `docs/DECISIONS.md` and move on.
