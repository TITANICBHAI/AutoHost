# ACTION_HARDENING — make the action layer trustworthy before the agent leans on it (Phase 12)

> **Status: PLAN. Every finding below is a PREDICTION from static reading** (2026-10-29…11-01; Gradle, Android SDK and a phone were not available). Line numbers were read in this tree on 2026-11-01. Nothing here is a confirmed defect until a failing test or an emulator run reproduces it (ADR-089 (3)); each item either gets a fix with before/after evidence or a recorded "does not reproduce".
> Decisions: ADR-089 (action layer), ADR-098 (grants/scopes). Sources adapted from: `docs/REUSE_MATRIX.md` §A (ClosePaw, Apache-2.0). Tasks: P12-01…P12-17, gates G12-a…f (`docs/PROGRESS.md`). Starts after X-09 (Epoch V).

## 1. Why this comes first

Scripts, flows **and** the planned agent all act through `device.ui` (`capabilities/a11y/.../A11yUiProvider.kt`, `Actions.kt`, `UiActions.kt`, `SetTextChain.kt`). A model that reasons about the screen from the *result of its last action* amplifies any lie in that result: a click reported "verified" that did nothing, a text field reported rejected that actually holds the text, a scroll that starts off-screen. AutoHost's *read* side is already hardened (`ReadGate`, `TraversalGuard`, `RootResolver`, `ForegroundResolver`, `PopupGuard`, `NodeRegistry`); the *act* side is thinner. The fix is shared: one hardened foundation, no second action path for the agent (ADR-086 (2)).

## 2. Vocabulary: three levels of success (adopted from ClosePaw's `doc/main/infra/tool/mobile_action.md`, ADR-089)

| Level | Meaning | Who decides | Today |
|---|---|---|---|
| **accepted** | the OS took the action (`performAction` true, gesture dispatched) | the action layer | what `verified` means for clicks (`Actions.kt:19,24`) |
| **effect** | an observable change after the action, confirmed by the host | the action layer (new) | not reported |
| **semantic** | the *goal* happened (`expect`/`verify`) | the caller (`expect=`, flow `assert*`, agent `verify`) | `expect=` exists (`A11yUiProvider.kt:294`) |

Contract change (P12-02, additive, through regeneration): `device.ui` action results gain optional `effect: "changed" | "unchanged" | "unverifiable"` and `warnings: [string]`. Absent = old behaviour. `verified` keeps meaning only "effect confirmed" once P12-04 lands; until then it stays "accepted" and docs say so. When the effect is `unchanged` the layer **reports it with a warning and does not fall through to another channel** (ClosePaw's double-click lesson).

## 3. Findings

| ID | Where (verified 2026-11-01) | Predicted behaviour | Confidence | Failing test first | Fix (source) | Task |
|---|---|---|---|---|---|---|
| **A1** `set_text` read-back can be stale | `Actions.kt:45-53` performs `ACTION_SET_TEXT`, then reads `UiTreeBuilder.safeText(info)` on the **same** `AccessibilityNodeInfo` with no `refresh()` (`refresh()` is called only in `NodeRegistry.resolve`, before the action). `A11yUiProvider.kt:441-487`: `applied` false → `REJECTED` → chain continues to IME commit (`clearFirst = true`, lines 452-453) or clipboard paste (lines 454-457; does **not** clear; skipped for secrets) → final `TEXT_INPUT_UNVERIFIED` / `TEXT_INPUT_REJECTED` (lines 470-483) | a successful set-text can be reported rejected; with paste in the chain the field may end up with the text **twice**; for `secret=true` a false `TEXT_INPUT_REJECTED` | ~0.7 (framework `AccessibilityNodeInfo` is a snapshot; exact OEM behaviour unknown) | emulator: fixture "plain field" surface; `set_text("abc")` must return `verified:true, strategy:"set_text"` and the field must hold exactly `abc`; also a JVM test through a new `NodeHandle` seam that fails unless the order is perform → **refresh** → read | refresh-then-verify; cursor/hint handling; identity guard (RM-A1) | P12-05, P12-11 |
| **A2** `click` "verified" = accepted only; `retry_if_no_change` can double-click | `Actions.kt:19,23-24` (`verified=true` when `performAction` returns true). `SettlePolicy.kt:61-63`: fingerprint = `index:id:text:desc:bounds` — **no** checked/selected/enabled/focused state. `A11yUiProvider.kt:285-293,377-386`: fingerprint taken before, click, then `changed()` re-reads **immediately** (no settle wait); a second click is sent when `retry_if_no_change` and the fingerprint is equal | a toggle (only `checked` changes) or a slow transition looks "unchanged" → second click flips it back or double-submits | ~0.8 | JVM (`FakeReader` + `FakeUiActions`, virtual clock): (a) toggle changes only `checked` → `effect=changed`, no retry; (b) the after-snapshot appears N ms later → the verifier waits for settle before saying `unchanged`; (c) nothing changes → `unchanged` + warning, and **no** channel fall-through | state-aware fingerprint + three-valued `ChangeDetector`; settle then compare (RM-A4, RM-A3) | P12-03, P12-04 |
| **A3** gestures are fire-and-forget | `Actions.kt:81-101`: `dispatchGesture(gesture, null, null)`; fixed 60 ms tap / 600 ms long-press; the boolean only says "dispatched" | a cancelled or ignored gesture is reported as success; timings ignore `ViewConfiguration` | ~0.85 | JVM with a fake dispatcher: callback never fires → typed timeout (virtual clock, 5 s); `dispatched == false` → typed failure; `onCancelled` → failure | awaited injection with timeout, `continuation.isActive` guard, `ViewConfiguration` tap timeout (RM-A2) | P12-06 |
| **A4** scroll starts off-screen on non-default displays; never uses accessibility scroll | `A11yUiProvider.kt:67-68`: constructor defaults `screenWidth = 1080`, `screenHeight = 2400`; `AppGraph.kt:691-707` does **not** pass the real size, so production uses the defaults. `scrollUntil` (lines 521-529) swipes from screen centre with distance 0.45 × height: start y = 1200 + 540 = **1740** on the default. `dump` (lines 259-267) uses the same numbers for the on-screen filter and `normalized` output | on a display whose real height is smaller than the default (the owner's Realme C3 is believed to be 720 × 1600 — **UNVERIFIED**, confirm with `adb shell wm size`) a down-scroll starts below the screen; nested/sub-scrollables are not targeted; `ACTION_SCROLL_FORWARD/BACKWARD` is never tried | ~0.8 for the defaults being used; the device effect is UNVERIFIED | JVM table test of a pure `ScrollPlanner(display, scrollableBounds, direction)` for 720×1600, 1080×2340, 1080×2400 (start/end inside bounds); emulator: nested-scrollable fixture, target found within N swipes | accessibility scroll first, gesture **inside** the scrollable's bounds, real display metrics from the service (`maximumWindowMetrics` on API 31 via `ApiGate`, else `getRealMetrics`) (RM-A1, RM-A7) | P12-08 |
| **A5** coordinate fallback = node-bounds centre | `Actions.kt:29-31` taps `rect.exactCenterX()/Y()` of the node | inside a large clickable container the centre can be a dead zone while the real hotspot is a child | ~0.6 | JVM table tests for a pure `HotspotRefiner` (container → smallest actionable child, ambiguity guard); emulator: large clickable container with an off-centre button | hotspot promotion: smallest actionable container, then nearest actionable child with the ambiguity guard (RM-A3) | P12-09 |
| **A6** result contract drift | `Actions.kt:14` `Route {NODE, PARENT, COORDINATE}`; `A11yUiProvider.kt:299` emits `NODE|PARENT|COORDINATE|NONE`; the frozen spec (`schemas/capability-specs.w1.json:173`), `schemas/errors.json:597-599` (`clickMethods`), the SDK stub/`_capabilities_gen.py:155`, the simulator (`sdk-python/autohost_sim/device.py:1146`) and the flow tests use `NODE_ACTION|ANCESTOR_ACTION|COORDINATE` | any consumer that matches on `method` (flows, scripts, the agent) sees values the spec never promised | ~0.95 (read in five places) | JVM `ProviderConformanceTest` (see §4); first case fails on `method` | map `Route` → spec names at the provider boundary; decide what `NONE` becomes (an error, not a method) | P12-01 |
| **A7** node-ref scoping is inert; act scope not applied to refs | `NodeRegistry.kt:15-17,28-41` supports `put(node, info, scope)` / `resolve(ref, scope)` (stale if another run's ref), but every call in `A11yUiProvider.kt` (lines 150, 196, 363, 512, 600) omits the scope, so `entry.scope != null && scope != null` never fires; one registry is shared by all runs (`AppGraph.kt:689`); `resolveTarget` (lines ~579-590) returns `resolveHandle(ref)` for `ref` / `target.ref` without consulting `actPackages` | run B can act on run A's ref; a run whose observe scope is wider than its act scope can act outside the act scope through a ref (ADR-098 (2)) | ~0.85 | JVM: two runs on one registry — B resolving A's ref → `STALE_NODE`; a ref to a node of package P with `actPackages = {Q}` → `SCOPE_VIOLATION` (`reason: SCOPE_NOT_GRANTED`, as `ScopeInterceptor` uses) | pass the run id into `put/resolve`; check the node's package against `actPackages` at ref resolution | P12-16 |
| **A8** spec scope flags unenforced | `allow_coordinates` / `allow_global` exist in `schemas/capability-specs.w1.json:29-30` (+ its broker copy and `_capabilities_gen.py:335-336`) and in **no** Kotlin file | `tap`, `swipe`, `press(home/recents/notifications)` are reachable with any `device.ui.act` grant | ~0.9 (grep) | JVM: grant without `allow_coordinates` → `tap`/`swipe` → `SCOPE_VIOLATION`; grant without `allow_global` → `press home` refused (`back` stays allowed per AGENT_RUNTIME §6) | enforce in the provider (or a small interceptor) from the grant's scope | P12-16 |
| **A9** `device.app` is not scope-filtered | `AppProvider` (wired at `AppGraph.kt:708`) never reads `ctx.scopePackages`; `ScopeInterceptor` can only check an explicit `package` argument | `device.app.list` returns every installed app and `foreground` names the real foreground package under a restricted `device.app.query` grant (ADR-098 (4)) | ~0.85 | JVM: restricted grant → `list` returns only granted packages; `foreground` outside scope → redacted/denied | filter by granted packages, or keep these methods out of restricted grants | P12-17 |

Also noted (not separate tasks): `click` walks at most 8 ancestors (`Actions.kt:22`) — ClosePaw's guard is node identity (bounds/id/class/label), not a hop count → P12-11; `Gestures` never checks the overlay window (P12-07); `Gestures.tap` has no display-bounds check (P12-06).

## 4. Provider ↔ spec conformance test (P12-01)

**Goal:** a provider can never return a shape the frozen spec does not promise (A6 was found by reading five files; a test finds the next one).
- Input: `schemas/capability-specs.w1.json` (`returns` strings such as `{method:'NODE_ACTION|ANCESTOR_ACTION|COORDINATE', verified:bool}`) — parse them with a small pure parser (`ReturnShape`), supporting `T`, `T?`, `'A|B|C'` enums, `bool/int/string/object/array`.
- Harness: run each provider with its existing fakes (`FakeReader`, `FakeUiActions`, `FakeDevice`; no mocking of our classes) over a table of calls per method; decode the `Envelope` result; assert keys ⊆ spec keys (extra key = failure unless listed in an `additive` allow-list), enum values ∈ the declared set, optional keys absent-or-typed.
- First red case: `device.ui.click` → `method` ∈ spec set (fails today: `NODE`, `PARENT`, `NONE`).
- Scope: `device.ui` first (P12-01); the table is generic so other providers join without new infrastructure (not required in Phase 12).
- Lives in `capabilities/a11y/src/test/...` (provider) with the parser in `core/broker` test fixtures; no new module.

## 5. Fixture-app surfaces (P12-12; `docs/FIXTURE_APP.md`)

| Surface | Proves | Predicts |
|---|---|---|
| Plain text field with a read-back label | `set_text` verified exactly once, no doubled text | A1 |
| Toggle (only `checked` changes) | `effect=changed`, no retry | A2 |
| Button with a 700 ms delayed transition | settle before verdict | A2 |
| Field that accepts `ACTION_SET_TEXT` but renders nothing (NumberPicker-like) | honest `unverifiable`/rejected, not success | A1 |
| Large clickable container with an off-centre child button | hotspot promotion; the app's own counter increments once | A5 |
| Nested scrollable (list inside a scroll view) + a tall single list | scroll inside bounds; accessibility scroll first | A4 |
| Gesture target (long-press counter) with a slow callback | awaited gesture result | A3 |

## 6. Verification procedure

1. **JVM first** (`scripts/test-jvm.sh`): every pure unit (`ChangeDetector`, `ScrollPlanner`, `HotspotRefiner`, `ReturnShape`, identity guard) with the ported ClosePaw tests (RM-G2) plus new red-then-green tests. A finding that cannot be expressed on the JVM waits for the emulator step.
2. **Emulator** (needs X-08): API 30, 31 and 34 images, fixture app, `connectedCoreDebugAndroidTest` (the harness `capabilities/a11y/src/androidTest/.../A11yUiProviderTestHarness.kt` exists). Per finding: run **before** the fix and record the result — reproduces / does not reproduce — then after. Evidence lines carry the image, API level and test name; an emulator run is *integration-tested*, never real-device-verified.
3. **Real device** (G12-d, `(real device)`): the fixture suite on the Realme C3 and the Galaxy M31 (evidence `device: <model>, Android <n> / API <n>`); also `adb shell wm size` to settle the A4 numbers.
4. If a finding does not reproduce: record "does not reproduce on <env>" with the run, keep the test (it still guards), skip the fix.

## 7. Tasks and gates

| Task | Finding | Rows (REUSE_MATRIX) |
|---|---|---|
| P12-01 conformance test | A6 | — |
| P12-02 `effect`/`warnings` in the spec + regeneration | §2 | — |
| P12-03 fingerprint + `ChangeDetector` | A2 | RM-A4 |
| P12-04 click effect verification | A2 | RM-A3, RM-A4 |
| P12-05 `set_text` | A1 | RM-A1 |
| P12-06 awaited gestures | A3 | RM-A2 |
| P12-07 overlay vs injected gestures | — | RM-F4 |
| P12-08 scroll | A4 | RM-A1, RM-A7 |
| P12-09 hotspot promotion | A5 | RM-A3 |
| P12-10 IME-Enter via `ApiGate` | — | RM-A1 |
| P12-11 identity guard | A1, ancestor walk | RM-A1 |
| P12-12 fixture surfaces | §5 | — |
| P12-13 emulator runs | all | RM-A7 |
| P12-14 port upstream tests | — | RM-G2 |
| P12-15 regeneration/docs | §2 | — |
| P12-16 a11y scope enforcement | A7, A8 | — |
| P12-17 `device.app` scope | A9 | — |

Gates: **G12-a** (conformance + `effect` vocabulary: P12-01, 02, 15) · **G12-b** (pure units: P12-03, 09, 11, 14) · **G12-c** (emulator reproduce-or-refute: P12-04…06, 08, 10, 12, 13) · **G12-d** (real device) · **G12-e** (overlay audit + tap-through: P12-07) · **G12-f** (scope closure: P12-16, 17). Procedures: `docs/GATES.md`.

## 8. What does not change

- Selector language, `NodeRegistry` ref format, broker envelope shape (only additive `effect`/`warnings`), the SDK surface (only additive), golden vectors except where `effect` appears.
- No app-specific code; no new action path; `Build.VERSION.SDK_INT` stays inside `ApiGate` and provider `availability()` (IME-Enter API 30 and window metrics API 31 go through `ApiGate`).
- `scroll_until` keeps its name and arguments; only its mechanics change.

## 9. Unknowns

- Whether `AccessibilityNodeInfo.getText()` is really stale after `ACTION_SET_TEXT` without `refresh()` on the owner's OEM builds (A1) — the emulator step decides.
- The real display sizes of both phones (A4) — `adb shell wm size`.
- Whether the effect detector needs a screenshot fallback for empty trees (ClosePaw uses an 8×8 perceptual hash); v1 returns `unverifiable` instead (RM-A4).
- Whether OEM overlays swallow injected taps (P12-07/S7).
