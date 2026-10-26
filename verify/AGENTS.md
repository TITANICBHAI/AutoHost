# AGENTS.md — AutoHost (agent brief, refreshed for the verification epoch)

You are the implementing **and verifying** agent for **AutoHost**: a Kotlin Android app (`minSdk 29` → latest) that hosts user-written automations — Python scripts, declarative flows, or JavaScript — and gives them real device capabilities (UI control of other apps, app launch/stop, notifications, network, files, events, optional shell-level power), with per-task settings, profiles, UIs, schedules, run tracking and recovery, plus a Dev API/MCP server so coding agents can drive the phone while authoring.

The docs decide *what* to build and what "done" means. You decide *how*, and you prove it with evidence — documented immediately, pass or fail.

## 0. Current epoch — know this before anything

- **All eleven phases are WRITTEN** (sessions 1–19, see `worklog.md`; delivered through `autohost-phase11-part2.zip`). Almost nothing is verified: **1/213 tasks ticked, Verified 0%** in `docs/PROGRESS.md`.
- What remains, in order (`HANDOFF.md` header + §4, §5):
  1. **The Gradle first build** — every Kotlin/JVM test was written in a sandbox with no Android SDK and has **never been executed**. `HANDOFF.md` §5 is the exact build/verification order; §6 is the map of likely first-build failures.
  2. **Every sandbox-runnable verification** — JVM tests, Python suites, generated-artifact drift checks, licence check, MCP evals. Run them locally; you have the tooling.
  3. **The device gates** (Gates 1–11 hardware evidence in `docs/GATES.md`) — need the owner's phone. Keep them unticked with `PENDING-DEVICE:` notes until hardware exists.
  4. **Release** — `docs/RELEASE.md`.
- There are **no further code phases in `plan.md`** — do not invent scope (`§3` rule 11). If verification uncovers a code gap, fixing it is in scope; new features are not.

## 1. Read this first — every session, in order. Do not skip. Do not work from memory.

1. **`HANDOFF.md`** — current status table, decisions already taken (do not reopen), §5 verification order, §6 first-build failure map, §7 working rules.
2. **`docs/PROGRESS.md`** — the dashboard, then the `(WRITTEN)` / `(FAILING)` / `(BLOCKED)` markers. Unticked tasks are your worklist; their `evidence:` lines tell you what was already built and what must run.
3. **`worklog.md`** (repo root) — read the last two session entries; append yours when the session ends.
4. **`docs/GATES.md`** — what evidence each gate demands before it may be ticked.
5. **`docs/CONVENTIONS.md`** + **`docs/REPO_LAYOUT.md`** — before touching any code.
6. The **`plan.md`** sections a task cites, and **`docs/DECISIONS.md`** before changing any behavior.

**Rule:** never assert project state from memory, from a chat summary, or from this file — the tracker files *are* the state, and they may be ahead of anything you were told. If a doc and reality disagree, fix the doc in the same commit and note it in `DECISIONS.md`.

### Doc map (where to look)

| Need | Open |
|---|---|
| What/why, architecture, capability catalog, phases | `plan.md` |
| Build/verification order, first-build failure map, working rules | `HANDOFF.md` §5–§7 |
| Status, worklist, evidence lines | `docs/PROGRESS.md` |
| Session-by-session history | `worklog.md` |
| Which modules/packages/files to create, where | `docs/REPO_LAYOUT.md` |
| How to *prove* each gate | `docs/GATES.md` |
| Coding standards, naming, testing, git | `docs/CONVENTIONS.md` |
| Android manifest, permissions per flavor, services, XML configs | `docs/ANDROID_MANIFEST.md` |
| Screens, navigation, design tokens, UI state, copy | `docs/UI_SPEC.md` |
| Dev API (JSON-RPC), reverse transport, MCP tools | `docs/DEVAPI.md` |
| Errors, manifests, flows, triggers, broker envelopes | `schemas/*.json`, `core/broker/src/main/aidl/**` |
| Python SDK contract / JS SDK contract | `sdk-python/autohost/**/*.pyi`, `docs/SDK_JS.md` |
| Decisions and open questions | `docs/DECISIONS.md` |
| Devices, per-path verification, emulator rules | `docs/DEVICES.md` |
| Spike procedures and verdicts | `docs/SPIKES.md` |
| Release steps, signing, licence CI | `docs/RELEASE.md` |
| End-user guide / vendor battery matrix / port exercise | `docs/USER_GUIDE.md`, `docs/OEM_MATRIX.md`, `docs/PORT_EXERCISE.md` |
| Lessons from other projects + **licence rules** | `docs/REFERENCES.md` |

**Precedence:** `plan.md` defines behaviour; `docs/*` define file-level structure and presentation. If two documents disagree, do not guess: record it in `docs/DECISIONS.md`, fix whichever is wrong, and continue.

## 2. Your machine — verify locally first, GitHub Actions is a mirror

You have **Gradle, the Android SDK, JDK, Kotlin, Python** (and `node`, which the JS sim tests use). That is strictly more than the sandbox the code was written in. Use it:

- **Run every verification locally.** `.github/workflows/ci.yml` lists the exact steps (python-suites + jvm-tests jobs) — those same commands are yours to run. Do not push to GitHub to find out whether something passes; push when you already know it does.
- **GitHub Actions is used only where the plan/owner assigns it**: X-01 (CI green on every commit) and the Gate 11-e CI run URL. Everything else: local.
- Known CI nit: the `branches:` filter at the top of `ci.yml` is malformed (`ain]` — should be `[main]`). Fix it the first time you touch CI.
- Emulator work you can run yourself (instrumented tests, API-30 a11y screenshot, G3-h) counts as executed — label it per `docs/DEVICES.md` (`UNVERIFIED-on-hardware` where only a real phone proves it).
- Device-gated items (real-phone spikes, vendor battery matrix, hardware gates) stay `PENDING-DEVICE` with the exact steps written out, so the owner can execute them when the phone is available.

## 3. The tick rule — how every verification ends (owner directive)

The moment any verification finishes — **pass or fail**:

1. **Document it NOW** on the task's/gate's line in `docs/PROGRESS.md`: what ran, the command, exit status, key numbers/log lines, artifact paths, and environment (JDK, API level, device vs emulator). Failures are documented exactly like passes — plus the hypothesis and the next action.
2. **If it PASSED: tick the box** (`- [ ]` → `- [x]`). **If it FAILED: keep the box unticked**, tag it `(FAILING: short reason)`, and then immediately either fix-and-rerun or switch to the next independent verification (loop back to the failure afterwards). A failure is an event to record, never something to hide or defer.
3. After any tick batch: `python tools/progress.py` (regenerates the dashboard) then `python tools/progress.py --check` (must pass).
4. **Never batch documentation at the end of a session; never end a session with an undocumented run.**

**Known tracker conflict, resolve once, then follow it:** `tools/progress.py --check` currently errors `GATE_SKIPPED` if you tick P1+ tasks while earlier phases' device gates are open — and those gates cannot close without the phone. The owner's directive is tick-immediately, so: tick + evidence anyway, then record **ADR-084** in `docs/DECISIONS.md` ("verification-epoch ticking: device-gate order no longer blocks task ticks; device gates stay unticked until hardware") and adjust `tools/progress.py` to match (tiny change, add a test, keep `--check` enforcing evidence-on-tick). Until that lands, a verified-but-untickable task carries its evidence line plus a `(VERIFIED-JVM)` / `(VERIFIED-PY)` tag so the state is never lost.

## 4. Working loop

1. Take the next item from `HANDOFF.md` §5's order (first build → per-module suites → assemblies), then the lowest-ID unticked, unblocked task in `docs/PROGRESS.md`.
2. Read its docs. Write tests first where logic is pure; fakes over mocks (`testing/fakes`).
3. Implement in the files named in `docs/REPO_LAYOUT.md`. Deviating? Log an ADR.
4. Run the verification (§2, §8). **Apply the tick rule (§3) to the result.**
5. `python tools/progress.py --check` must pass. Commit: `P9-05: <summary>` (task id first, small commits).
6. Blocked? Keep the box unticked, write `(BLOCKED: reason)`, add it to PROGRESS "Blockers", continue with the next independent item.

**A task is done only when:** code merged, tests pass, specs/docs updated, evidence recorded, `--check` green.

## 5. Git & delivery

- You hold a **PAT for the owner's repo**: commit small and per-task, **push after every verified milestone**. The owner pushes on their own too — never force-push, never rewrite shared history.
- Zip delivery was the old channel (the writing sandbox had no network). **Git push replaces it** unless the owner explicitly asks for a zip.
- Never commit keystores, `local.properties`, real credentials, or personal screenshots.

## 6. Non-negotiables

1. **Fail closed.** Unknown state, missing capability, ungranted permission, stale node, malformed input → typed error with an actionable message. Never guess, never continue silently.
2. **No app-specific or service-specific code** outside `samples/` and `testing/`.
3. **`Build.VERSION.SDK_INT` is read only in `ApiGate` and provider `availability()`.** Version-dependent behaviour is an ordered provider chain (plan §3.5); API-specific calls live in tiny `*ApiNN` objects.
4. **All device/service access goes through the broker interceptor chain.** Providers register a `CapabilitySpec`; SDK stubs/docs are generated from specs — never hand-edit generated files.
5. **Secrets never leave `SecretStore`** except per call to the broker for a declared field. Never log them; never put them in checkpoints, KV, evidence text or exports. The canary-secret test must stay green.
6. **Visible and stoppable.** Any run that acts on the UI shows the ongoing notification with Stop; the global kill switch always works.
7. **Boundaries:** no CAPTCHA solving/bypass, no stealth or identity spoofing, no rate-limit circumvention, no credential harvesting. Challenges → `WAITING_USER`. (plan §0.4)
8. **Clean-room for AGPL/GPL references.** Mobilerun Portal (AGPL) and OpenAutoJs (GPL + NC) are read for ideas only. Apache/MIT sources may be adapted with notices in `THIRD_PARTY_NOTICES.md`.
9. **Do not invent scope.** If it isn't in a task, a doc, or a decision, ask or log it in `DECISIONS.md` — don't build it.
10. **Don't mock our own classes** — use the fakes. Don't call `System.currentTimeMillis()`/`Random` directly (`Clock`/`Ids`). No DI framework. No `GlobalScope`; classify errors into `AhResult`, never throw across the broker.
11. **Golden vectors are frozen** — regenerate only with a recorded decision (ADR-039/051). Python changes: rerun both suites + `mcp_eval.py`; after editing `sdk-python/autohost` run `tools/sync_sdk.py`.

## 7. What NOT to do

- Don't skip the §1 reading order, and don't trust any summary (including this file) over the tracker files.
- Don't tick a box without evidence, and don't hide a failure — §3 exists for this.
- Don't push to GitHub Actions to "see if it works" — run locally first (§2).
- Don't change a wire contract (`schemas/*`, AIDL, `.pyi`) casually — they are final; changes need an ADR and regeneration, never hand edits.
- Don't add debug endpoints to release builds; don't set `isAccessibilityTool`; don't split generated files from their generator.

## 8. Commands (current counts at session 19 — re-verify, don't trust)

```
# Python (no Android tooling needed) — expect ~415 + ~41 tests green:
cd sdk-python && python3 -m unittest discover -s tests -t tests -p "test_*.py"; cd ..
python3 -m unittest discover -s tests -p "test_*.py"
python3 tools/progress.py [--check] [--html docs/progress.html]
python3 tools/licence_check.py && python3 tools/mcp_eval.py
python3 tools/gen_artifacts.py --check && python3 tools/sync_sdk.py --check
python3 tools/gen_flow_vectors.py && git diff --exit-code testing/golden/flow_vectors.json
python3 tools/gen_schedule_vectors.py && git diff --exit-code testing/golden/schedule_vectors.json

# JVM (first time ever — HANDOFF §5 has the full per-module order and §6 the failure map):
gradle test                                        # all JVM unit tests
gradle :app:assembleCoreDebug :testing:fixture-app:assembleDebug
gradle connectedCoreDebugAndroidTest               # instrumented (device/emulator attached)

# CI mirror (only this goes to GitHub Actions): .github/workflows/ci.yml
```

## 9. When to ask the human

Only for: anything requiring a physical action on the phone (enable a service, tap a prompt, plug in USB), device facts, and decisions that change scope, licence or security posture. Everything else: the defaults in `docs/DECISIONS.md` stand — record new decisions there and move on.

## 10. Resume protocol (context loss or a new session)

Read §1's order top to bottom, then: continue at the first item of `HANDOFF.md` §5 that lacks evidence, or the first `(FAILING)`/`(BLOCKED)` you can unblock. The docs are the memory — never ask the owner to re-explain what a doc already says.
