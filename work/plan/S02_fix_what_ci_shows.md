# S02 — Fix what the first CI digest shows (milestone M1b). Branch `work/s02-green`.
Read `origin/ci-results:latest.md`; its first line must name your branch and commit. Fix **in the order the digest lists**, one module per push.

## Likely contents (verify, do not assume)
- **Compile errors** (`## Kotlin compile errors`): fix first; each is a real source problem. Search all call sites before changing a signature.
- **Hung test** (`TIMED OUT` + last STARTED lines): the per-task timeout (8 min) stopped it. Typical causes in this repo: a test clock that never advances
  while the code loops on it with a real `delay` (C-02 pattern → give the clock a step, or inject a `pause` function); a coroutine on a virtual-time
  `TestDispatcher` waking a blocked thread (C-03 pattern → use a real thread or `runTest`). `RunControls.sleep` also uses `delay`: a flow test that
  drives it on a TestDispatcher hangs the same way.
- `:app` tests (7 classes were named in the old notes: AgentRunDetailStateTest, AgentSectionViewerStateTest, AiSettingsStateTest, AskSheetStateTest,
  OverlayWindowFlagsTest, ReleaseHardeningTest, DevTaskStarterTest). C-04 fixes ReleaseHardeningTest. If a test fails with *"Method … not mocked"*,
  add `testOptions.unitTests.isReturnDefaultValues = true` to `app/build.gradle.kts`. Fix the *test* only if it contradicts documented behaviour; otherwise fix the code.
- **Modules with no results:** compile error, timeout or a module that really has no tests (`:runtime:python`, `:runtime:api`).
- `:runtime:agent` and `:core:llm` run for the first time here; expect surprises — treat them like any other module.

## Also
1. Un-`@Ignore` `FlowInterpreterGoldenTest` then `FlowInterpreterTest` (one per push) once `:runtime:flow` is stable; they are the flow engine's main tests.
2. After the first digest exists on your branch: delete `.github/workflows/jvm-core.yml` and `jvm-capabilities.yml`.
3. Copy the style-baseline counts (ktlint/detekt findings) into STATUS "Broken". Fix none of them now.
4. Update STATUS: which modules are green, what remains with a one-line reason.

## Gate
`latest.md` shows RED→GREEN or every remaining failure has a one-line reason in STATUS. No `continue-on-error` on test steps. At most 3 CI cycles per problem.
