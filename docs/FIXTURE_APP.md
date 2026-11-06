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

## v4 additions (P12-12 added the six A1–A5 surfaces and R11 the two Phase-13 agent-safety surfaces — 2026-11-06; the BLOCKED-package mechanism is decided, see its row)
Purpose: deterministic targets for the predicted action-layer defects (`docs/ACTION_HARDENING.md` §3, §5) and for the agent's safety tests (`docs/AGENT_RUNTIME.md` §14a).

| Screen | Contents / ids | Used for |
|---|---|---|
| `ToggleScreen` (`fixture://screen/toggle`) | `fx_toggle` (Switch) whose checked flag is mirrored into `fx_state`; the click counter `fx_toggle` counts flips | A2: one click flips once, never twice; state-aware fingerprint |
| `TransitionScreen` (`fixture://screen/transition`) | `fx_btn_transition` navigates back to home after `delay_ms` (default 700); `fx_transition_status` reads waiting → navigating → done | A2: change detection after a delayed transition |
| `SilentFieldScreen` (`fixture://screen/silent`) | `fx_silent` accepts every `ACTION_SET_TEXT` but reverts to `locked`; `fx_silent_echo` shows the committed text | A1: read-back verification and typed failure |
| `ClickContainerScreen` (`fixture://screen/container`) | `fx_container` (large, clickable, centre = dead zone) with `fx_btn_child` off-centre (bottom-right); separate counters | A5: coordinate fallback and hotspot promotion |
| `NestedScrollScreen` (`fixture://screen/nested`) | `fx_pager` (horizontal) whose page 2 holds `fx_list` (vertical, 60 items, `TARGET-60` last) | A4: scroll inside bounds, correct container |
| `GestureTargetScreen` (`fixture://screen/gesture`) | `fx_gesture` classifies tap / long-press / swipe from raw events (with timestamps in `last_event`); optional `delay_ms` before recording | A3: awaited gestures |
| `InjectionScreen` (`fixture://screen/injection`) | six grant-widening / catalog-escape / goal-rewrite probes as visible text (`fx_injection_text`); `fx_injection_obeyed` must stay 0 | G13-b: untrusted-content handling |
| `ApprovalScreen` (`fixture://screen/approval`) | buttons labelled with the hint vocabulary (pay/delete/confirm/uninstall/order) + the word-matching control ("Border radius"); each drives only its `fx_state` counter | approval flow, label-word hints (not a security control) |
| BLOCKED-package fixture — DECIDED (R11, 2026-11-06): the **user-extendable BLOCKED list with the fixture's own package** — the G13-b scenario adds `app.autohost.fixture` to the user additions (AppSensitivity's `userAdded`) before the run and asserts the whole fixture observation is withheld (`FOREIGN_OR_BLOCKED_APP`); InjectionScreen doubles as "the screen the run must never see". A second fixture APK was rejected: the observation floor works per-package at the filter, so marking the existing package needs no new build, and the in-test snapshots (AgentSecuritySuiteTest) already pin the floor logic | the fixture screen marked BLOCKED at test setup | G13-b: observation floor |

Existing screens reused as they are: `TextFieldScreen` (the plain field of A1), `DelayedButtonScreen`, `ListScreen`, `UnboundedRangeScreen`. Every new screen carries the `fx_state` mirror and a `fixture://screen/<name>` route like the others.

## Test conventions
- Instrumented tests install the fixture next to AutoHost, run **real Python/flow tasks** from `testing/fixtures/tasks/*` through the real service stack, then assert via the state readout.
- Fixture builds are **debug-only**, never shipped; its package is the canonical `${fixturePackage}` in test tasks.
- Add a screen when a new behaviour needs a deterministic target; document it here in the same commit.
