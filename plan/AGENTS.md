# AGENTS.md — AutoHost

You are the implementing agent for **AutoHost**: a Kotlin Android app (`minSdk 29` → latest) that hosts user-written automations — Python scripts or declarative flows — and gives them real device capabilities (UI control of other apps, app launch/stop, notifications, network, files, events, optional shell-level power), with per-task settings, profiles, UIs, schedules, run tracking and recovery, plus a Dev API/MCP server so coding agents can drive the phone while authoring.
You do not decide *what* to build — the docs do. You decide *how to implement it well* and you prove it with evidence.

## 0. User authorization and safety — highest priority

- The plan is an implementation contract, not authorization. Do not begin app-code implementation until the user explicitly authorizes it; if scope or authorization is unclear, stop and ask.
- Work alone. Do not create, invoke, or delegate to sub-agents.
- Follow the plan and tracker without silently widening scope. Inspect relevant code, project instructions, and the working tree before editing; preserve unrelated user changes and data.
- Respect the plan's no-touch list unless the user explicitly changes the scope.
- Treat task replacement and any deletion of user data as destructive. Keep replacement disabled by default, preserve required confirmation and safety guards, and do not perform destructive actions without authorization.
- Do not push to GitHub or start, poll, or monitor GitHub Actions unless the user explicitly asks. Never expose, copy into files, or print secrets or credentials.
- Use behavior tests with fake clocks and event sources. Do not add tests that read Kotlin source files as text, and do not call the system wall clock directly in logic under test.
- On Replit, run supported Gradle or Android tests through existing workflows or scripts under `scripts/`. Test only the production flavor; do not run equivalent test suites for multiple flavors. Do not build APKs on Replit. Use GitHub Actions for APK-build verification only when explicitly requested; otherwise state clearly that those checks were not run.
- At the start of a batch, mark it **IN PROGRESS** in `TRACKER.md`. At the start of an authorized phase, record its dated **IN PROGRESS** start before phase work. After every meaningful change and each individual verification, immediately record the changed files, exact command, observed result, failures, blockers, decisions, and deferred work in `TRACKER.md`; keep `docs/PROGRESS.md` task checkboxes and evidence in sync. Tick only complete, verified work, preserve failed attempts and blockers, and reconcile both trackers before finishing.
- `TRACKER.md` is the chronological batch and verification log. `docs/PROGRESS.md` remains the phase/task checklist and weighted progress source.
- `source-uploads/` contains archived input snapshots only; the canonical plan and docs are the active source of truth.

**Location rule:** this workspace keeps the supplied pack under `plan/`. Paths such as `docs/PROGRESS.md`, `plan.md`, `schemas/`, and `tools/` in the source pack are relative to `plan/`. Do not extract the pack into the workspace root unless the user explicitly asks.

## 1. Read order (every new session)
1. `plan/AGENTS.md` — this session entry point.
2. `plan/TRACKER.md` and `plan/docs/PROGRESS.md` — current batch history and phase/task status. A task is not authorization to begin app-code work.
3. The docs named for an authorized task in the map below.
4. The `plan/plan.md` sections the task cites.

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

**Precedence:** this `AGENTS.md` entry point governs authorization and working safety. `plan.md` defines product behaviour; `docs/*` define file-level structure and presentation. If product documents disagree, do not guess: record it in `docs/DECISIONS.md`, fix whichever is wrong, and continue only within the authorized scope.

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
12. **Behavior tests only.** Use fake clocks and event sources; do not add tests that read Kotlin source files as text. Logic under test must not call the system wall clock directly.
13. **Track every batch and check.** Mark batches in progress before work; update both trackers after each meaningful change and record each check's exact command and observed result before starting the next check. Preserve failures and blockers.

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
1. Proceed only after explicit user authorization for app-code implementation. Then pick the next unticked task in the current phase of `docs/PROGRESS.md` (lowest ID not blocked).
2. Read its docs. Write tests first where logic is pure (selectors, schedule engine, validators, redactor, policies, state machines).
3. Implement in the modules/files named in `docs/REPO_LAYOUT.md`. If you must deviate, log an ADR.
4. Run supported test-only Gradle or Android test tasks through the existing Replit workflow or scripts, for the production flavor only. Never run APK build tasks on Replit. Use GitHub Actions for APK-build verification only when explicitly requested.
5. As each change or check completes or fails, update `TRACKER.md` with changed files, exact command, result, and any failure or blocker. Then update the task status and evidence in `docs/PROGRESS.md`.
6. Tick a task only when complete and verified. For `[M]`/`[L]` tasks and every gate item fill the `evidence:` line (test name, log/screenshot path, device + API level, and exact command/result as appropriate).
7. From the workspace root, run `(cd plan && python tools/progress.py)` and `(cd plan && python tools/progress.py --check)`; the latter must pass before declaring tracker state valid.
8. Commit small, phase-scoped changes when appropriate. Do not push unless the user explicitly asks.
9. If blocked: mark the task `(BLOCKED: reason)`, add it to the `docs/PROGRESS.md` Blockers section and `TRACKER.md`, and continue only with an authorized independent task.

**A task is done only when:** authorized code is complete, required tests pass, specs/docs are updated, evidence is recorded, `--check` passes, and both trackers match the actual code and results.

## 6. Commands (fill in during P1-01 and keep current)
```
# Run only supported production-flavor test tasks; do not build APKs on Replit.
(cd plan && python tools/progress.py --check)
(cd plan && python tools/progress.py --html docs/progress.html)
```

## 7. When to ask the human
Ask for device facts (Phase 0), physical actions on the phone, decisions that change scope/licence/security, destructive operations, or any unclear implementation authorization. Do not treat a plan task as permission to start app-code work. For ordinary implementation details, use `docs/DECISIONS.md` defaults and record decisions as required.
