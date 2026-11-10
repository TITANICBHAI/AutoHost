# NEED TO — the remaining work (blocked on the CI logs)

The owner will share the CI run logs. Everything writable is done. This file lists exactly
what to do when the logs arrive.

## 1. The `:app` module's runtime test failures (the biggest blocker)
- The app module **compiles clean** but some of the ~52 tests FAIL at runtime.
- The CI annotations only show compile errors — the test assertion failures are in the
  Gradle test report (`app/build/reports/tests/testDebugUnitTest/index.html`).
- **What to do:** read the test report, identify the failing tests, fix the test logic or
  the main code, push.
- The test files: `AgentRunDetailStateTest` (10), `AgentSectionViewerStateTest` (6),
  `AiSettingsStateTest` (11), `AskSheetStateTest` (10), `OverlayWindowFlagsTest` (6),
  `ReleaseHardeningTest` (5), `DevTaskStarterTest` (4) — the newest tests from W1–W6.4.
- The compile errors are already fixed (batches 33–34).

## 2. The `:capabilities:data` module hang
- The tests HANG in the JVM environment (a 15-minute timeout fires).
- Likely a `runBlocking` or `withTimeout` that waits forever on the JVM.
- **What to do:** read the Gradle test report for `:capabilities:data`, identify the hanging
  test, fix it or @Ignore it with the reason.
- The test file: `DataClusterTest.kt` (the data policy providers, no Room — the hang is
  likely in a `runBlocking` that awaits something that never resolves).

## 3. The socket modules (notifications, net, rawbridge, shell)
- These run AFTER `:app` and `:capabilities:data` in the jvm-capabilities workflow.
- They have NEVER been tested. They may compile clean or have issues.
- **What to do:** once `:app` and `:capabilities:data` pass (or are skipped), the workflow
  will run these modules — read the results and fix.

## 4. The vision NCC math bugs (@Ignore'd)
- `TemplateMatcherTest` — the NCC score of an exact match is 0.9985 not 1.0, and the
  brightness-shifted match lands at position 52 not 77.
- **What to do:** debug the `nccAt`/`normalized` math in `TemplateMatcher.kt` step by step
  with actual runs. The tests are @Ignore'd with the reason.
- The other vision tests (the gradient, the size refusal, grayscale, the missing-engines)
  pass or are @Ignore'd for the bitmap reason.

## 5. X-12's Room/storage red tests (@Ignore'd)
- `X12BoundaryTest` — 2 @Ignore'd tests for the Room persistence boundary (a registered
  canary never reaches the Room run row; the persisted RunSpec profile snapshot never
  carries x-secret plaintext).
- **What to do:** un-ignore them when the Android build environment is available (they
  need Room which can't run on the plain JVM).

## 6. The socket modules' first runs
- `:capabilities:notifications`, `:capabilities:net`, `:capabilities:rawbridge`,
  `:capabilities:shell` — these run after `:app` in the workflow.
- **What to do:** read the test results and fix any compile or assertion failures.

## 7. The data module's 15-minute timeout
- The `continue-on-error: true` on the data step means the workflow continues past it.
- **What to do:** fix the hang or leave the timeout in place (the data tests need
  debugging with runs).

---

## The order of operations when the logs arrive
1. Fix the `:app` module's runtime test failures (the biggest blocker — 15 modules wait behind it)
2. Fix or @Ignore the `:capabilities:data` hang
3. The socket modules will run automatically once 1+2 are done
4. The vision NCC and the X-12 Room tests are lower priority (@Ignore'd, not blocking)
