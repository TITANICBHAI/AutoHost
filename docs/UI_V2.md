# UI_V2 — the interface rebuilt around what the app can do (owner request 2026-11-12)

Replaces the navigation and screen structure of `UI_SPEC.md` §3–4 (the design tokens, status chips and copy rules there still hold).
Why: the old UI grew screen by screen and exposes a fraction of the 47 capabilities / 157 methods. Here the screens are derived
from the feature list (`app/.../ui2/FeatureCatalog.kt`, tested against `schemas/capability-specs.w1.json`), so a new capability cannot ship without a place in the UI.

## 1. Rules
1. **Nothing in the normal flow needs a computer.** Advanced access (shell tier) is optional and only unlocks the 8 "advanced" capabilities. Routes are shown computer-free first (`AccessPlan`). A route that does not work yet (in-app Wireless debugging is a draft) is shown as "not available yet", never as steps.
2. **Every feature says what it needs, in one of six ways** (`Need`): nothing · Android asks once · one system switch · pick a folder · a Settings switch · optional advanced access. The same word and the same button everywhere.
3. **Status first, one primary action per screen.** Stop is always reachable while a run is active.
4. **Small phones first.** Design target 320 dp wide, 568 dp tall, font scale 1.3. Rows are at most two lines; chips wrap; no horizontal scrolling; sheets instead of new screens for short tasks; touch targets ≥ 48 dp; a long list is lazy.
5. **No ids on normal screens.** Capability ids, selectors and JSON appear only under "Details" / Developer.

## 2. Four tabs
| Tab | Job | Contents |
|---|---|---|
| **Today** | what needs me now | needs-you prompts (top) · active run with Stop · "Finish setup" card (max 3 steps) · up next · recent runs · Ask bar (when an AI model is set) |
| **Tasks** | what can this phone do for me | list with search and chips (All · Ready · Needs setup · Scheduled) · **+ New** sheet: Import file · From repo · Record · Quick macro · Ask AI to make one |
| **When** | run things automatically | Schedules and Triggers (notification, app opened, charging, webhook, …) in one list; a Quick macro is one "When → Do" row |
| **Phone** | what works here and what it needs | Set-up steps · every feature by group with its status · Advanced access (optional) · Always-on tips |

Top-bar gear on every tab → **Settings** (AI model, security switches, backup and packs, developer, about). Settings is no longer where features hide.

## 3. What each feature asks (the `Need` table)
| Need | Shown as | The button does |
|---|---|---|
| READY | "Works now" | nothing |
| ASK_ON_USE | "Android asks the first time" | **Allow now** runs the permission dialog ahead of the run |
| TOGGLE | "Needs one switch" + which (Accessibility · Notification access · AutoHost keyboard) | opens that system screen; the row turns green when the switch is seen on |
| PICK | "You choose the folder" | opens the folder picker |
| SWITCH | "Off by default (security)" | opens Settings → Security at that switch, with the risk sentence |
| ADVANCED | "Optional advanced access" | opens the Advanced access sheet (routes, computer-free first) |

Task detail uses the same table: its **Can do** tab lists only the capabilities this task declares, each with its verdict, and **Allow all that Android asks** in one tap.

## 4. Advanced access sheet
Order: Root → Shizuku → in-app Wireless debugging → ADB from a computer. Each row: name, one line, a chip "No computer" / "Needs a computer", and a state (working · not set up · not available yet). After any route works once, a **Grant myself permanent permissions** action (one-time `WRITE_SECURE_SETTINGS`) lets accessibility self-heal after a reboot without the route being on any more (planned; not built yet).

## 5. Build order (each step compiles and is unit-tested on CI before the next)
1. `FeatureCatalog`, `AccessPlan`, `Readiness` (done, UI2-1).
2. Phone tab (set-up steps, feature list, advanced sheet) on the live state.
3. Tab shell (Today / Tasks / When / Phone) replacing `MainTabs`; old Setup/Surfaces reachable only from Settings until retired.
4. Today (needs-you, active, finish setup, recent), Tasks + New sheet, When.
5. Task detail: Can do tab, Allow-all.
6. Quick macros (OpenTasker-inspired, see the reference comparison).
7. Small-screen checks: Robolectric at 320×568 and fontScale 1.3 for every tab (JVM only; not a phone).

## 6. Not decided / owner
- Tab names ("Tasks" vs "Automations").
- Whether Labs (Record, Studio, Ask) stay behind a switch once the New sheet exposes them.
