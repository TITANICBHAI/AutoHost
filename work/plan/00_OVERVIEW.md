# AutoHost — overview of the plan (read this first, then only the Sxx file STATUS.md names)

## What AutoHost is
An Android app where small automations (Python scripts, recorded flows, JS, an optional AI agent) act on the phone through a
permission broker: read the screen and tap in apps you choose, open apps, call websites you list, notify, run on schedules —
with a visible Stop, run history and crash recovery. *A new automation costs a script, not a new app.*

## Verdict (after reading the code, the specs and running the Python side)
- **Good:** the architecture and safety design are real (one broker, scoped grants, fail-closed errors, honest availability,
  generated SDK/docs, a candid SECURITY.md). Python SDK + simulator pass 436 tests; root tooling tests pass.
- **Not proven:** the Android app has **never been launched** on any device or emulator (D-24 / D-25). A launch crash was
  "fixed" by text edits and never run. 10 of 32 Kotlin modules have no passing test run; two are in no CI workflow.
- **Scope is far ahead of proof:** ~53K lines of Kotlin, 16 phases, a 932-point tracker that disagrees with CI, six
  contradictory "start here" files. CI takes ~100 minutes, runs the wrong Gradle tasks and hides failures.
- **The UI drifted from your own `docs/UI_SPEC.md`:** the task list hides every task not pushed with adb, schedules need
  JSON, Back leaves the app, there is no way to pick your own script or folder.
- **Strategy: make one thing real.** Honest fast CI → prove it launches → fix the usable shell → one golden path on a phone
  → release 0.1 alpha. Everything else is frozen ("Labs") until then.

## What is in this pack, and how much of it is verified
| Piece | Where | Verified how |
|---|---|---|
| Reset of process docs, tools, workflows | `ready/tools/m0_reset.py` | run on a clean copy of your repo; all Python suites + checks pass afterwards |
| Python runner: plain scripts work, `print()` → run log | `patches/P06` | applied to a clean copy; **8 new tests**; SDK suite 436 pass; worker copy synced |
| Test/manifest fixes C-02 … C-05, Gradle timeout | `patches/P01–P05` | `git apply --check` on a clean copy. **Kotlin not compiled** (no Android toolchain) — CI is the judge |
| CI workflow + digest tool + task list | `ready/.github/…`, `ready/tools/ci_digest.py`, `ready/scripts/…` | actionlint clean; digest tool has 7 tests; task list guarded by a test against `settings.gradle.kts` |
| Schedule-form spec (form → rule) | `ready/testing/golden/schedule_form_vectors.json` | produced by your Python reference scheduler; 5 tests |
| Manifest-factory spec (choices → task.json) | `ready/testing/golden/script_manifest_vectors.json` | validated against `schemas/task.schema.json`; 7 tests |
| AGENTS.md (10 rules), STATUS.md, README.md | `ready/` | read against the repo |
| Everything in `plan/Sxx_*.md` about Kotlin/UI | `plan/` | **reasoned from code, not compiled or seen on a screen** |

## Sessions (one per Claude.ai chat; each ends with a pull request and a rewritten STATUS.md)
| # | Goal | Gate | Needs the owner? |
|---|---|---|---|
| S01 | Apply the starter pack: reset docs, new CI, test fixes, Python runner fixes | Python suites green locally; branch pushed; first CI digest exists | add the workflow file in the GitHub web UI if the connector can't push it |
| S02 | Fix what the first digest lists (one module per push) | digest shows zero failing tests or each has a reason in STATUS | no |
| S03 | Prove the app starts (Robolectric launch smoke on the JVM) | smoke test green | no |
| S04 | One honest task list, Back stack, Setup scroll, StrictMode | tests green | no |
| S05 | Schedule without JSON (form + vectors) + Schedule tab + Developer switch | vectors test green; no JSON on normal screens | no |
| S06 | Stable signing key → updates install in place | two CI APKs install over each other | **yes** (10 min, once) |
| S07 | First run + "restricted settings" guide + plain failure card | tests green; phone checklist printed | no |
| — | **PHONE SESSION** (15–30 min) | screenshots per step | **yes** |
| S08 | Fix what the phone showed (max 3 rounds) | evidence recorded in STATUS | no |
| S09 | Use my own script / folder / pasted code | tests + phone check | phone check |
| S10 | Share, all examples (+ app picker), "when something happens", diagnostics | tests green | no |
| S11 | Home screen + 4-tab bar, polish, release 0.1 alpha | "ready to use" checklist with evidence | phone check |
`LATER.md`: Record→Save→Schedule, online examples gallery on your site, recipes, AI presets/BYOK, connect-my-computer, vision matcher fix.

## Decisions for the owner (answer once; the agent follows them)
1. **Phone (D-25):** allow short install-and-screenshot sessions (≤ 30 min) when the agent asks for evidence? `docs/DEVICES.md`
   lists two of your phones. *If no:* the launch smoke test + Firebase Test Lab are the only runtime proof and the release says "unverified on device".
2. **Labs:** hide the advanced features behind a switch and ship only `corePy` for 0.1? (recommended: yes)
3. **FileProvider (D-23):** remove the whole-`files/` entry (patch P04)? (recommended: yes)
4. **Signing key (S06):** create it and add four GitHub secrets? Without it every CI APK means "uninstall first and lose your data".
5. **Name for things:** keep `Tasks` (UI_SPEC) or rename to `Automations`? Also confirm the GitHub `owner/repo` (the prompt assumes `TITANICBHAI/AutoHost`).

## What will never be perfect (say it in the README)
Android can block the Accessibility switch for non-store installs until "Allow restricted settings", and Advanced
Protection mode can block it outright; phone makers' battery managers can still kill background work; UI automation depends
on other apps' screens and breaks when they change; the screen must be on and unlocked; sideload only; CAPTCHA/stealth/
credential harvesting are refused by design.

## Not verified (read this)
No Kotlin was compiled and no screen was seen. **From memory, re-check before relying:** Android's exact "Allow restricted
settings" menu path; that a fresh GitHub runner makes a new debug key each run; GitHub's rule that pushing workflow files
needs the `workflow` token scope; Chaquopy sending `print()` to logcat; AI provider base URLs; Firebase Test Lab's free quota.
