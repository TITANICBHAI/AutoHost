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
