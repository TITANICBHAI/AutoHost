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
