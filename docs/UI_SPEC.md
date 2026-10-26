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
- **Flow/Code:** flow tasks get a **Flow** tab (P6-10): the document as text; JSON entries are editable with **Validate & Save** (parsed through the runtime parser first — a bad edit never reaches disk); YAML entries stay read-only until the ADR-052 plug lands. Task Studio (structured step editing) arrives in Phase 11.
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
### S16 Task UI host (Tier A/B/C) — built (P7-01/02)
Tier A auto-form stays the Config tab. Tasks with `ui.mode == "declarative"` or `"html"` get a **UI** tab on task detail:
- **Tier B** (`ui/main.json`): the Compose renderer draws the frozen v1 widget set (column/row/card/tabs/text/markdown/input/toggle/select/button/progress/table/image/logview/stat/profile-picker) with bindings `{{config.x}} {{profile.y}} {{run.status|stage|reason|attempt}} {{state.z}} {{input.NAME}}` (whole-string placeholder keeps the native type; unresolved renders empty). Actions: run, run_stage, call(handler, args) → `@ui.handler` in the `:ui` worker (30 s cap), navigate (switches detail tabs), confirm (two-step dialog with consequences). A returned `"state"` dict patches `{{state.*}}` live. Footer: **"This screen was provided by the task."**
- **Tier C** (`ui/index.html`): sandboxed WebView — **no network** (non-file requests 403), **no file escape** (only paths under the task bundle, else 404), JS bridge `autohost.invoke(handler, args)` (synchronous), `autohost.state()`, run-status pushes via `window.autohostEvent`. The page's strict CSP meta is defense-in-depth; the host boundary is the guarantee (ADR-055).
- A document that fails to parse is an honest error card, never a blank screen; the renderer panel is height-bounded inside the detail screen's list.
### S17 Prompts and WAITING_USER — built (P5-06 + P7-04)
`ui.ask` appears as a transparent-activity dialog (confirm / text / choice) or a notification with a full-screen intent; FLAG_SECURE on. P7-04 adds: `ui.dialog` (explicit buttons, returns the pressed label; "" on dismissal/timeout), `ui.form` (text/number/switch/select fields, secret masking, {name: value} on submit, None on dismissal), `ui.overlay` (short text strip via the a11y service's window — honest `{shown:false, reason:ACCESSIBILITY_OFF}` when the service is off), and `ui.console.write` (redacted `ui.console` events → logview + run history). `needs_user` notifications are high-priority with **Done** / **Abort** actions.
### S18 Recorder and Flow view
Recorder overlay: ● REC + step counter + ■ Stop (a `TYPE_ACCESSIBILITY_OVERLAY` strip via the a11y service — P6-09 written); after Stop → **Review steps** screen: warnings, list of steps with selector + confidence chips (STABLE ID / DESC / TEXT / RELATIONAL / **COORDINATES**), delete/reorder, **Save as flow** — disabled while any coordinate step lacks its explicit confirmation checkbox (G6-e). Flow view: JSON as text (edit-as-text with validate-before-save); syntax colour and **Run / Replay with guard** buttons arrive with Phase 7 surfaces (guard engine itself is written, P6-08).
### S19 Inspector overlay — built (P7-11, v1)
Started from **Settings → Surfaces**; scope is the *live foreground package* (tracked via ForegroundTracker — switching apps re-scopes automatically; AutoHost itself is never drawn on). One full-screen a11y overlay draws every node's bounds with its short resource id or a text snippet; a tap picks the deepest/smallest node under the point, highlights it and **copies the selector JSON** to the clipboard — the bottom strip shows `[basis] {…}` where basis is resource-id / text / content-desc / class+index / pkg+class (the `SelectorBuilder` ranking; every candidate round-trips through `SelectorEngine`, so what is copied re-finds the node). **Copy** repeats the last copy; **Exit** ends the mode. v1 limits (ADR-056): the overlay absorbs touches while inspecting (it is an explicit mode, not a HUD), and "Add to recorder" stays a v3 item — the selector format is already the recorder's.
### S20 Floating bubble (surface) — built (P7-06, v1)
A small draggable strip over other apps (a11y overlay window, no SYSTEM_ALERT_WINDOW needed): `task · stage (+n)` + **Open** + **Stop** (the global kill switch). Visibility = `BubblePolicy`: **auto** (default) shows while an active run is UI-acting, **always** while any run is active, **off** never. Set in Settings → Surfaces. Stop cancels all runs, revokes tokens, kills workers and closes prompts (`AppGraph.globalStop`).
### S21 Surfaces settings — built (P7-06..12)
Settings → Surfaces hosts every surface's controls: bubble mode chips; the five tile bindings (tap a task to bind/unbind; API 13+ "suggest tile 1" via `requestAddTileService`); the widget task picker; the external-trigger token (copy / regenerate + the `am broadcast` example); inspector Start/Exit; the volume-key stop (off by default; key choice Volume−/Volume+, hold 0.6–3 s); and per-task pinned-shortcut buttons. The task detail page also offers **＋ Add to home screen** (P7-08).
### S22 Triggers — built (P8-03/04)
The task detail **Overview tab** hosts a Triggers section listing every rule of the task: source, one honest status line (last fire minutes, launch count, cooldown/debounce/busy settings, `armed`/`live`), an enable switch, **Test** (dispatches a synthetic event through the live guard chain — the result dict is shown inline) and **Delete**. **Add trigger** opens a source-driven editor: a scrollable source chip row (event kinds + `time.*`), the params fields that source actually uses (package/title/text regexes, battery op+percent, delay seconds, external name, chain task+on, SSID), then `cooldown_seconds`, `debounce_ms`, `max_launch_count`, the busy-policy chips (`drop`/`queue`/`replace` with queue max) — saved through `triggers.upsert` semantics with inline `TRIGGER_RULE_INVALID(field)` errors. Rules whose source needs a grant the task lacks show the §11.8 note in red ("needs the 'device.notifications' grant — events are dropped"). The event-hub FGS need (ADR-057) is derived from the enabled rules, not a user toggle. Debounce holds and busy queues are labelled in-memory: a process restart drops queued events.

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


### S23 — Settings → Webhooks (P8-08)
Card under a "Webhooks" section header, same tokens as the Dev API card.
- **Body copy:** "POST http://127.0.0.1:8082/hook/<name> with header X-AutoHost-Token fires the matching external trigger rule. Hook names come from the task's trigger rules."
- **Enabled switch** — writes `webhook.enabled` and reconciles the endpoint immediately (start/stop applies port/token/LAN).
- **When on:** token line (`Token: <first8>…`), Copy token, Rotate (writes `webhook.token`, restarts the endpoint), and a "Reachable from the LAN (token still required)" switch (`webhook.lan`; the token stays mandatory in both bindings — ADR-061).
- **Empty states:** endpoint off shows only the copy + switch; no hooks configured is fine (the trigger editor S22 lists `external` sources to name one).

### S24 — Settings → Shell tier (advanced) (P9-12)
One card under a "Shell tier (advanced)" header, above Developer & security:
- **Header copy:** "Pair the shell tier" + a two-line explainer (unlocks device.shell, app manage, logcat, radios, display; the ADB-over-TCP path resets at every reboot on Android 10 and AutoHost reminds you).
- **Transport chips:** one row per configured transport in chain order (`adb.tcp` → `wireless` → `shizuku` → `root`), label left, state word right ("live" primary colour, "awaiting_authorization" tertiary, "needs_setup"/"unsupported" muted + the reason code as a small subtitle).
- **Actions:** "Check now" (force re-probe, becomes "Checking…" while in flight; remembers a live tier for the boot reminder) and "Copy adb steps" (`adb tcpip 5555 && adb connect <phone-ip>:5555`).
- **One-time PC grants block:** the WRITE_SECURE_SETTINGS grant line + a pointer to the CLI (`autohost grant-secure-settings`).
- **Experimental toggle:** "UiAutomation UI link" with the honest copy ("Exclusive with accessibility — enabling this closes the accessibility session while the link is in use; they can never run together. Off by default.").
- **OPEN_SHELL_SETUP** fix actions route here from the shell providers' NEEDS_SETUP envelopes.
- **Reminder notification** (post-reboot): "Shell tier needs re-arming" — body spells the per-boot ritual, tap opens the app (Setup).

### S25 — Browser surface (P10-02)
Every `browser.webview` page is a plain full-screen activity — visible, named, user-stoppable:
- **Chrome strip (dark):** the page title (falls back to the URL while loading) on the left, a "Close" button on the right. Close finishes the page through the controller — the run's next browser call answers `CAPABILITY_UNAVAILABLE(BROWSER_SURFACE_CLOSED)` honestly instead of reopening anything.
- **Back gesture** is a Stop affordance: it closes the page; it never silently navigates an automated surface away.
- **No stealth controls exist**: no user-agent/viewport/locale overrides anywhere in the module by construction; per-profile isolation is a data-directory suffix (S26), never a UI disguise.
- **Run-scoped lifecycle:** pages are closed and the CDP forward removed on the run's terminal transition (AppGraph collector); the profile lock serializes cross-profile pages with the limitation documented in ADR-070.

### S26 — Settings → Backup & packs (P11-04/P11-08)
One card, two halves, both honest:
- **Backup:** Export (SAF `CreateDocument`, `autohost-backup.json`) and Restore (SAF open) with an explicit "Overwrite installed tasks on restore" switch (off = restore plans skip existing tasks). The status line always names the outcome in counts — "restored N task(s), M profile(s), skipped K — re-enter profile secrets (names in the plan)"; failures carry the codec's reason (e.g. `SECRET_SHAPED_KEY`) verbatim. Nothing ticked-only: the empty state says "Nothing exported or restored yet."
- **App packs (.aapack):** Install pack (SAF open) + a per-pack row `{id} @ {version} → {target}` with Remove. The explainer line names the mechanism: "referenced from tasks via uses_packs and injected as config.packs each run." Rejections print the pack.json issues verbatim — a pack never lands half-valid.

### S26a — Settings → Developer → Reverse bridge (P11-07)
Shown inside the Dev API card once a token exists: a `ws://pc-host:8090` URL field, Connect/Stop, and a state line (`state:` plus the last error verbatim). The URL is deliberately NOT persisted — a device-initiated socket is opt-in per session, and the field's placeholder teaches the protocol. Dev API off = the row never appears.

### S27 — Task Studio (P11-05, Library → Studio)
Three modes behind one tab row: **Tasks** (New-task card: id + name + a python/flow/javascript runtime selector; the dev-folder task list with Edit), **Repo** (index URL field + Load, then per-entry cards: title, `id · version · verdict message`, and Install/Upgrade buttons only for verdicts that allow them — UpToDate/Rejected rows state their case), **Editor** (Name / Description / Version fields, the entry file in a monospace editor, Save (validates) + Validate buttons, and the standing note: "Drafts live in the dev folder… Studio never edits installed bundles"). Validation messages are the ManifestValidator's own strings — the Studio never paraphrases the rules it enforces.
