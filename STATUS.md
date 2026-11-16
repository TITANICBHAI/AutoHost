# STATUS — rewrite this file at the end of every session (do not append). Keep it under 150 lines.

Updated: 2026-11-16 · by: Session 2 (UI v3 + macro/script joins) · branch: work/s02-green · last commit with CI evidence: f6516ba (CI run 37: GREEN, 1548 JVM tests, 0 failed, 0 compile errors; all five workflows completed success, the APK builds).

## Now
UI v3 is in (`docs/UI_V2.md` §2, `docs/INTEGRATION_V3.md` §7): a new logo and indigo + amber colours, a five-icon bottom bar **Tasks · Agent · Build · Activity · Settings**, a separate full-screen first-run onboarding (skippable, replayable from Settings), a standalone "What this phone allows" screen, and the fix for the phone crash `Key "NOTIFICATIONS" was already used` (a step and a feature group shared a LazyColumn key).
The plan for macros, Python scripts and agents working as one is `docs/INTEGRATION_V3.md` (decision: ADR-102). Built and CI-green: the identity, bar and onboarding; a flow reads the inputs a script passes (run 34); `tasks.run` hands back the child's `{code, data}` and Python `autohost.macros.run(id, inputs)` (run 35); the flow step `runTask`, a macro starting another task (run 36); and the quick-macro editor now takes **values it can be given** and offers **Type text, Scroll, Press key, Run another task, Finish with a result** (run 37). None of the joins has run on a phone.
Nobody has seen any of it on a phone. Owner: please open a pull request `work/s02-green` → main (the token here cannot), install the debug APK from the `Android debug APK` run for f6516ba (https://github.com/TITANICBHAI/AutoHost/actions/runs/43856265759) and tell me what the first launch looks like.

## Works (CI evidence only; JVM, not a phone)
- Run 37: 1548 tests, 0 failed, 0 compile errors; Android debug APK, `ci`, `jvm-core`, `jvm-tests`, `jvm-capabilities` all completed success. The digest still lists four modules with no test results (`runtime/api`, `runtime/python`, `plugin-sdk`, `testing/fixture-app`); that was already so before this session.
- Run 37 added 14 `MacroCompilerTest` tests (the `app` module went 332 → 360, so it runs each test twice): every new step is parsed by the real `FlowParser`, the manifest is decoded by the real `TaskManifest`, `{name}` and `{answer.data.x}` expand only for names the macro knows, bad names/duplicates/reserved names/unknown names are refused in plain words, a screen step asks for its own app only, `tasks.run` is scoped to exactly the named tasks, and the run and idle limits grow by the waits.
- Run 36: 1520 tests green. `runTask {task, args?, into?}` exists in all five places the flow format lives (Kotlin parser and executor, the Python reference `flows.py`, `schemas/flow.schema.json`, golden vectors 44 → 49, both sides' tests): 18 Python tests, 10 provider tests running a real flow against a fake `tasks.run`, 2 parser tests.
- Run 35: `tasks.run` returns the child's `{code, data}` as result text (before this it was always empty: a real bug); Python `autohost.macros` (12 tests). Run 34: a flow reads only the inputs its own `env` declares (8 tests + 4 provider tests); before this a flow could not read the trigger payload at all.
- `UiJourneyTest` (Robolectric + Compose test rule) composes the real activity: first launch walks onboarding and all five tabs; a returning person sees no welcome; the access screen survives 12 swipe-ups (the crash above); the welcome can be replayed from Settings.
- Pure, tested UI logic in `app/.../ui2/`: `FeatureCatalog` (47 capabilities, test fails if the contract and the catalog differ), `AccessPlan`, `Readiness`, `PhonePlan`, `GetStarted`, `TaskReadiness`, `TodayModel`, `SettingsHubModel`, `OnboardingPlan`, `BuildModel`, `ListKeys`, `macro/MacroCompiler`.
- Earlier real bugs fixed (LLM gateway retry, pairing HKDF, tool catalog null, completion fail-closed, etc.) — see git log `S02-…`.

## Broken or unverified
- **Never run on a device.** Compose layout, small-screen fit (320 dp, font 1.3), the new logo and colours, onboarding, the five-tab bar, back handling, permissions, the macro editor's new fields: all unverified. Whether Gemini's and YouTube's screens expose enough accessibility nodes for such macros is **unverified** (`INTEGRATION_V3.md` §3). Whether *Type text* with a field label finds a real app's field is unverified (a blank label uses the focused editable field).
- **The quick-macro editor still asks for package names and task ids** (a typed "App package" and "Task to run"): against the rule that ordinary users never see them. The app picker and task picker are the next piece (`INTEGRATION_V3.md` §5 C2). It also cannot edit a macro after saving (ids carry a time suffix) and has no *Copy text* step.
- **Agent tab is not a chat yet.** It is the Ask form in plain-language mode. Not built, only designed (`INTEGRATION_V3.md` §5): real chat tab, screenshots to the model, agent `run_task`, `ai.llm` + Python `llm.chat`, Python `agent.run`, macro node editor with a live element picker, configurable floating-ball actions, workflow builder.
- The Python `macros` module has `run` but no `list`/`read`.
- Spec drift found, not fixed: `schemas/capability-specs.w1.json` lists `tasks.run.run` params without `timeout_ms` (the Python SDK and `runTask` send it) and its `returns` omits `await:"timeout"`. The Python reference `_check_env_map` rejects a boolean env value although its message says booleans are allowed and Kotlin accepts them; no vector covers it.
- All new UI text is hardcoded Kotlin, not `strings.xml` (against `docs/CONVENTIONS.md`).
- Advanced access without a computer: only **Shizuku (Android 11+)** and **root** are wired. In-app Wireless-debugging pairing is a DRAFT (`exec` throws UNSUPPORTED). Plain ADB needs a computer. The UI says so.
- Not built: pick a whole folder as a task; write a Python script in the app; "Grant myself permanent permissions"; Needs-setup filter chip; per-task "Can do" tab with Allow-all; Studio list reads the dev folder only; Recorder saves `flow.json` without `task.json`, so recorded flows never reach the Library; `AndroidHealthEnv` does not fill `imeSelected`/`shellTier`.
- Triggers are still created per task (task detail); the Schedules screen lists schedules only.
- Style baseline: ~19k ktlint/detekt findings (report only). Robolectric licence row is "as far as known — confirm".

## Needs you
1. Pull request `work/s02-green` → main. 2. A phone session with the debug APK (logo, colours, onboarding, bar, Settings, Add a task, quick macro with a value and a *Run another task* step).
3. Three answers from `INTEGRATION_V3.md` §10: keep a no-Python `lite` build? Should AutoHost's model write the video titles, or Gemini and AutoHost copies them? May the app use official APIs (video, upload) next to screen driving?
4. Already answered 2026-11-14: yes to the agent sending screenshots to the model provider and yes to scripts calling the model. Both stay opt-in per provider and off by default.
5. Older: Labs default, FileProvider removal, signing key/secrets.

## Next (ranked; each step ends CI-green and pushed)
1. Quick-macro editor, second slice (`INTEGRATION_V3.md` §5 C2): installed-app picker and task picker (so nobody types a package name or id), then *Copy text* into a value, a "Use from a script" snippet, editing a saved macro.
2. Real Agent chat tab, then agent `run_task` with an allowlist, then `ai.llm` + Python `llm.chat` (new ADR).
3. Screenshots: image messages, per-provider opt-in, sensitive-app block, secure-screen refusal, per-call approval. Tests first.
4. Macro node editor in slices: installed-app picker → action picker (OpenTasker, MIT: file header + `THIRD_PARTY_NOTICES.md` row) → live screen-element picker.
5. Floating-ball actions; workflow builder; per-task "Can do" tab; folder import and script editor; move UI text to `strings.xml`.

## Lessons
- Read `details.md` on the `ci-results` branch before touching code; fetch with `git fetch -q origin ci-results:refs/remotes/origin/ci-results`. It carries no stack traces, so a test timeout is a hypothesis until it passes.
- Lazy-list keys must be unique within one list: namespace them by kind (`ListKeys`). An activity-only smoke test never lays out screens; compose the real screen in a test.
- After adding a variant to an enum or sealed type, grep every `when` over it (CI run 30). Run 37 passed first time because every `when (step)` was updated in the same change.
- `performClick` taps a node's centre: scroll the node fully into view first (`performScrollToNode`), not just until it is first visible.
- Most "failures" after compile errors were wrong tests, but four were real product bugs: decide by reading the code.
- A completeness test against the contract caught a missing capability (`device.intent`): generate UI from data, test the join.
- Missing imports (`dp`, `rememberSaveable`) are the usual first CI failure on Compose files: check imports when writing without a compiler.
- A negative test that greps copy ("no computer") must forbid asking, not mentioning.
- The flow format lives in five places (parser, executor, Python reference, schema, vectors): change them in one commit and regenerate the vectors.
- Keep LF/CRLF as found; use exact find/replace and re-read.
