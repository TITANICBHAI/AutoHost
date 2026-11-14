# UI_V2 — the interface rebuilt around what the app can do (owner request 2026-11-12; revised as UI v3 on 2026-11-14)

Replaces the navigation and screen structure of `UI_SPEC.md` §3–4 (the design tokens, status chips and copy rules there still hold).
Why: the old UI grew screen by screen and exposes a fraction of the 47 capabilities / 157 methods. Here the screens are derived
from the feature list (`app/.../ui2/FeatureCatalog.kt`, tested against `schemas/capability-specs.w1.json`), so a new capability cannot ship without a place in the UI.
How macros, Python scripts and agents are meant to work together (and the Agent chat tab) is in `INTEGRATION_V3.md`; this file is the screen structure.

## 1. Rules
1. **Nothing in the normal flow needs a computer.** Advanced access (shell tier) is optional and only unlocks the 8 "advanced" capabilities. Routes are shown computer-free first (`AccessPlan`). A route that does not work yet (in-app Wireless debugging is a draft) is shown as "not available yet", never as steps.
2. **Every feature says what it needs, in one of six ways** (`Need`): nothing · Android asks once · one system switch · pick a folder · a Settings switch · optional advanced access. The same word and the same button everywhere.
3. **Status first, one primary action per screen.** Stop is always reachable while a run is active.
4. **Small phones first.** Design target 320 dp wide, 568 dp tall, font scale 1.3. Rows are at most two lines; chips wrap; no horizontal scrolling; sheets instead of new screens for short tasks; touch targets ≥ 48 dp; a long list is lazy. (Design target only: not yet checked on a phone or in a 320 dp test.)
5. **No ids on normal screens.** Capability ids, selectors and JSON appear only under "Details" / Developer.

## 2. Five tabs and a separate onboarding (UI v3, revised 2026-11-14)
History: the first draft had Today / Tasks / When / Phone and assumed the phone was already set up; the second folded those into three tabs (Tasks / Setup / Settings); the owner then said three tabs were too few and the onboarding was mixed into the app screens. Now:

**First launch shows a full-screen onboarding, not a tab.** Welcome → one page per setup step (`GetStarted`: accessibility is the required one; background running, notification access, keyboard, AI model and a first task are recommended or optional), each with one big action button and "Skip for now" → "You are ready" or "Almost there". "Skip all" is on every page except the last, and Back steps to the previous page. It is shown once and can be replayed from Settings → "Replay the welcome steps". Code: `OnboardingPlan` (pure, tested), `OnboardingFlow`; the saved flag is read before anything is drawn so a returning person never sees the welcome flash by.

The bottom bar has five icons (`MAIN_TABS` in `AppNavHost.kt`):

| Tab | Job | Contents |
|---|---|---|
| **Tasks** | do things | header with the logo, Search, **+ Add** (*Add a task*: template · quick macro · import a .zip task file) and a ⋮ menu · above the list `TasksAttention`: unfinished setup first, then runs that need you or are running (Stop), then the way into Schedules and triggers · task cards with a status chip, the last result, and a "Needs: …" line with a **Set up** button when a required ability is not ready |
| **Agent** | tell the agent what you need | today the Ask form in plain-language mode (what you want, which apps, Run); with no AI model it says "Add an AI model first" and opens AI settings. It becomes a real chat in `INTEGRATION_V3.md` step F |
| **Build** | make something to run | one list of ways: quick macro · template · task file (.zip) · Python (info card) · Studio (Labs only). `BuildModel` |
| **Activity** | what happened and what is waiting | *Needs you / Running / Finished* groups (`TodayModel`) and buttons to Schedules and triggers and to the full history |
| **Settings** | everything else, by what you want to do | **What this phone allows** (the old Setup screen: every ability by group with its status and fix, Advanced access, keep-running tips; a standalone screen with a Back button) · AI and agent (BYOK model, Ask, runs) · Tasks, scripts and folders (add a task, quick macro, Python status, task folder) · Automatic (schedules, tiles/shortcuts/bubble) · Safety and system (health, security/backup/developer) · Replay the welcome steps · Labs switch |

The old developer-style settings page stays reachable as "Security, backup and developer".
**Adding tasks needs no computer**: the dev folder is hidden from file managers on newer Android, so *Import a task file (.zip)* (system file picker → import review → install) is the route for Python, JavaScript and flow tasks. Not built yet: picking a whole folder; writing a Python script in the app.
Identity: the logo is a phone with a circuit trace ending in an amber action dot (`BrandMark`, launcher and notification icons); palette is indigo with an amber accent (`core/designsystem` Theme).

## 3. What each feature asks (the `Need` table)
| Need | Shown as | The button does |
|---|---|---|
| READY | "Works now" | nothing |
| ASK_ON_USE | "Android asks the first time" | **Allow now** runs the permission dialog ahead of the run |
| TOGGLE | "Needs one switch" + which (Accessibility · Notification access · AutoHost keyboard) | opens that system screen; the row turns green when the switch is seen on |
| PICK | "You choose the folder" | opens the folder picker |
| SWITCH | "Off by default (security)" | opens Settings → Security at that switch, with the risk sentence |
| ADVANCED | "Optional advanced access" | opens the Advanced access sheet (routes, computer-free first) |

Task cards use the same table through `TaskReadiness` (the "Needs: …" line). Planned, not built: a task detail **Can do** tab listing only the capabilities that task declares, each with its verdict, and **Allow all that Android asks** in one tap.

## 4. Advanced access sheet
Order: Root → Shizuku → in-app Wireless debugging → ADB from a computer. Each row: name, one line, a chip "No computer" / "Needs a computer", and a state (working · not set up · not available yet). After any route works once, a **Grant myself permanent permissions** action (one-time `WRITE_SECURE_SETTINGS`) lets accessibility self-heal after a reboot without the route being on any more (planned; not built yet).

## 5. Build order (each step compiles and is unit-tested on CI before the next)
1. `FeatureCatalog`, `AccessPlan`, `Readiness` (done, UI2-1).
2. The ability list and Advanced access on the live state (done; now the screen "What this phone allows").
3. Tab shell (done: five tabs, separate onboarding, Settings hub, Add a task, .zip import; UI v3, CI run 32).
4. A test that lays out the real screens (done: `UiJourneyTest`, after a phone crash that the old smoke test could not see).
5. Quick macros (done, OpenTasker-inspired, see the reference comparison).
6. Next, in the order of `INTEGRATION_V3.md` §9: macro ↔ Python joins, the real Agent chat tab, screenshots, the macro node editor, floating-ball actions.
7. Folder import, an in-app Python script editor, the task-detail Can do tab with Allow-all.
8. Small-screen checks: Robolectric at 320×568 and fontScale 1.3 for every tab (JVM only; not a phone). Not done.

## 6. Not decided / owner
- Tab names are Tasks / Agent / Build / Activity / Settings by default; the owner has not objected.
- Whether Labs (Record, Studio, Ask) stay behind a switch once Build exposes them.
- Nothing in this file has been seen on a phone yet.
