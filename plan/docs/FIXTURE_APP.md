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

## Test conventions
- Instrumented tests install the fixture next to AutoHost, run **real Python/flow tasks** from `testing/fixtures/tasks/*` through the real service stack, then assert via the state readout.
- Fixture builds are **debug-only**, never shipped; its package is the canonical `${fixturePackage}` in test tasks.
- Add a screen when a new behaviour needs a deterministic target; document it here in the same commit.
