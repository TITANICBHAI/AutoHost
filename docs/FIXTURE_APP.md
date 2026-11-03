# FIXTURE_APP — contract for `testing/fixture-app`
A tiny app (`app.autohost.fixture`, Compose or Views) that gives every test a **known, controllable** target in a **different package** from AutoHost (so scoping is exercised). Keep it dependency-light. Each screen has stable `resource-id`s (prefix `fx_`), visible labels, and writes its state to a **state readout** (below) so tests can assert effects without reading the UI.

## State readout
- `ContentProvider` `content://app.autohost.fixture.state/state` → JSON `{screen, counters:{…}, last_event, text_fields:{…}, clicks:{…}}`; reset via `…/reset`.
- Also mirrored in a visible `TextView` `fx_state` (small) on each screen for screenshot/OCR tests.
- Launch behaviours are controlled by **intent extras** (`delay_ms`, `locale`, `variant`, `fail_after`), so a script can request a specific scenario.

## Screens
| Screen (Activity) | Contents / ids | Used for |
|---|---|---|
| `HomeActivity` | buttons to each screen (`fx_open_<name>`) | navigation |
| `ButtonScreen` | `fx_btn_continue` "Continue", counter `fx_clicks`; variant extra changes label ("Continuar") and id | click, verify, multi-label |
| `DelayedButtonScreen` | `fx_btn_late` appears after `delay_ms` | wait_for, settle |
| `ListScreen` | 200 rows "Item 0…199", target "Item 150", `fx_list` | scroll_until, long trees |
| `UnboundedRangeScreen` | endless lazy list | scroll limits |
| `TextFieldScreen` | `fx_text`, `fx_password`, `fx_multiline`, Unicode echo `fx_echo` | set_text, IME, secrets masking |
| `DialogScreen` | AlertDialog (OK/Cancel), PopupWindow, bottom sheet | popup guard, RootResolver multi-window |
| `PermissionDialogScreen` | triggers a runtime-permission request (system dialog) | popup guard / system-dialog detection |
| `KeyboardScreen` | focusable fields that raise/hide the IME | keyboard state |
| `DynamicScreen` | content changes every `delay_ms` | settle, window-updating |
| `SecureScreen` | `FLAG_SECURE` | screenshot typed failure |
| `CanvasScreen` | custom-drawn buttons, **no semantic nodes** | `EMPTY_TREE`, coordinates, vision |
| `WebViewScreen` | local HTML page with form (assets) | web content nodes, browser tests |
| `ToastScreen` | button shows a Toast | toast events |
| `DeepLinkActivity` | `fixture://screen/<name>?…` | launch with deep link |
| `ClipboardScreen` | copy/paste fields | clipboard via IME |
| `NotificationScreen` | posts a notification on demand | notification triggers |
| `CrashScreen` | throws on demand | app crash handling, evidence |
| `LocaleScreen` | labels in `en`/`es` per extra | multi-label selectors |

## P3 additions (all written, awaiting device verification)
- Every screen carries the `fx_state` mirror; `Screens.bind()` sets `FixtureState.screen` and refreshes it.
- `ButtonScreen` accepts `variant=es` (label "Continuar", id `fx_btn_continue_es`) and every Home route passes
  its incoming intent extras through, so `fixture://screen/<name>?variant=…` drives both at once.
- `DeepLinkActivity` routes `fixture://screen/<name>` to all 17 screens (extras pass through).
- `CanvasScreen` and `DynamicScreen` build their views programmatically by nature (custom drawing / a
  re-inflating ticker); everything else uses XML layouts with `@+id/fx_*` ids as before.

## v4 additions (PLANNED — none exist yet; added by P12-12 and Phase 13, documented here in the same commit)
Purpose: deterministic targets for the predicted action-layer defects (`docs/ACTION_HARDENING.md` §3, §5) and for the agent's safety tests (`docs/AGENT_RUNTIME.md` §14a).

| Screen (planned) | Contents / ids | Used for |
|---|---|---|
| `ToggleScreen` | a switch/checkbox whose state flags (checked, selected) are mirrored into `fx_state`; a click counter | A2: one click flips once, never twice; state-aware fingerprint |
| `TransitionScreen` | a button that navigates after `delay_ms` | A2: change detection after a delayed transition |
| `SilentFieldScreen` | a field whose `ACTION_SET_TEXT` is accepted (`true`) but whose visible text does not change | A1: read-back verification and typed failure |
| `ClickContainerScreen` | a large clickable container with a small child button off-centre | A5: coordinate fallback and hotspot promotion |
| `NestedScrollScreen` | a vertical list inside another scrollable (horizontal pager) with a target at the end | A4: scroll inside bounds, correct container |
| `GestureTargetScreen` | records tap / long-press / swipe with timestamps and an optional `delay_ms` before reacting | A3: awaited gestures |
| `InjectionScreen` (Phase 13) | on-screen text that tries to widen grants or call tools outside the catalog | G13-b: untrusted-content handling |
| `ApprovalScreen` (Phase 13) | buttons labelled with risky words that only increment `fx_state` counters | approval flow, label-word hints (not a security control) |
| BLOCKED-package fixture (Phase 13) | a screen the run must never see — mechanism **UNDECIDED** (a second fixture package, or the fixture added to the user-extendable BLOCKED list; bundled entries cannot be overridden) — decided in P13-17 | G13-b: observation floor |

Existing screens reused as they are: `TextFieldScreen` (plain field), `DelayedButtonScreen`, `ListScreen`, `UnboundedRangeScreen`. Every new screen carries the `fx_state` mirror and a `fixture://screen/<name>` route like the others.

## Test conventions
- Instrumented tests install the fixture next to AutoHost, run **real Python/flow tasks** from `testing/fixtures/tasks/*` through the real service stack, then assert via the state readout.
- Fixture builds are **debug-only**, never shipped; its package is the canonical `${fixturePackage}` in test tasks.
- Add a screen when a new behaviour needs a deterministic target; document it here in the same commit.
