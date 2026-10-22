# UI_SPEC — screens, navigation, tokens, state
Compose + Material 3, single activity. Calm utility tool: status first, dense but legible, honest about what's unavailable. Wireframes are ~36 columns; they define **content and hierarchy**, not pixels.

## 1. Principles
1. **Status first.** The first thing on any screen is *what is happening / what is wrong*.
2. **Honest states.** Never show a disabled control without the reason and a **Fix** action (`schemas/errors.json`).
3. **Visible and stoppable.** An active run is always one tap from **Stop**; the Home screen and the bottom bar show it.
4. **Destructive actions are two-step** (confirm with consequences stated).
5. **Secrets masked** everywhere; reveal only on explicit tap, never in screenshots of logs.
6. **Works on small phones/low RAM:** lazy lists, log paging, no heavy animation, 48 dp targets, large-font safe.
7. **API-aware:** items that don't apply to this Android version are hidden or explained (plan §3.5).

## 2. Design tokens (`:core:designsystem`)
Material 3 with a fixed semantic palette (dynamic colour may theme surfaces on API 31+, **status colours never change**).
| Token | Light | Dark | Use |
|---|---|---|---|
| `primary` | `#0E7C86` | `#4FD1D9` | actions, selected |
| `onPrimary` | `#FFFFFF` | `#00363A` | |
| `surface` / `surfaceVariant` | `#FAFCFC` / `#E3EBEC` | `#101414` / `#2B3335` | cards |
| `outline` | `#6F7B7D` | `#8A9698` | borders |
| `status.success` | `#2E7D32` | `#81C784` | SUCCEEDED, AVAILABLE |
| `status.running` | `#1565C0` | `#64B5F6` | RUNNING, STARTING |
| `status.waiting` | `#6A1B9A` | `#CE93D8` | WAITING_USER (most prominent) |
| `status.warning` | `#B26A00` | `#FFB74D` | PAUSED, NEEDS_SETUP, degraded |
| `status.error` | `#C62828` | `#EF9A9A` | FAILED, DENIED |
| `status.interrupted` | `#E65100` | `#FFAB91` | INTERRUPTED |
| `status.neutral` | `#5F6B6D` | `#B0BABC` | QUEUED, CANCELLED, SKIPPED, UNSUPPORTED |
Typography: M3 scale; `labelSmall` for chips, **monospace** (`FontFamily.Monospace`) for logs, selectors and the compact UI dump. Spacing 4 dp grid (8/12/16/24). Shape: 12 dp cards, 20 dp chips. Icons: Material Symbols. Motion: only a pulsing dot for RUNNING and a progress bar; honour reduce-motion.
**Status chips:** `QUEUED` neutral · `STARTING` running (hollow) · `RUNNING` running (pulsing dot) · `PAUSED` warning · `WAITING_USER` waiting (filled, with an action button) · `SUCCEEDED` success · `FAILED` error · `CANCELLED` neutral · `INTERRUPTED` interrupted · `SKIPPED` neutral outline. **Availability chips:** `AVAILABLE` success · `NEEDS_SETUP` warning + **Fix** · `UNSUPPORTED` neutral + reason · `DENIED` error outline · `DISABLED_BY_SWITCH` warning + **Open switch** · `UNVERIFIED` outline "unverified on hardware".

## 3. Navigation
Bottom bar (4): **Home · Tasks · Schedules · Settings**. Runs/History reachable from Home ("Recent runs → See all") and Task detail → History. Setup & Health from the Home banner and Settings. An **Active-runs bar** sits above the bottom bar whenever a run is active (task · profile · stage · **Stop**).
```
Routes: home · tasks(library) · task/{id}[tab] · task/{id}/profile/{pid} · task/{id}/review(permissions) · import · import/report/{id}
        run/{id} · runs(history) · schedules · schedule/{id}/edit · triggers · trigger/{id}/edit
        health · health/shell(pairing guide) · settings · settings/developer · settings/security · settings/devapi
        catalog(capabilities) · recorder/{taskId} · inspector
Deep links: autohost://task/{id}  autohost://run/{id}  autohost://health
```

## 4. Screens
### S01 Home
```
┌ AutoHost                    ⚙   ┐
│ ⚠ Accessibility is off  [Fix]   │  HealthBanner (only when a required check fails)
│ ACTIVE NOW                      │
│ ┌─────────────────────────────┐ │
│ │ ● Recurring Press · Profile A│ │  RunCard: stage, elapsed, progress
│ │ stage: wait_for_button  0:42 │ │
│ │ ▓▓▓▓▓░░░░░  [Pause] [Stop]   │ │
│ └─────────────────────────────┘ │
│ NEEDS YOU                       │  only if WAITING_USER exists (purple, top)
│ ⏸ Press · C — unlock phone [Done]│
│ UP NEXT                         │
│ 14:30  Recurring Press · B (6m) │
│ RECENT RUNS            See all ›│
│ ✓ HTTP Check      14:00  ok     │
│ ✗ Recurring Press 13:30  FAILED │
├ Home  Tasks  Schedules  Settings┤
```
States: loading skeleton · empty ("No tasks yet — Import or create from a template") · error. Capability strip under the app bar shows chips for the tiers that matter (A11y ✓ · Shell ✗ · Alarms ✓).
### S02 Tasks (Library)
Search + chips (All / Ready / Needs setup / Scheduled). Card: icon, name, version, runtime badge (`py`/`flow`), status chip (**Ready** / **Needs setup (n)** / **Disabled**), last run chip, next run. FAB **+ Import** → bottom sheet: *From file · From link · From sample · New from template · Record a flow*.
### S03 Import and ImportReport
Progress → **ImportReport**: bundle name, sha-256 (copyable), manifest summary, **Errors** (blocking, red), **Warnings** (amber), **Compat** (per requirement: capability/app/API/imports with chips + Fix). Buttons: **Review permissions** → S05, **Install** (disabled until review done), **Cancel**. Dev-folder tasks show a **permission diff** instead (added/removed/widened scopes, one-tap **Approve**).
### S04 Task detail (tabs)
```
┌ ‹ Recurring Press     v1.2  py  ⋮┐
│ [Ready]  Next 14:30  ▶ Run       │
│ Overview│Config│Profiles│Schedule│History│Flow/Code│
```
- **Overview:** README, requirements table (capabilities with scope + live availability, apps with installed/version), compat report, runtime, insecure-feature notes (⚠ listed prominently), **Permissions…**.
- **Config:** schema-driven form (S06). **Profiles:** list (S07). **Schedule:** rules for this task (S10) + trigger rules (S13). **History:** runs for this task.
- **Flow/Code:** read-only view (flow YAML or file list) with copy; Task Studio editing arrives in Phase 11.
- ⋮ menu: Disable, Export bundle, Remove (two-step: keep history / delete everything).
### S05 Permission Review
Header "**Recurring Press** wants to…". One row per capability: plain-language meaning, **resolved scope** (e.g. *only in com.example.app*), *Required/Optional*, live availability chip. Optional rows have a switch. High-risk rows (shell, raw API, app manage, all files, SMS) get a red icon, an explanation and an extra confirm; if the global switch is off the row shows **Disabled by setting — Open switch**. Footer: **Allow** / **Deny**.
### S06 Config form (schema-driven, Tier A)
Widgets: text, multiline, number (min/max), switch, select, multi-select, interval, time, date, **app-picker** (installed apps with icon), profile-picker, file/folder-picker, secret, instance-target. Validation inline; **Reset to default**; "Changed → will re-ask permissions" notice when a scoped value changes.
### S07 Profiles
List with name, last run, status dot, archived toggle. Edit: schema fields + secrets (masked, "Replace"), **instance target** (device scope), **Clone**, **Archive**. Header explains: *profiles keep separate settings, secrets and history*.
### S08 Run screen (live)
```
┌ ‹ Recurring Press · Profile A    ┐
│ [RUNNING ●]  attempt 1   0:42    │
│ stage: wait_for_button  ▓▓▓░░    │
│ ⚠ Incident: button not found (1) │  incidents first (unresolved)
│ TIMELINE   ✓ launch  ● wait  ○ act │
│ LOG (live)  14:00:02 INFO …      │  monospace, level filter, pause scroll
│ EVIDENCE    [thumb][thumb]       │
│ [Pause] [Stop] [Take over]       │
```
`WAITING_USER` replaces the button row with a purple banner (instruction text + **Done — continue** / **Abort**). Failed runs show reason code + message (from errors.json) + **Retry stage / Resume / Export bundle**.
### S09 History and Run detail
Filter by task/profile/status/date. Run detail tabs: **Summary** (result, reason, duration, trigger), **Timeline** (stages, attempts), **Log**, **Evidence** (images + compact dumps; sensitivity labels; delete), **Audit** (broker calls), **Environment**. Actions: **Export bundle** (redacted; choose evidence), **Re-run**, **Delete**.
### S10 Schedules
List: rule summary ("every 30 min · 2 profiles · sequential"), next fire, last result, enabled switch, **late-timing badge** when exact alarms are unavailable. Editor: type (interval/daily/weekly/cron/once/window) with live "next 5 fires" preview, profiles + fan-out mode, limits, conditions, misfire policy, backend note.
### S11 Setup & Health (registry of HealthChecks)
```
┌ Setup & Health         [Re-check]┐
│ NEEDS FIXING NOW                 │
│ ✗ Accessibility service  [Fix]   │  required, no autofix
│ CAN FIX AUTOMATICALLY            │
│ ! AutoHost keyboard not selected [Fix now]
│ OPTIONAL                         │
│ ○ Shell tier  (PC needed on Android 10) [Guide]
│ ✓ Notifications   ✓ Exact alarms │
```
Rows show `title`, reason message, API-applicability, **Fix** (opens the right system screen or runs the autofix), and, for PC-assisted items, the exact command with a **Copy** button. A **Always-on device checklist** card (stay awake, lock, battery, autostart). Same data as `autohost doctor`.
### S12 Settings
Sections: General (retention, notification style) · Scheduling (backend: Auto/Alarm/Work/Job, reconcile) · Surfaces (floating bubble on/off, volume-key stop, tiles) · **Developer** (Dev API toggle + port + token + grants, dev folder, debug logging, slow-motion) · **Security (insecure-feature switches, default off; relaxed mode with warning)** · Data (export/import tasks+profiles, clear evidence) · About (versions, licences, THIRD_PARTY_NOTICES).
### S13 Triggers (rules)
List with source icon, summary ("notification from X matching /code/ → Task, cooldown 60 s"), status (last fire, count, cooldown remaining, queue depth). Editor: source picker (from `triggers.catalog`), params, cooldown, max launches, debounce, **busy policy** (queue/drop/replace), target task+profile, **Test fire** with a sample payload.
### S14 Shell pairing guide
Stepper. **Android 10:** 1) enable USB debugging, 2) on the PC run `autohost enable-shell` (copy), 3) authorize the key on the phone (screenshot of the prompt), 4) verify. **Android 11+:** Wireless debugging pairing (code entry). Shows state `NOT_PAIRED / PC_ASSIST_REQUIRED / READY` and the post-reboot reminder explanation.
### S15 Capability catalog
Searchable list grouped by category; each: id, summary, tier, risk, live availability, wave, example snippet, **Copy agent context pack**.
### S16 Task UI host (Tier A/B/C)
Tier A auto-form; Tier B renderer (§5 components); Tier C sandboxed WebView with a thin native frame (title, back, ⋮ → View as text). A **"This screen was provided by <task>"** footer for trust.
### S17 Prompts and WAITING_USER
`ui.ask` appears as a transparent-activity dialog (confirm / text / choice) or a notification with inline reply when the screen is off. `needs_user` notifications are high-priority with **Done** / **Abort** actions.
### S18 Recorder and Flow view
Recorder overlay: ● Record · ⏸ Pause · ■ Stop + step counter; after Stop → **Review steps** screen: list of steps with selector, confidence (stable id / text / relational / **coordinates ⚠**), delete/reorder, **Save as flow**. Flow view: YAML with syntax colour, **Run**, **Replay with guard**.
### S19 Inspector overlay
Draws node bounds with index badges over the inspected app; tap a node → bottom card with id/text/desc/class/bounds, **Copy selector** (ranked suggestions), **Add to recorder**. Scoped to one package; Stop button always visible.
### S20 Floating bubble (surface)
Small draggable bubble: status colour + stage; tap → expand (Pause, Stop, open run). Visible whenever a UI-acting run is active (can be hidden only if the ongoing notification + volume-key stop are on).

## 5. Component library (`:core:designsystem`)
`StatusChip(status)`, `AvailabilityChip(avail)`, `CapabilityRow(spec, grant, availability)`, `ProfileChip`, `StageTimeline(stages)`, `LogLine(event)`, `LogList(paged)`, `IncidentCard`, `HealthBanner`, `HealthCheckRow`, `EmptyState(icon, title, body, action)`, `SectionHeader`, `ConfirmDialog(consequences)`, `KeyValueRow`, `CommandCopyCard(command)`, `SelectorSuggestionList`, `ProgressBar`, `SecretField`, `AppPickerRow`, `IntervalPicker`, `RuleSummaryText`. Declarative-UI renderer components (Tier B): `column,row,card,tabs,text,markdown,input,toggle,select,button,progress,table,image,logview,stat,profile-picker`.

## 6. UI state sketches (Kotlin — shape, not final)
```kotlin
data class HomeUiState(val health: HealthSummary?, val active: List<RunCardUi>, val needsUser: List<RunCardUi>,
                       val upNext: List<UpcomingUi>, val recent: List<RunRowUi>, val loading: Boolean)
data class RunUiState(val run: RunHeaderUi, val stages: List<StageUi>, val incidents: List<IncidentUi>,
                      val log: LogPage, val evidence: List<EvidenceUi>, val actions: RunActions, val waiting: WaitingUi?)
data class RunActions(val canPause: Boolean, val canResume: Boolean, val canCancel: Boolean, val canRetryStage: Boolean, val canExport: Boolean)
data class PermissionReviewUiState(val task: TaskHeaderUi, val rows: List<PermissionRowUi>, val canAllow: Boolean, val diff: PermissionDiff?)
data class PermissionRowUi(val capability: String, val meaning: String, val scope: String?, val required: Boolean,
                           val risk: Risk, val availability: AvailabilityUi, val granted: Boolean?, val globalSwitch: SwitchUi?)
data class HealthUiState(val needsFix: List<HealthRowUi>, val autofix: List<HealthRowUi>, val optional: List<HealthRowUi>, val checklist: AlwaysOnUi)
data class ScheduleEditUiState(val draft: ScheduleDraft, val nextFires: List<Instant>, val errors: Map<String, ReasonCode>)
data class TriggerEditUiState(val draft: TriggerRuleDraft, val catalog: TriggerCatalog, val testResult: TriggerTestResult?, val errors: Map<String, ReasonCode>)
sealed interface UiEffect { data class Navigate(val route: String): UiEffect; data class OpenSystemScreen(val action: FixAction): UiEffect; data class Toast(val textRes: Int): UiEffect }
```
Every ViewModel: `StateFlow<UiState>`, `fun onEvent(UiEvent)`, `Flow<UiEffect>`. Previews for loading / empty / error / typical / worst-case.

## 7. Copy rules
- Reason copy comes from `schemas/errors.json` (`userMessage`, `fixAction.label`). Messages say **what happened, why, what to do**; no jargon, no codes in the main sentence (code shown as small monospace subtitle).
- Examples: `A11Y_SERVICE_DISABLED` → "Accessibility is turned off, so AutoHost can't see or tap other apps. [Open settings]". `PC_ASSIST_REQUIRED` → "This needs a one-time command from a computer after each restart. [Show command]". `UNVERIFIED_ON_HARDWARE` → "Built for newer Android; not yet tested on a real device."
- Time: relative for <24 h ("in 6 min"), absolute otherwise; always device locale/time zone.

## 8. Accessibility and performance
TalkBack labels on every control and chip (status read as words); focus order follows visual order; logs announce only level ≥ WARN; contrast ≥ 4.5:1 (verify both themes); min touch 48 dp; font scale to 200 %; landscape supported but not optimised. Paging for logs/history (page size 100); images downscaled; no list recomposition storms (stable keys). Low-RAM: no auto-playing animation, thumbnails capped.

## 9. Screenshot tests
Each screen's previews are the golden set for screenshot tests (Roborazzi/Paparazzi — choose in P2 via ADR). Required states: light/dark × normal/large font × {loading, empty, error, typical}.
