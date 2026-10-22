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
| Phase kickoff prompts | `prompts/*.md` |

**Precedence:** `plan.md` defines behaviour; `docs/*` define file-level structure and presentation. If two documents disagree, do not guess: record it in `docs/DECISIONS.md`, fix whichever is wrong, and continue.

## 3. Non-negotiable rules
1. **One phase at a time.** Do not tick tasks in phase N+1 until every gate item of phase N is ticked *with evidence*. `python tools/progress.py --check` enforces this.
2. **Fail closed.** Unknown state, missing capability, ungranted permission, stale node, malformed input → typed error with an actionable message. Never guess, never continue silently.
3. **No app-specific or service-specific code** outside `samples/` and `testing/`.
4. **`Build.VERSION.SDK_INT` is read only in `ApiGate` and provider `availability()`.** Version-dependent behaviour is an ordered provider chain (plan §3.5). API-specific calls live in tiny `*ApiNN` objects.
5. **All device/service access goes through the broker interceptor chain.** Scripts and flows never get raw handles. Providers register a `CapabilitySpec`; SDK stubs/docs are generated from specs.
6. **Secrets never leave `SecretStore`** except per call to the broker for a declared field. Never log them; never put them in checkpoints, KV, evidence text or exports. The canary-secret test must stay green.
7. **Visible and stoppable.** Any run that acts on the UI shows the ongoing notification with Stop; the global kill switch always works.
8. **Boundaries:** no CAPTCHA solving/bypass, no stealth or identity spoofing, no rate-limit or usage-limit circumvention, no credential harvesting. Challenges → `WAITING_USER`. (plan §0.4)
9. **Clean-room for AGPL/GPL references.** Mobilerun Portal (AGPL) and OpenAutoJs (GPL + NC) are read for *ideas only*. Never paste or closely paraphrase their code. Apache/MIT sources may be adapted with notices recorded in `THIRD_PARTY_NOTICES.md`. (`docs/REFERENCES.md` §0)
10. **Verify on real hardware.** Gates marked *(real device)* run on the primary Android 10 phone. Paths only exercised on emulators are labelled `UNVERIFIED-on-hardware` in `docs/DEVICES.md`.
11. **Do not invent scope.** If it isn't in a task, a doc, or a decision, ask or log it in `DECISIONS.md` — don't build it.

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
1. Pick the next unticked task in the current phase of `docs/PROGRESS.md` (lowest ID not blocked).
2. Read its docs. Write tests first where logic is pure (selectors, schedule engine, validators, redactor, policies, state machines).
3. Implement in the modules/files named in `docs/REPO_LAYOUT.md`. If you must deviate, log an ADR.
4. Run `./gradlew check` (plus instrumented tests / device steps if device-touching).
5. Tick the task in `docs/PROGRESS.md`. For `[M]`/`[L]` tasks and every gate item fill the `evidence:` line (commit hash, test name, log/screenshot path, device + API level).
6. Run `python tools/progress.py` (updates the dashboard) and `python tools/progress.py --check` (must pass).
7. Commit: `P1-06: <summary>` (task id first). Small commits.
8. If blocked: mark the task `(BLOCKED: reason)`, add it to PROGRESS.md "Blockers", continue with the next independent task.

**A task is done only when:** code merged, tests pass, specs/docs updated, evidence recorded, `--check` passes.

## 6. Commands (fill in during P1-01 and keep current)
```
./gradlew check                           # unit tests + lint + detekt/ktlint
./gradlew :app:assembleCoreDebug          # debug APK (flavor core)
./gradlew connectedCoreDebugAndroidTest   # instrumented tests (device/emulator attached)
python tools/progress.py [--check] [--html docs/progress.html]
```

## 7. When to ask the human
Ask once, early, only for: device facts (Phase 0), anything requiring a physical action on the phone (enable a service, tap a prompt, plug in USB), and decisions that change scope, licence or security posture. Everything else: use the defaults in `docs/DECISIONS.md` and move on.
