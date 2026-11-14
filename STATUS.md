# STATUS — rewrite this file at the end of every session (do not append). Keep it under 150 lines.

Updated: 2026-11-14 · by: Session 2 (UI v3) · branch: work/s02-green · last pushed code commit: 15337a9 (CI run 32: GREEN, 1493 JVM tests, 0 failed, 0 compile errors; all five workflows completed success, APK builds)

## Now
UI v3 is in (`docs/UI_V2.md` §2, `docs/INTEGRATION_V3.md` §7): a new logo and indigo + amber colours, a five-icon bottom bar **Tasks · Agent · Build · Activity · Settings**, a separate full-screen first-run onboarding (skippable, replayable from Settings), a standalone "What this phone allows" screen, and the fix for the phone crash `Key "NOTIFICATIONS" was already used` (a step and a feature group shared a LazyColumn key).
The plan for macros, Python scripts and agents working as one is `docs/INTEGRATION_V3.md`. Only the identity, bar and onboarding part is built; the joins are not.
Nobody has seen any of it on a phone. Owner: please open a pull request `work/s02-green` → main (the token here cannot), install the debug APK from the `Android debug APK` run for 15337a9 (https://github.com/TITANICBHAI/AutoHost/actions/runs/43608698402) and tell me what the first launch looks like.

## Works (CI evidence only; JVM, not a phone)
- Run 32: 1493 tests, 0 failed, 0 compile errors; Android debug APK, `ci`, `jvm-core`, `jvm-tests`, `jvm-capabilities` all completed success. The digest still lists four modules with no test results (`runtime/api`, `runtime/python`, `plugin-sdk`, `testing/fixture-app`); that was already so before this session.
- `UiJourneyTest` (Robolectric + Compose test rule) composes the real activity: first launch walks onboarding and all five tabs; a returning person sees no welcome; the access screen survives 12 swipe-ups (the crash above); the welcome can be replayed from Settings. This is the first test that lays out real screens; the old launch smoke test never did.
- Pure, tested UI logic in `app/.../ui2/`: `FeatureCatalog` (47 capabilities, test fails if the contract and the catalog differ), `AccessPlan`, `Readiness`, `PhonePlan`, `GetStarted`, `TaskReadiness` (card says "Needs: …" + Set up), `TodayModel`, `SettingsHubModel`, `OnboardingPlan`, `BuildModel`, `ListKeys`, `macro/MacroCompiler` (compiled flow accepted by the real `FlowParser`).
- Earlier real bugs fixed (LLM gateway retry, pairing HKDF, tool catalog null, completion fail-closed, etc.) — see git log `S02-…`.

## Broken or unverified
- **Never run on a device.** Compose layout, small-screen fit (320 dp, font 1.3), the new logo and colours, onboarding, the five-tab bar, back handling, permissions: all unverified.
- **Agent tab is not a chat yet.** It is the Ask form in plain-language mode. Not built, only designed (`INTEGRATION_V3.md` §5): real chat tab, screenshots to the model, agent `run_task`, `ai.llm` + Python `llm.chat`, Python `agent.run`, flow `runTask` command, flow variables from `args`, declared macro inputs, Python `macros` module, macro node editor with a live element picker, configurable floating-ball actions, workflow builder.
- Whether a flow can read the `args` a script passes is **not established** (flow variables resolve env → config → profile).
- All new UI text is hardcoded Kotlin, not `strings.xml` (against `docs/CONVENTIONS.md`).
- Advanced access without a computer: only **Shizuku (Android 11+)** and **root** are wired. In-app Wireless-debugging pairing is a DRAFT (`exec` throws UNSUPPORTED). Plain ADB needs a computer. The UI says so.
- Not built: pick a whole folder as a task; write a Python script in the app; "Grant myself permanent permissions"; Needs-setup filter chip; per-task "Can do" tab with Allow-all; Studio list reads the dev folder only; Recorder saves `flow.json` without `task.json`, so recorded flows never reach the Library; `AndroidHealthEnv` does not fill `imeSelected`/`shellTier`.
- Triggers are still created per task (task detail); the Schedules screen lists schedules only.
- Style baseline: ~19k ktlint/detekt findings (report only). Robolectric licence row is "as far as known — confirm".

## Needs you
1. Pull request `work/s02-green` → main. 2. A phone session with the debug APK (logo, colours, onboarding, bar, Settings, Add a task, quick macro).
3. Three answers from `INTEGRATION_V3.md` §10: keep a no-Python `lite` build? Should AutoHost's model write the video titles, or Gemini and AutoHost copies them? May the app use official APIs (video, upload) next to screen driving?
4. Already answered 2026-11-14: yes to the agent sending screenshots to the model provider and yes to scripts calling the model. Both stay opt-in per provider and off by default.
5. Older: Labs default, FileProvider removal, signing key/secrets.

## Next (ranked; each step ends CI-green and pushed)
1. Macro ↔ Python joins (`INTEGRATION_V3.md` A–D): confirm how flows see `args`, then `runTask` flow command, args/JSON variables, declared macro inputs, Python `macros` module. Tests against the real flow parser and the Python sim.
2. Real Agent chat tab, then agent `run_task` with an allowlist, then `ai.llm` + Python `llm.chat` (new ADR).
3. Screenshots: image messages, per-provider opt-in, sensitive-app block, secure-screen refusal, per-call approval. Tests first.
4. Macro node editor in slices: installed-app picker → action picker (OpenTasker, MIT: file header + `THIRD_PARTY_NOTICES.md` row) → live screen-element picker.
5. Floating-ball actions; workflow builder; per-task "Can do" tab; folder import and script editor; move UI text to `strings.xml`.

## Lessons
- Read `details.md` on the `ci-results` branch before touching code; fetch with `git fetch -q origin ci-results:refs/remotes/origin/ci-results`. It carries no stack traces, so a test timeout is a hypothesis until it passes.
- Lazy-list keys must be unique within one list: namespace them by kind (`ListKeys`). An activity-only smoke test never lays out screens; compose the real screen in a test.
- After adding a variant to an enum or sealed type, grep every `when` over it (CI run 30).
- `performClick` taps a node's centre: scroll the node fully into view first (`performScrollToNode`), not just until it is first visible.
- Most "failures" after compile errors were wrong tests, but four were real product bugs: decide by reading the code.
- A completeness test against the contract caught a missing capability (`device.intent`): generate UI from data, test the join.
- Missing imports (`dp`, `rememberSaveable`) are the usual first CI failure on Compose files: check imports when writing without a compiler.
- A negative test that greps copy ("no computer") must forbid asking, not mentioning.
- Keep LF/CRLF as found; use exact find/replace and re-read.
