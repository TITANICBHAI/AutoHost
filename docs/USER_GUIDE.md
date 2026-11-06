# AutoHost — User guide

Everything a person needs to go from a fresh install to a working automation, and everything a
task author needs day to day. The architecture and the "why" live in `plan.md`; this guide is
the "how" on the phone.

## 1. Install and first launch

1. Sideload the APK (the README says where builds come from). The first launch shows Setup
   because nothing is granted yet — that is correct, not an error.
2. Play Protect may warn about unknown developers on the first install. This is expected for
   sideloaded, limited-distribution apps; do **not** disable Play Protect. The warning clears
   once the package registration is verified (docs/RELEASE.md §2).
3. Open **Setup & Health** (the second tab). It is a checklist, not a wall of switches: each
   row is one grant with a Fix button that takes you straight to the system screen. Take the
   list top to bottom — when Setup says "ready", the app is genuinely ready.

## 2. The three grants that matter

| Grant | What it unlocks | Notes |
|---|---|---|
| **Accessibility service** | Driving other apps: clicks, text, scrolls, screenshots (API 30+ path) | Off = most tasks cannot act; the app never misdeclares itself as an accessibility tool |
| **Notifications** | The run notification with Stop, plus the notification-listener capability | The Stop button on a run is your kill switch |
| **Exact alarms** | Schedules that fire on time | Denied = timing gets a "may be late" badge; everything still works, just less punctually |

Optional extras appear when a task needs them: WRITE_SECURE_SETTINGS (one-time, enables boot
self-heal — see §7), the shell tier (§8), media/SAF scopes for files, and the like.

## 3. Your first task

1. **Library → + Template** and pick `press-button` (the reference task). Templates ship as
   real task bundles — what you see is what runs.
2. Fill the config form (the app picker plus a button label). Forms are generated from the
   task's declared schema; nothing is hidden.
3. Tap **Run**. The run screen shows live logs; a notification with **Stop** appears for any
   run that acts on the UI.
4. When a run fails, the detail view shows the reason code, the stage, and (when the screen
   changed unexpectedly) a compact UI dump. Everything redacted, everything on-device.

Task sources: templates, the import flow (Open-with / share sheet for `.aatask` files), the
dev folder (`/sdcard/Android/data/app.autohost/files/dev/<task>/`), and the **repo** (§6).

## 4. Profiles, schedules and triggers

- **Profiles** are named bundles of config + secrets for one task (e.g. "home account",
  "work account"). Secrets live in the Android Keystore — never in the database, never in
  exports, never in logs (the canary test keeps everyone honest).
- **Schedules** support intervals, daily/weekly windows, a cron subset, one-shots and
  conditions (charging, unmetered, screen unlocked). Misfires reschedule honestly; two UI
  runs never overlap.
- **Triggers** fire tasks from events: notifications, toasts, battery/screen/network changes,
  app switches, other tasks (chains, max depth 3), webhooks, and external intents (token-guarded).
  Every rule has a live status view with cooldown/debounce/busy-queue state.

## 5. Surfaces

Runs are reachable wherever you are: quick-settings tiles, pinned shortcuts, a home-screen
widget, a draggable floating bubble (shows progress + Stop), notification actions, the
volume-key stop hold (off by default), and `RUN_TASK` intents for other automations (the
per-install token is under Surfaces).

## 6. Task Studio, the repo and the inspector (PC)

- **Task Studio** (Library → Studio) creates and edits **dev-folder** tasks on the phone:
  name/description/version fields, the entry file in a monospace editor, validate-before-save
  through the same validator an import uses. The **Repo tab** fetches an `index.json` over
  https, shows Install/Upgrade/Up-to-date verdicts, and installs through the normal import
  pipeline (integrity hash enforced when the index carries one).
- **The PC side**: `autohost push/run/logs/ui` drive the phone over the Dev API (off by
  default; Settings → Developer → Dev API issues the token). `autohost ui web` serves the
  browser inspector at `127.0.0.1:8099` — a live tree, selector try-box and screenshot, with
  the token never leaving the proxy. `autohost mcp` is the coding-agent entry point.
- **Without USB**: `autohost bridge --listen 8090 --http 8081 --token <token>` on the PC, then
  Settings → Developer → Reverse bridge on the phone (`ws://<pc-host>:8090`). The phone dials
  OUT; the token rides the first frame, never a URL.

## 7. Resilience

- **Boot self-heal** re-arms schedules after a reboot and re-enables the accessibility service
  (when the one-time WRITE_SECURE_SETTINGS grant exists: `adb shell pm grant app.autohost
  android.permission.WRITE_SECURE_SETTINGS`, then flip the toggle in Setup).
- **Checkpoints**: long tasks stage their progress; after a kill they resume without repeating
  completed stages.
- **Backup/restore** (Settings → Backup & packs): one JSON file with tasks + profiles. Secrets
  are never in the file — restore re-asks you for each secret by name. That is a feature; treat
  the backup file as non-sensitive but not secret-bearing.

## 8. The shell tier (optional, advanced)

The shell tier drives the device through Android's own ADB surface when the accessibility
service is unavailable. Priority: ADB-over-TCP (Android 10 phones — PC-assisted per boot,
`adb tcpip 5555`; `autohost enable-shell` prints the steps) → Wireless-debugging pairing
(API 30+) → Shizuku → root. Everything works without it; the tier is a fallback, not a
requirement. `device.shell` commands are allowlisted per task with an unconditional
metacharacter deny — one allowlisted command can never become arbitrary shell.

## 9. Plugins and app packs

- **Plugins**: an external APK that contributes capabilities (the example under
  `samples/plugin-example/`). Install it like any app; AutoHost discovers, validates and
  binds it. Tasks declare the plugin's capability id — updating the plugin changes nothing in
  the tasks.
- **App packs** (`.aapack`): the brittle per-app knowledge (selectors, screen signatures,
  labels) for one target app, installed under Settings → Backup & packs. Tasks reference packs
  via `uses_packs`; a fix ships once and every task picks it up next run. The run result names
  the pack version that was actually used.

## 9a. The Ask bar (the AI agent)

The **Ask** bar is a fourth kind of run: you state a goal ("Open Settings and turn on Do Not
Disturb", pick the apps it may touch, and an AI model drives the phone one step at a time until
the goal is done, a budget runs out, or you press Stop. It is **supervised by design** — the
model proposes, AutoHost disposes:

- **One action per turn.** The model sees one observation of the screen at a time and asks for
  exactly one action. Extra asks are refused, never executed.
- **It can only go where you allowed.** Before an Ask starts you pick the target apps. The
  agent cannot add an app mid-run, cannot use tools that are not on its allowlist (no shell, no
  SMS, no clipboard reading, no app lists), and cannot act in an app that is on your **blocked
  list** — content from blocked apps never reaches the model at all.
- **Risky actions ask you first.** When a control is labelled pay / send / delete / buy / order /
  confirm… or the app is unknown, AutoHost asks: **Allow once / Allow for this run / Deny**. No
  answer in time means **deny and the run stops**; "Deny" refuses just that action and the agent
  can try another way. The approval names the control as you see it — never "node 17".
- **It never types secrets.** Password, PIN, one-time-code and card fields are off limits even
  when the goal seems to need them.
- **It cannot declare itself done.** When the model says it finished, AutoHost takes a final
  screenshot-of-record and asks **you**: "The assistant says it is done: … Is it?" Only your
  Yes succeeds the run. No answer or a No ends it honestly (`COMPLETION_UNVERIFIED` /
  `COMPLETION_REJECTED`).
- **Budgets, always.** Turns, wall-clock time, model calls, tokens and device actions are capped;
  hitting a cap ends the run with a `BUDGET_*` reason. An interrupted Ask is **never** re-run
  behind your back.
- **Stop works mid-everything.** Stop, the notification and the kill switch interrupt even an
  in-flight model call or tap. Pause waits for the current step and holds before the next.

Requirements: AI set up (provider + key, Settings → AI), the accessibility service on, the
screen unlocked, and no other UI run active. Each failed requirement gives a typed reason with a
fix button and **starts no run**.

### What is sent to the AI provider (the data-egress disclosure)

First use shows this disclosure; it is repeated here in full.

**Sent to the provider:** your goal text; the agent's instructions; the list of tools it may use;
filtered observations of the screens it works with (element class, id, visible text or label up
to 80 characters, state flags, position and size, package names); the results of its tool calls;
your answers to its questions.

**Never sent:** screenshots (unless you turn them on for a provider), password text, API keys or
secrets of any kind, run tokens, the clipboard (the agent has no clipboard tool), notification
contents, your installed-app list, and **anything from a blocked app**.

Logs and the AI audit trail carry sizes and counts, not content. A full step-by-step transcript
is kept only if you turn `keep_transcript` on for a run, and then only inside that run's folder,
redacted; exporting it in a `.aarun` bundle is always your explicit choice.

### Action results: `effect` and `retry_if_no_change`

Every `device.ui` action result tells you what actually happened, not just that a call was
accepted:

- **`effect`** is `changed` (the screen state really moved — the state-aware check includes
  toggle/selection/enabled state), `unchanged` (nothing observable moved — treat the action as
  suspect), or `unverifiable` (the screen could not be compared honestly — never assumed either
  way). `verified` alone only ever means "the call was accepted", never "it worked".
- **`retry_if_no_change`** (opt-in on click/long_click) repeats the accepted action **once**
  when the screen did not change, then reports. It never loops, and it never falls through to
  another channel after an `unchanged` verdict — a second click that would flip a toggle back is
  exactly what this guard exists to prevent.
- Set-text results carry the same honesty: `verified` is true only when a read-back of the field
  shows exactly the requested text; a field that accepts the set but renders nothing reports
  honestly instead of succeeding.

## 10. Troubleshooting

| Symptom | First checks |
|---|---|
| A run ends `CAPABILITY_NOT_GRANTED` | Task detail → permission review; the fix button routes there |
| `NO_ACTIVE_WINDOW` / empty snapshot | Is the target app actually foreground? Screenshots/dumps degrade honestly — the reason is in the result |
| Schedules late or skipped | Setup & Health → exact alarms + battery exemption; OEM kill lists in `docs/OEM_MATRIX.md` |
| After a reboot nothing fires | Boot self-heal toggle on? WRITE_SECURE_SETTINGS granted? |
| Dev API unreachable | Toggle on + token copied? `adb forward tcp:8080 tcp:8080`? Or use the reverse bridge (§6) |
| A pack selector misses | The pack version is in the run result; bump the pack, not every task |

Honesty rule for everything above: when the app does not know, it says so (`UNVERIFIED`,
`BEST_EFFORT`, `degraded:true`) — the status is part of the result, not a footnote.
