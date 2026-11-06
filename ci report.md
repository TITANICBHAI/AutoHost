# CI REPORT — the hand-back fix cycle (commit 3132707+)

Concise log of every CI failure and its fix. **Read this first when the same problem surfaces
again** — recurring lessons live in the RECURRING section at the bottom.

---

## Run 1 — commit 3132707 (the W1–W6.4 hand-back, 154 files)

### ci.yml — Python suites: 23 ERRORs in test_schemas.ManifestRejects
- **Cause:** the W6.3 schema split was half-applied — the `$ref`s pointed at
  `#/$defs/configObjectSchema` / `profileObjectSchema` but the `$defs` still carried only
  `objectSchema` (the W6.3 heredoc updated the refs but the def rename silently failed — the
  known heredoc pitfall). `jsonschema_mini._resolve` → `KeyError: 'configObjectSchema'`.
- **Fix:** renamed the def in the source schema (Edit tool, not a heredoc): `objectSchema` →
  `configObjectSchema` (the `x-secret` property REMOVED from it) + added `profileObjectSchema`
  (with `x-secret`) beside it. `gen_artifacts.py` re-run.

### jvm-core — core:data compile: SecretStoreTest.FakeDao
- **Cause:** P14-06 added `SecretBlobDao.delete(profileId, key)` to the interface; the test's
  `FakeDao` didn't implement it.
- **Fix:** implemented `delete` in `FakeDao` (rows.remove by the composite id).

### jvm-capabilities + android-debug-apk — capabilities/a11y compile (5 errors)
- **Cause 1:** `ScrollPlanner.kt` had no `import app.autohost.core.model.Bounds` (unresolved
  `Bounds`, `l`, `t`, `r`, `b`).
- **Fix:** added the import.
- **Cause 2:** `ScrollPlanner` — `val distance = h * fraction` produced a **Double**
  (fraction: Double) fed into Float `Stroke` params.
- **Fix:** `* fraction.toFloat()`.
- **Cause 3:** `A11yUiProvider` — two `ctx.runId` uses inside functions that DON'T have `ctx`:
  `dismissKnownDialog` (the throwaway put→resolve there was scoped with `ctx.runId`) and
  `setText` (whose signature lacked ctx).
- **Fix:** `dismissKnownDialog` scopes the throwaway re-mint to `null` honestly (a dialog probe,
  not a persisted run ref); `setText` gained the `ctx` parameter (routed from `invoke`).

---

## Run 2 — commit cca4462 (ci GREEN; jvm-core + jvm-capabilities compile-red)

### jvm-core — core:data compile: SecretStoreTest.FakeDao
- **Cause:** the W1 FakeDao `delete` insertion landed INSIDE `deleteForProfile`'s body (the
  regex matched the lambda's `}` as the method's close). "Modifier 'override' is not applicable
  to 'local function'" + the missing-abstract-member error.
- **Fix:** restructured — deleteForProfile closes, then `delete` as a sibling.

### jvm-capabilities — capabilities/a11y TEST compile: GestureInjectorTest
- **Cause:** the W2.4 test used `async`/`withTimeoutOrNull`/`launch` with only
  `kotlinx.coroutines.test.runTest` imported (run 1 never reached test compilation — the main
  sources failed first, so run 2 surfaced the next layer).
- **Fix:** added `kotlinx.coroutines.{async, launch, withTimeoutOrNull}` imports.
- **Note:** pre-checked all other new test files for the same class of missing coroutine
  imports — one false positive (AgentSecuritySuiteTest's `awaitCancellation` is a private
  top-level wrapper, no import needed), zero real gaps.

---

## Run 3 — commit b0486b8 (ci GREEN, jvm-core GREEN; app + jvm-capabilities compile-red)

### android-debug-apk — app compile: Unresolved `AgentRuntimeProvider` / `agent`
- **Cause:** `app/build.gradle.kts` never got the `:runtime:agent` dependency (and `:core:llm`
  for the factory's `LlmGateway`) — AgentLoopFactory/AgentRuntimeProvider/AgentRunStarter were
  unwritable until the dep existed.
- **Fix:** added `implementation(project(":runtime:agent"))` + `implementation(project(":core:llm"))`.

### jvm-capabilities — a11y TEST compile (4 errors)
- **Cause 1:** GestureInjectorTest has TWO `noTapFloor` declarations (line 17 val + line 102 get())
  — a W2.4-era duplicate, never compiled until now. Fix: removed the bottom one.
- **Cause 2:** GestureInjectorTest's FQNH calls (`kotlinx.coroutines.async { }`) conflict with my
  added imports — un-qualified the calls to `async { }` / `yield()` and added
  `import kotlinx.coroutines.yield`.
- **Cause 3:** ScopeClosureTest's backtick test name contained `;` — ILLEGAL in backtick names on
  the JVM. Renamed.
- **Cause 4:** ScrollPlannerTest's `s.x1 in 0f..w` with w:Int — Int/Float mismatch. Fixed with
  `w.toFloat()`.

---

## Run 4 — commit 2f3225c (ci GREEN, jvm-core GREEN; app compile-red on runtime/agent + a11y 2 test failures)

### android-debug-apk — runtime/agent main compile (4 errors, the module's FIRST compile)
- **Cause 1:** `AgentRuntimeProvider` — `kotlinx.coroutines.coroutineContext.ensureActive()`:
  `coroutineContext` is `kotlin.coroutines`' suspend property, NOT kotlinx'.
- **Fix:** `currentCoroutineContext().ensureActive()` + the import.
- **Cause 2:** `AgentPreflight` imported `app.autohost.core.common.AhError` — AhError lives in
  `app.autohost.core.model`.
- **Fix:** the import moved.
- **Cause 3:** `AgentLoop` — the finish `when` used `FinishVerifier.Confirmed/...` but the
  sealed cases live on `FinishResult`.
- **Fix:** the three references renamed.
- **Cause 4:** `AgentLoop` — `tools = catalog.wireSpecs()` returns JsonArray but ChatRequest
  wants `List<ToolSpec>`.
- **Fix:** `ToolCatalog.specs()` (typed) added; the loop uses it.

### jvm-capabilities — a11y test failures (2 REAL assertion failures, the first actual test runs!)
- **HotspotRefiner**: the tests contradicted each other (T1/T2 written from "smallest
  fully-contained child wins"; T5/T7 from "the tap point filters") — never compiled, never
  reconciled. **Fix (the impl + one test):** the tap-point filter applies ONLY when explicitly
  given (the A5 dead-zone story: no tap → the smallest fully-contained child); full containment
  replaced the broken "STRICTLY inside" disjunct; T5 repurposed to the explicit-miss case.
- The impl change matches the fixture story (the container centre is the dead zone; the child
  off-centre is promoted without a tap).

---

## RECURRING — read before every fix cycle

1. **Heredoc-written file edits can silently half-persist.** The W6.3 schema split was done via
   a `python - <<'EOF'` heredoc: the `$ref` string-replace landed, the def-rename json.dump did
   NOT (or the two writes raced on Windows). RULE: file rewrites go through the Edit/Write tools;
   heredocs only for generated/checked output, and ALWAYS verify the file content immediately
   after (grep the exact new marker).
2. **New DAO interface methods break test fakes.** Adding a method to `SecretBlobDao` (or any
   interface) requires updating every test fake implementing it. Check `grep -rn ": SecretBlobDao"`
   across src/test before pushing.
3. **New function params must be threaded to EVERY call site** — the `ctx.runId` scope-closure
   edits touched 5 call sites but missed `setText`'s signature and `dismissKnownDialog`'s body.
   After a signature change: `grep -n "<fn>("` and walk every hit.
4. **Pure-math Float/Double**: `Int * Double` is Double; Kotlin won't coerce into Float params.
   The ScrollPlanner `fraction` needed `.toFloat()` at the use site.
5. **Missing imports in new files** (`Bounds` in ScrollPlanner): brace-balance checkers don't
   catch unresolved references — only the compiler does. First-run failures are EXPECTED for
   hand-written imports; budget a fix cycle.
