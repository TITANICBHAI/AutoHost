# AutoHost — Android-Native Automation Platform
## Implementation Plan (v3 — reference-informed, Android 10 to latest)

> **Working name:** `AutoHost` (package `app.autohost`, Python SDK module `autohost`). Rename freely; keep the name in one place (`gradle.properties` + `Branding.kt`).
> **Audience:** the implementing coding agent. The planner does not write code.
> **Revision note (v2):** rebuilt from the product vision. Any existing Python/PC automation repo (e.g. the "AFKFarmer" zip the user shared) is only an *example of a script a user might one day want to run*. It is a PC project: its "Android part" is driven from a PC over ADB. **None of its structure, state names, libraries or flow is a design input.** If you are handed that repo, do not mine it for architecture; at most it is a late, optional compatibility exercise (§18, Phase 10).
> **Versions:** this plan does not pin library versions. Look up current stable versions of AGP, Kotlin, Compose BOM, Room, WorkManager, Chaquopy, etc. at implementation time and record them in `docs/VERSIONS.md`.
> **v3 (reference review):** this version folds in lessons from five reference projects (Mobilerun Portal, Mobilerun, OpenAutoJs, Maestro, Appium) and Google's Artemis — see `docs/REFERENCES.md`. **Licence rule:** Portal is AGPL-3.0 and OpenAutoJs is GPL-3.0 + non-commercial: *concepts only, clean-room, never copy code*. Maestro/Appium (Apache-2.0) and Mobilerun (MIT) may be adapted with notices preserved.
> **Companion files** (the agent pack, stored under `plan/` in this workspace): `AGENTS.md` (session entry point and agent rules), `TRACKER.md` (batch/verification history), `docs/PROGRESS.md` (phase/task checklist and weighted progress), `docs/GATES.md`, `docs/REPO_LAYOUT.md`, `docs/ANDROID_MANIFEST.md`, `docs/UI_SPEC.md`, `docs/CONVENTIONS.md`, `docs/FIXTURE_APP.md`, `docs/DECISIONS.md`, `docs/DEVICES.md`, `docs/SPIKES.md`, `docs/REFERENCES.md`, plus `schemas/`, `sdk-python/`, `samples/`, `prompts/`. Paths within the source pack are relative to `plan/`.
> **Platform floor (v2.1):** the app supports **Android 10 (API 29) through the latest release** — `minSdk = 29`. The user's own phone runs **Android 10**, so it is the primary real test device and the *API-29 code paths* (§3.5) matter most. Behaviour that only exists on newer Android is always an optional provider with a fallback, never an assumption.

---

## 0. How to use this document

### 0.1 Reading order
1. §1–§4: what we are building, what Android really allows, design pillars.
2. §5–§7: architecture, the capability catalog (the heart of the product), the runtime and SDK.
3. §8–§15 as needed per phase.
4. **§18 is your work queue.** Everything else is reference.

### 0.2 Working agreement
- **Authorization comes first.** This plan defines the implementation contract; it does not authorize implementation. Do not begin app-code work until the user explicitly authorizes it. Stop and ask if authorization or requested scope is unclear.
- **A context handoff is read-only.** When a new agent receives `PRE_AGENT.md` as context, reading the plan is not permission to edit files. Wait until the user explicitly says to start editing. This is separate from the explicit authorization required before app-code implementation.
- **Work alone and preserve scope.** Do not create, invoke, or delegate to sub-agents. Follow the plan and tracker in order; do not silently widen scope. Inspect relevant code, project instructions, and the working tree before editing, and preserve unrelated user changes and data.
- **Protect user data and credentials.** Treat task replacement and other data deletion as destructive; keep replacement disabled by default and require authorization before destructive actions. Never expose, copy into files, or print secrets or credentials.
- **Track batches and checks as they happen.** Mark a batch in progress before work, and mark an authorized phase in progress with a dated note before phase work. After every meaningful change and every individual verification, immediately record status, changed files, exact command, observed result, failures, blockers, decisions, and deferred work in `TRACKER.md`; keep `docs/PROGRESS.md` task checkboxes and evidence in sync. Record evidence for each completed item and batch. Tick only completed, verified work; preserve failures and blockers, and append fixes and successful reruns without erasing history. Before finishing, reconcile both trackers with the actual code and evidence.
- **Testing and GitHub limits.** Test only the production flavor. Use behavior tests with fake clocks and event sources; do not test by reading Kotlin source as text, and do not call the system wall clock directly in logic under test. On Replit, run supported tests through existing workflows or scripts; do not build APKs. Use GitHub Actions for APK-build verification only on explicit request. Do not push to GitHub or start, poll, or monitor GitHub Actions unless explicitly asked; otherwise state which checks were not run.
- Work **one phase at a time**. Do not start phase N+1 until phase N's gate has been demonstrated. Record evidence (command, test name, screenshot path, device used) in `docs/PROGRESS.md`. A green build alone never passes a gate; gates specify behaviours to demonstrate on a real device.
- **Spikes first (Phase 0).** The riskiest assumptions are tested on the user's real device(s) before architecture hardens. A failed spike triggers its listed fallback, not a redesign.
- **Thin vertical slice first (Phase 1):** a Python script that clicks something in another app, with live logs and a working Stop, before building libraries, schedules, profiles or fancy UI.
- **Fail closed.** Unknown state, missing capability, ungranted permission, stale node, malformed manifest → a typed error with an actionable message. Never guess, never silently continue.
- **No app-specific or service-specific code in the host.** If you write `if (package == "...")` anywhere outside `samples/` or `testing/`, stop: it belongs in a task.
- **One source of truth for the SDK:** capability specs (§5.3) generate the catalog, Python stubs, docs and agent pack (§5.5). Never hand-maintain a parallel list.
- Every capability, SDK function and manifest field gets: spec, docs entry, unit/contract test, and (if it touches the device) an instrumented test against the fixture app.
- For ordinary implementation details, use the default in §21 and log a 5-line ADR in `docs/DECISIONS.md`. If scope, authorization, destructive behavior, privacy, or security posture is unclear, stop and ask rather than infer approval.
- **API-level discipline:** `minSdk = 29`. Every behaviour that differs by Android version lives behind the §3.5 matrix: a provider declares its minimum API and its fallback, and `availability(env)` decides. `Build.VERSION.SDK_INT` is never checked ad hoc in feature code — only inside `core:common` (`ApiGate`) and provider availability. Newer-Android paths that cannot be run on real hardware are validated on emulators and marked **UNVERIFIED-on-hardware** in `docs/DEVICES.md` and in the CompatReport.
- **Primary device = the user's Android 10 phone.** Every gate marked *(real device)* is run on it. A feature must not pass a gate through an API-30+ path that the phone cannot use.
- Keep commits small and phase-scoped.

### 0.3 Information to collect from the user before Phase 0 (ask once, record in `docs/DEVICES.md`)
The primary device is the user's **Android 10 phone**: record model, OEM skin, security-patch level, CPU ABI list (`getprop ro.product.cpu.abilist` — some Android 10 phones are 32-bit-only), RAM, WebView version, and whether it is rooted. Also: whether the user has other real devices (record each one's Android version, RAM and ABI) or a spare always-on device; a PC with `adb` and a USB cable for development (the Android 10 shell tier needs it, §3.1); two or three real third-party apps the user may eventually automate (S2 test targets only, never host code); preferred app name.
**Test matrix:** the primary Android 10 phone for every real-device gate; any additional real devices for paths the primary phone cannot exercise (record in `DEVICES.md` which paths each one verified); plus emulator images for API 30, 33, 34 and the latest release for newer-OS behaviour. Emulators cannot reproduce OEM task killers or real Wireless-debugging pairing, so those paths stay labelled UNVERIFIED-on-hardware until run on a real device.

### 0.4 Boundaries (from the product vision — enforced by design, not just policy text)
The platform executes **visible, user-authorized, user-defined work on the user's own device and accounts.** The host must not ship features whose purpose is to: solve or bypass CAPTCHAs or anti-bot challenges (tasks pause for the user instead — `WAITING_USER`); spoof device or browser identity or hide that automation is happening; circumvent a service's rate limits, usage limits or access controls; harvest credentials or unrelated app content.
Design consequences: per-package scopes (§6, §14), a persistent notification + floating Stop control while any run acts on the UI, a global kill switch, secrets only on declared fields, and no stealth features anywhere. Authors are responsible for respecting the terms of services their tasks touch; the docs say so.

---

## 1. What we are building

### 1.1 Summary
One Android app (the **host**) that gives scripts the environment and the **real device capabilities** they need to do useful work on a phone, so a new automation costs *a script*, not a new app. The host provides: a Python runtime (first-class, other runtimes pluggable), a tiered capability layer behind a permission broker, a task library with per-task settings, **profiles**, optional UIs, **triggers and schedules**, run tracking with recovery, and debugging tools. Scripts decide what the work is.

### 1.2 Three promises
1. **Reach** — scripts can do *real work* on the phone: drive other apps' UIs, launch/stop apps, react to notifications and device events, call the network, touch files, present their own UI, and (with explicit grants) reach almost any Android API. Breadth is tiered with graceful degradation (§3, §6).
2. **Leverage** — a task is a small bundle. Profiles, config forms, scheduling, logs, history, recovery and permissions come free.
3. **Trust** — runs are visible, stoppable, recoverable, and honest about what is unsupported on this device.

### 1.3 Design archetypes (used to test generality — none is privileged)
| ID | Example | What it demands from the platform |
|---|---|---|
| A | **Recurring app interaction**: every N minutes, for each profile, open app X, wait for a state, press a button | UI observe/act, app launch, schedules, profiles, post-action verification, awake/unlock handling, per-profile memory |
| B | **Background utility**: poll an HTTP API, post a notification, write a file | No UI lock, runs with screen off, net, notify, files, concurrency, adaptive rescheduling |
| C | **Event reaction**: when a notification from X arrives, copy a code; when the charger connects, run Y | Event triggers, notification listener, trigger payloads, minimal-privilege event delivery |
| D | **Long multi-stage workflow**: many screens, maybe a browser, a human hand-off mid-way, resume later | Stages/checkpoints, `WAITING_USER`, evidence, resume semantics, long timeouts, browser (later) |
| E | **Interactive tool with its own UI**: form → run → show results; pinned to the home screen | Task UI tiers, system surfaces, imperative prompts |

**Rule:** a design decision must serve at least two archetypes. If it only serves one specific app or service, it is a task-level concern, not a host feature.

### 1.4 Glossary
**Task** = installed automation bundle. **Profile** = a named instance of a task's per-account/per-instance settings, secrets, persistent store and history. **Run** = one execution of a task (optionally for one profile). **Capability** = a permissioned thing scripts can do (`device.ui.act`). **Provider** = Kotlin implementation of a capability on some **tier**. **Grant** = user approval of a capability with a **scope**. **Trigger** = what starts a run. **Surface** = a system-level entry/control point (tile, shortcut, widget, notification, bubble).

### 1.5 Non-goals (v1)
Play Store distribution; running desktop libraries/tools unchanged (no Playwright/ADB-binary shims — scripts are written for this SDK); hard OS-level sandboxing of hostile scripts (§14 is explicit about this); bypassing the lock screen; Android 9 and below (`minSdk = 29`); iOS.

---

## 2. Prior art and what we borrow

Full analysis, licence rules and a per-module study list: **`docs/REFERENCES.md`**. Summary:

| Project | Licence | Worth borrowing | Lesson / pitfall |
|---|---|---|---|
| **Mobilerun Portal** (on-device accessibility service + local API) | AGPL-3.0 — *concepts only* | Hardened tree reads (guard, multi-window roots, bounded read gate with degraded snapshots), custom IME for text/clipboard, MediaProjection auto-accept, keep-awake watchdog, trigger rules, HTTP/WS/ContentProvider/reverse-WS control API, debug fixture activities, policy-class testing | Closest analogue of our T1 tier. Its hardening shows how fragile raw accessibility reads are. We do **not** copy code and we do **not** set `isAccessibilityTool` |
| **Mobilerun** (Python LLM-agent framework) | MIT | Driver abstraction + recording decorator, guarded macro replay, credential-by-key, app cards, indexed compact UI text, MCP adapter, Skill-format docs | We host scripts, not LLM loops; no stealth drivers |
| **Maestro** (YAML mobile UI flows) | Apache-2.0 | Declarative commands, relational selectors, settle-after-action, retry-if-no-change, in-process FakeDriver tests, agent-friendly `AGENTS.md`, MCP server, IME for Unicode, inspector (Studio) | Needs a PC; we run on the phone. Instrumentation (UiAutomation) conflicts with accessibility services |
| **Appium** | Apache-2.0 | Extension manifests with migrations, insecure-feature gating, doctor checks, command middleware, idle-session timeouts | Server/client protocol is out of scope |
| **Auto.js family** (OpenAutoJs) | GPL-3.0 + NC — *concepts only* | Selector breadth, click fallbacks, API namespace breadth, volume-key stop, pluggable scheduler backends + reconcile worker, layout inspector | Weak reliability tooling and security; per-script APK packaging is exactly what we avoid |
| **Google Artemis** (agent, Apache-2.0) | Apache-2.0 | Accessibility helper that avoids the UiAutomation connection, dynamic-first locating, pre-action safety net, incidents, verification levels, MCP tools, web console | LLM-per-step cost; review the repo directly |
| **SL4A**, **Tasker/MacroDroid**, **Termux:API**, **Shizuku** | various | Curated facade; event triggers + setup UX; per-feature Android permissions; shell-level power without a PC | See `docs/REFERENCES.md` |

---

## 3. Android reality: tiers, constraints, installation

### 3.1 Capability tiers (every tier is optional, discoverable, honestly reported)
| Tier | Mechanism | Gives scripts | Requires | Notes |
|---|---|---|---|---|
| **T0 Host-native** | Kotlin providers over normal Android APIs | HTTP/WebSocket/local server, files (private + user-picked folders), notifications, intents, clipboard (foreground), audio/TTS/vibrate/flashlight, battery/network/device events, WorkManager/alarms, and — each with its own Android permission requested lazily — contacts, calendar, location, camera, mic, SMS | Normal permissions or special accesses | The reliable bulk of real work that needs no UI scraping |
| **T1 Accessibility** | Our `AccessibilityService` | Read other apps' UI trees, click/type/scroll, gestures, global actions (back/home/recents/notifications/quick settings/lock/screenshot), window and app-foreground events, silent screenshots (**API 30+ only** — on Android 10 see the chain in §3.5), accessibility-overlay windows (no overlay permission needed) | User enables the service | Primary "act inside other apps" tier. Can be blocked by Advanced Protection (§3.2) |
| **T2 Raw Android bridge** | Chaquopy's `java` module inside the worker | Any app-level Android API from Python (system services, content providers, reflection) | Grant of `android.api.direct` (dangerous) | The **escape hatch**: authors and agents never hit a ceiling when curated capabilities don't cover something. Popular raw uses get promoted to curated T0 capabilities later |
| **T3 Shell-level** | On-device ADB client (our own) over **ADB-over-TCP** (API 29+, needs a PC once per boot) or **Wireless debugging** (API 30+, fully on-device); or Shizuku; or root | `input`, `am`, `pm`, `settings`, `svc`, `cmd`, `uiautomator dump`, `screencap`; verified force-stop; install/uninstall; `--user` targeting; one-time grant of `WRITE_SECURE_SETTINGS` to ourselves; alternative provider for `device.ui` when T1 is unavailable | **Android 10:** a PC + USB cable runs `adb tcpip 5555` after each reboot, then the app connects to `127.0.0.1:5555` and the user authorizes its key once. **Android 11+:** Wireless debugging paired on-device (needs Wi-Fi/hotspot). Or Shizuku (PC-started on Android 10), or root | Optional power tier; powers verified stop, radios, self-heal |
| **T4 Vision** | ML Kit OCR, OpenCV template matching, QR decode | Find things on node-less UIs | A screenshot source (T1/T3) | Hints, never sole proof of a state change |

Special accesses the Setup & Health center must handle (§13): Accessibility, Notification access, Notifications permission, Exact alarms, Battery-optimization exemption, Display over other apps (optional), Usage access (optional), Write settings (optional), Do Not Disturb access (optional), Manage all files (optional), `WRITE_SECURE_SETTINGS` (via T3 grant).

### 3.2 Hard constraints and how the plan answers them
| Constraint | Reality | Plan response |
|---|---|---|
| Screen on and unlocked | UI automation (T1/T3) needs an interactive window; apps cannot bypass a secure keyguard | Scheduler pre-flight (§11.5); `WAITING_USER(unlock)` hand-off; the always-on recipe (§3.4); best-effort wake + dismiss for non-secure keyguards; **never** store the device PIN by default |
| Background activity starts | Restricted since Android 10. Documented exemptions include a visible window, a service bound by the system such as an **AccessibilityService**, and the **Display-over-other-apps** (`SYSTEM_ALERT_WINDOW`) permission. A blocked launch is **silent** (only a logcat line) | Launch chain: start from the accessibility-service context → with the overlay permission granted → full-screen-intent notification → T3 `am start`; **always verify the foreground package afterwards**; ranking confirmed on the Android 10 phone in S2 |
| Force-stopping other apps | Needs system/shell privilege | Chain: T3 `am force-stop` → drive the App-info screen via T1 → `killBackgroundProcesses` after backgrounding. Result reports `VERIFIED` / `BEST_EFFORT` / `FAILED` |
| Toggling Wi-Fi/Bluetooth/airplane/mobile data | Blocked for normal apps on modern Android (**verify per version**) | T3 `svc`/`cmd`; otherwise open the relevant Settings panel and drive it via T1 |
| Clipboard in background | Reads limited to foreground/IME | Works when the host has focus; documented fallback via T1 paste |
| Screenshots | T1 `takeScreenshot` needs **API 30+ — not available on Android 10**; secure windows return nothing (shell `screencap` is black there too) | Provider chain in §3.5 (MediaProjection with one-time consent on API 29, T3 `screencap`, last-resort global-action screenshot); typed `SCREENSHOT_BLOCKED` / `PROJECTION_NOT_GRANTED`; UI-tree evidence always works, screenshots are best-effort on API 29 |
| **Advanced Protection Mode** | Opt-in mode (Android 16+); Android 17 beta reports show it blocking/revoking Accessibility for apps not declared as accessibility tools — expected in stable, **verify on device (S2)** | Detect via `AdvancedProtectionManager` (API-guarded); surface a **named** state `A11Y_BLOCKED_ADVANCED_PROTECTION` with guidance; T3 stays a real alternative `device.ui` provider; **do not set `isAccessibilityTool="true"`** — this is not an accessibility tool and misdeclaring invites trust/policy trouble |
| "Restricted settings" | Android 13+ can grey out Accessibility/Notification-access toggles for apps installed outside stores; behaviour depends on installer | Setup wizard walks through App info → ⋮ → Allow restricted settings when needed; detect and explain |
| Doze and OEM task killers | Aggressive background limits | Exact alarms, foreground service, battery exemption, OEM autostart guide, health dashboard, soak tests, self-heal (§3.4) |
| Native Python packages | Chaquopy's `pip` runs at build time, binary wheels only | Curated bundled set per build flavor + vendored pure-Python in task bundles (§7.7) |
| Desktop-only libraries | Playwright, Selenium, pyautogui, tkinter, etc. cannot run | Replacement capabilities (`device.ui`, `browser`), linter with suggestions (§15). No shims |
| Shell-tier friction | Android 10 has **no on-device Wireless-debugging pairing** (Android 11+ does), so the shell tier needs a PC after every reboot; where Wireless debugging exists it needs Wi-Fi/hotspot and Android turns it off when Wi-Fi drops | Optional tier; `autohost enable-shell` PC helper; post-reboot reminder notification; the one-time `WRITE_SECURE_SETTINGS` PC grant (survives reboots) removes most other manual steps; wireless-pairing path built for API 30+ and labelled UNVERIFIED-on-hardware until tested |

### 3.3 Distribution and installation
- Assume **sideloading**. Accessibility-for-automation, `QUERY_ALL_PACKAGES`, exact alarms and `specialUse` foreground services do not fit Play policy for a general automation tool.
- **Developer verification:** Google is rolling out identity verification for apps on certified devices (user-facing enforcement began Sept 30, 2026 in a small set of countries, with a global rollout planned for 2027). Unregistered apps can still be installed via ADB or an "advanced flow" with extra steps. Hobbyists can use a free **limited-distribution** developer account (up to 20 devices, no government ID or fee). **Action:** register the package name early under such an account, record it in `DECISIONS.md`, and re-verify current rules at implementation time. Development builds install with `adb install` (always available, including on the Android 10 phone). Whether verification enforcement reaches older Android versions must be re-checked at implementation time.
- **Play Protect heuristics:** an app combining Accessibility, Notification access, SMS and overlay permissions resembles malware patterns and may be warned about. Mitigate with two build flavors: **`core`** (accessibility, notification access, network, files, notifications — *no* SMS/call/camera/mic declared) and **`full`** (everything). Keep each flavor's manifest minimal and documented; sign with a stable key; document the warning flow honestly (never advise disabling protections wholesale).

### 3.4 Always-on automation device recipe (surfaced as a checklist in Setup & Health)
For reliable recurring UI automation, use a dedicated or semi-dedicated phone on a charger: Developer options → *Stay awake while charging*; screen lock None/Swipe (or a Smart Lock mode if a lock is required); Battery → Unrestricted for AutoHost plus the OEM autostart/protected-apps setting; Wi-Fi kept on during sleep; adaptive battery/app standby relaxed for AutoHost where the OEM allows.
**Recommended one-time power setup (needs a PC once):** one-time `pm grant <package> android.permission.WRITE_SECURE_SETTINGS` (run once from a PC with `autohost grant-secure-settings`; the grant survives reboots; on Android 11+ the app's own wireless-debugging pairing can also do it). With it, AutoHost can (a) keep *stay awake while charging* on, (b) re-enable its own accessibility service if the system or an OEM turns it off (not possible while Advanced Protection blocks it), (c) keep ADB enabled (and, on Android 11+, Wireless debugging). On the Android 10 phone this is what lets AutoHost bring its accessibility service back after a reboot or an OEM kill without you touching Settings. Everything is opt-in with a plain-language explanation.

### 3.5 API-level matrix (minSdk 29) — what the Android 10 floor means
The app supports **Android 10 (API 29) → latest**, and the user's own phone is on Android 10. Every row is a place where behaviour differs by Android version. Providers implement the right variant (or declare a fallback) and report it through `availability(env)`; nothing outside `core:common` (`ApiGate`) and provider code reads `SDK_INT`.

| Area | Android 10 (API 29) — the user's phone | Android 11+ (API 30+) |
|---|---|---|
| Silent screenshots | **Not available through Accessibility.** `device.screenshot` chain: (1) MediaProjection — one-time system consent, needs a foreground service of type `mediaProjection`, token lost if the process dies (then `PROJECTION_NOT_GRANTED` / `WAITING_USER`); (2) T3 `screencap`; (3) last resort, **off by default**: `GLOBAL_ACTION_TAKE_SCREENSHOT`, then collect the file from MediaStore (noisy; delete afterwards) | API 30+: `AccessibilityService.takeScreenshot`, no consent. API 34+: MediaProjection consent becomes per-session, so prefer the Accessibility path |
| Shell tier | **No on-device Wireless debugging.** PC-assisted per boot: `adb tcpip 5555`, then the app's ADB client connects to `127.0.0.1:5555` and the key is authorized once. Shizuku also needs a PC start per boot | Wireless-debugging pairing fully on-device (needs Wi-Fi/hotspot); Shizuku can start itself that way |
| `WRITE_SECURE_SETTINGS` | One-time `pm grant` from a PC (survives reboots): self-heal of the accessibility service, keep-awake, ADB toggles | Same, or granted through the on-device shell tier |
| Package visibility | All installed packages visible | Declare `<queries>` / `QUERY_ALL_PACKAGES` (sideload) |
| Shared storage | Scoped storage with the `requestLegacyExternalStorage="true"` opt-out plus `READ/WRITE_EXTERNAL_STORAGE` gives broad access (`files.all`) | Opt-out ignored for target ≥30: SAF grants, MediaStore, or "All files access" (`MANAGE_EXTERNAL_STORAGE`); granular media permissions on API 33+ |
| Dev folder | Default `getExternalFilesDir("dev")`; `adb push` works, no storage permission needed | Same on every version (file managers cannot browse `Android/data` on 11+; `adb push` still works) |
| Foreground services | Type attribute exists but is optional, except `mediaProjection`; no `specialUse` | API 34+: a type is mandatory → `specialUse` + subtype property; declare `FOREGROUND_SERVICE` plus the type-specific permissions on API 34+; use version-guarded `startForeground` (`ServiceCompat`) |
| Starting an FGS from the background | Allowed (call `startForeground` within ~5 s) | API 31+: restricted; exemptions include a fired exact alarm |
| Exact alarms | `setExactAndAllowWhileIdle`, no permission | API 31–32: `SCHEDULE_EXACT_ALARM` (auto-granted); API 33+: `USE_EXACT_ALARM` (sideload) or user-granted `SCHEDULE_EXACT_ALARM`; API 34+: denied by default — check `canScheduleExactAlarms()` |
| Notifications permission | Enabled by default | API 33+: `POST_NOTIFICATIONS` runtime permission |
| Restricted settings (sideload) | Not applicable | API 33+: Accessibility / Notification-access toggles may be greyed out until "Allow restricted settings" |
| Unused-app permission reset / hibernation | Not applicable | API 30+: exempt AutoHost (Setup item) or permissions and background activity can be removed |
| Implicit broadcasts | Since API 26 most cannot be manifest-registered | Same — the EventHub registers receivers dynamically inside long-lived system-bound services (§11.8) |
| Advanced Protection | Not applicable | Android 16+ only (API-guarded detection) |
| Raw Android bridge | Public and greylisted APIs only (hidden-API restrictions apply since Android 9); system-level work needs T3 | Same |
| WebView | Per-run data-directory suffix (API 28+) gives per-profile isolation; WebView version varies by device | Same |

**Fallback-chain rule:** version-dependent capabilities are ordered provider chains (e.g. `device.screenshot`: Accessibility ≥30 → MediaProjection → Shell → global-action). The CompatReport shows the active link and why better ones are unavailable.
**Verification status:** each provider/path records the devices it was run on (`VERIFIED_ON`) in `docs/DEVICES.md`; paths exercised only on emulators (for example API 30+ wireless pairing) are labelled **UNVERIFIED-on-hardware** in the docs and in the CompatReport until someone runs them on a real device.

---

## 4. Design pillars → engineering rules
1. **Breadth with graceful degradation.** Many capabilities, each reporting `AVAILABLE`, `NEEDS_SETUP(action)` or `UNSUPPORTED(reason)`; never a silent failure.
2. **Interchangeable providers.** One capability id can have several providers (e.g. `device.ui` via Accessibility or via shell); the broker picks by availability and task preference.
3. **Compute where the data lives.** Selector matching runs in Kotlin next to the live UI tree; scripts send selectors and receive small handles/summaries, not whole trees.
4. **Verify, fail closed.** Actions can carry an expected outcome; unknown or ambiguous state is an error or a user hand-off, never a guess.
5. **Visible and stoppable.** Notification + floating bubble + global Stop for any run acting on the UI.
6. **Agent-friendly authoring.** Small, consistent, discoverable API; generated stubs/docs; actionable error messages; a fast push-run-read-logs loop (§13).
7. **Host knows nothing about specific apps or services.**
8. **Everything is a run.** Every execution has status, log, evidence and audit trail.
9. **Thin slices first.** Prove the "script does real work on the phone" thesis in Phase 1, then widen.
10. **Harden the perception layer.** Accessibility reads can wedge, trees can be cyclic or huge, nodes go stale. Reads are bounded, guarded and degradable (§5.7).
11. **Everything passes an interceptor chain.** Auth, grants, scope, insecure-feature gate, rate limit, recorder, audit — as composable interceptors (§5.4).
12. **Flows for the simple cases, scripts for the rest.** A 10-line declarative flow should not need a Python process (§7.9).
13. **Drive the phone from your agent.** A Dev API, MCP server and inspector make the authoring loop interactive (§5.6, §13.3).

---

## 5. Architecture

### 5.1 Overview
```
┌──────────────────────────── MAIN PROCESS ────────────────────────────────┐
│ Compose UI: home · library · task detail · runs · schedules · triggers ·   │
│             setup/health · settings · inspector · recorder                 │
│ Surfaces: notification · floating bubble · QS tiles · shortcuts · widget   │
│                                                                            │
│ Engine: RunOrchestrator · TriggerHub · Scheduler(+reconcile) · Locks       │
│         CheckpointStore · Retention · TaskRepository · Profile/Secret store│
│ Runtimes in-process: FlowRuntime (declarative flows, no worker process)    │
│                                                                            │
│ DEV API (JSON-RPC over HTTP/WS + ContentProvider, token)  ──► MCP bridge   │
│                                                                            │
│ CAPABILITY BROKER — interceptor chain:                                     │
│   auth(runToken) → grants → scope → insecure-feature gate → rate limit     │
│   → recorder → audit → provider                                            │
│                                                                            │
│ Providers (CapabilitySpec + availability()):                               │
│   T0 net · files · notify · intent · audio · events · data · ime · screen  │
│   T1 A11yProvider (service · ReadGate · SelectorEngine · overlays ·        │
│                    AutoAcceptGate)                                         │
│   T3 ShellProvider (ADB-loopback/Shizuku/root) · ShellUiProvider           │
│   T4 Vision · Browser(WebView) · …    plugin proxies (external APKs)       │
└───────────▲────────────────────────────────────────────────────────────────┘
            │ AIDL (JSON envelope, run-token auth, big payloads via files)
┌───────────┴────────────── WORKER PROCESS :wN  (Python runs only) ──────────┐
│ RuntimeProvider (Python/Chaquopy) → `autohost` SDK → task code              │
│ T2 raw Android bridge only if `android.api.direct` was granted              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Processes and lifecycle
- The **main process owns all state and all providers.** Workers are disposable. **Worker slots are adaptive:** 2 on low-RAM devices (≤3 GB or `isLowRamDevice`), up to 4 otherwise (`:w0…:w3` declared in the manifest), plus 1 warm **UI-handler worker** (for task-UI callbacks, 30 s cap, never blocks long runs).
- **Worker recycling:** a worker process exits after its run, so no module/global state leaks between runs.
- **Flow runs need no worker process.** The declarative `FlowRuntime` (§7.9) executes inside the main process as a coroutine against the same broker interface, so the simple automations cost no Python interpreter — the cheapest option on low-RAM phones.
- **Low-memory policy** (≤3 GB RAM or `isLowRamDevice`): at most 2 worker slots; the UI-handler worker starts on demand and exits when idle instead of staying warm; UI-tree dumps and evidence images are size-capped/downscaled; `onTrimMemory` releases caches; the main process stays foreground while any run or armed schedule needs it. Record measured memory per worker in `docs/DEVICES.md`.
- Start sequence: engine allocates a slot → creates the `Run` row (`QUEUED → STARTING`) → mints a **runToken** bound to `{runId, taskId, profileId, grants, expiry}` → binds the worker service → sends `RunSpec` (task path, entry, config snapshot, non-secret profile snapshot, checkpoint, attempt, trigger payload).
- Worker heartbeats ≤2 s; >10 s without a heartbeat or process death ⇒ `INTERRUPTED` (never assumed success).
- A **run foreground service** runs while ≥1 run is active (type `specialUse` + subtype property on API 34+, no type on API 29–33 — §3.5); its notification shows task/profile/stage with **Stop**. A separate `ProjectionService` (type `mediaProjection`) exists only for the screenshot path.
- **Global kill switch:** notification action, in-app button, floating bubble: cancels all runs, kills workers, revokes tokens, closes prompts.
- A pure in-process transport implements the same broker interface for unit tests and the PC simulator.

### 5.3 Core Kotlin interfaces (sketch — refine, keep the shape)
```kotlin
data class CapabilitySpec(
    val id: String,                           // "device.ui"
    val summary: String,
    val permissions: List<PermissionSpec>,    // e.g. device.ui.observe, device.ui.act (+ scope schema)
    val methods: List<MethodSpec>,            // name, JSON-schema params/returns, error codes, requires, timeoutMs, risk
    val androidAccess: List<AndroidAccess>,   // runtime perms / special accesses, requested lazily
    val minSdk: Int = 26,
)
sealed interface Availability {
    object Available : Availability
    data class NeedsSetup(val reason: ReasonCode, val action: SetupAction) : Availability
    data class Unsupported(val reason: ReasonCode) : Availability
}
interface CapabilityProvider {
    val spec: CapabilitySpec
    val tier: Tier
    fun availability(env: DeviceEnv): Availability
    suspend fun invoke(call: CallContext, method: String, args: JsonObject): JsonElement
}
interface RuntimeProvider { val id: String; fun start(spec: RunSpec, broker: BrokerHandle): RunHandle }
interface TriggerSource   { val type: String; fun arm(rule: TriggerRule, sink: TriggerSink): Disposable }
```

### 5.4 Broker protocol
```aidl
interface ICapabilityBroker {                       // worker → main
    String call(String runToken, String capability, String method, String argsJson);
    void   emit(String runToken, String eventJson); // logs, progress, stage events
    String describe(String runToken);               // granted caps + live availability
}
interface ITaskWorker {                             // main → worker
    void start(String runSpecJson);
    void requestPause(); void requestResume(); void requestCancel();
    String ping();
}
```
- Envelope: `{"ok":true,"result":…}` or `{"ok":false,"error":{"code":…,"message":…,"details":{…}}}`.
- Error codes: `PERMISSION_DENIED`, `SCOPE_VIOLATION`, `CAPABILITY_UNAVAILABLE` (with reason code), `NEEDS_SETUP`, `TIMEOUT`, `NOT_FOUND`, `STALE_NODE`, `SCREENSHOT_BLOCKED`, `INVALID_ARGS`, `RATE_LIMITED`, `CANCELLED`, `INTERNAL`.
- Payloads over ~100 KB (screenshots, large dumps) are written to the run's evidence dir and returned as a path/handle (Binder's transaction limit is ~1 MB).
- Calls block from the script's view, each with a timeout and a per-run budget.
- **Provider selection:** several providers may implement one capability id; broker chooses by availability, then task hint (`autohost.prefer("shell")`), then tier order. Version-dependent variants are separate providers in an ordered **fallback chain** (e.g. `device.screenshot`: Accessibility on API 30+ → MediaProjection → Shell → global-action).
- **Interceptor chain** (OkHttp-style, ordered, each can short-circuit): `AuthInterceptor` (runToken) → `GrantInterceptor` → `ScopeInterceptor` → `InsecureFeatureInterceptor` (§14.5) → `RateLimitInterceptor` → `RecorderInterceptor` (records actions + pre/post snapshots for the recorder and for run export) → `AuditInterceptor` → provider. Extra interceptors (slow-motion for debugging, tracing, fault injection in tests) are registered by the app, never by tasks.
- **UiAutomation exclusivity:** a provider built on the UiAutomation connection (instrumentation-style) suppresses ordinary accessibility services. `A11yProvider` and `UiAutomationProvider` are therefore **mutually exclusive**; the broker never activates both, and the active choice is shown in the CompatReport.

### 5.5 Generated artifacts (single source of truth)
A Gradle task `generateSdkArtifacts` reads every `CapabilitySpec` and produces: `capabilities.json` (machine-readable catalog), Python `.pyi` stubs plus thin generated call wrappers, `docs/CAPABILITIES.md`, the in-app catalog screen data, and the **agent context pack** (§13.3). A test fails if specs and generated output drift.

### 5.6 Dev API, MCP bridge and inspector (the interactive authoring surface)
A token-authenticated **JSON-RPC 2.0** endpoint hosted by the app (enable in Settings → Developer; off by default):
- **Transports:** (1) HTTP + WebSocket on a local port (default 8080/8081) reached with `adb forward` or LAN; (2) a ContentProvider adapter for `adb shell content …` one-liners; (3) later, a **reverse WebSocket** where the phone dials out to the PC (no adb forward needed). Token from the app or `adb shell content query --uri content://<pkg>/auth_token`.
- **Method groups:** `device.*` (the same capability methods the SDK uses — state, `ui.snapshot`, `ui.find`, `ui.click`, `app.launch`, screenshot…), `tasks.*` (list, install, remove, config), `runs.*` (start, status, tail, cancel, export), `health.*` (doctor), `triggers.*` (catalog, status, rules CRUD, test), `events.subscribe`. Responses use one envelope: `{status:"success"|"error", result|error}`.
- Calls through the Dev API go through the **same broker interceptors** under a synthetic "developer" run token whose grants the user sets in Settings; high-risk capabilities stay behind the insecure-feature switches.
- **CLI + MCP:** `autohost` (PC, Python) wraps the Dev API: `push`, `run`, `logs -f`, `ui dump`, `ui try <selector>`, `pull-run`, `doctor`, `enable-shell`, `grant-secure-settings`, `docs --pack`, `mcp`, `ui` (web inspector, later). `autohost mcp` exposes MCP tools — `autohost_get_device_state`, `autohost_dump_ui`, `autohost_try_selector`, `autohost_screenshot`, `autohost_push_task`, `autohost_run_task`, `autohost_get_run`, `autohost_tail_logs`, `autohost_export_run`, `autohost_diagnose` — so a coding agent can look at the phone, test a selector, write a task, run it and read the failure bundle in one loop.
- **Inspector:** on-device overlay (Phase 7) draws node bounds, ids and indices and copies a selector on tap; a PC web inspector (`autohost ui`) is a later convenience.

### 5.7 Perception hardening (T1 internals every agent must implement)
- **ReadGate:** every accessibility read runs on a small bounded daemon pool, **single-flight per key**, with a hard time budget (default 2.5 s). On timeout or pool saturation it returns the **last good snapshot** marked `degraded=true, degradedReason, snapshotAgeMs` instead of blocking; a wedged read is not cancellable, so its worker is sacrificed and workers are reclaimed when idle.
- **TraversalGuard:** depth cap 128, cycle detection (identity set along the active path), every node getter wrapped for `RuntimeException`, nodes recycled on every path; a child that references an ancestor is skipped.
- **RootResolver:** active-window root **plus** higher-layer application windows (popups, dialogs), de-duplicated by window id; fall back to the active root only when the window list cannot be read. Distinguish `NO_ACTIVE_WINDOW` (a real freeze) from `EMPTY_TREE` (a window that exposes no semantic nodes — Flutter, games, WebView surfaces — which is **not an error**).
- **ForegroundResolver:** the foreground app is confirmed from window-state / windows-changed events *and* the root package of the selected window; event metadata is only a fallback for the exact same window. Emits `APP_ENTERED` / `APP_EXITED`.
- **PopupGuard:** before an action, check for a higher-layer window above the target (system dialog, permission prompt, ANR) and raise `OBSCURED_BY_WINDOW` (or apply the task's `dismiss_known_dialogs` policy) instead of tapping blindly.
- **Settle:** after every action, optionally wait until consecutive snapshots are equal or `settle_timeout` elapses (`wait_for_settle`); optional `retry_if_no_change` repeats a tap once when nothing changed.
- **AutoAcceptGate:** a rate-limited, cooldown-protected helper that clicks known system dialogs *only while a host-initiated flow is pending* (MediaProjection consent, package-installer confirmation). Dialog variants (single-step, spinner/dropdown, inline radio) are matched by view ids and localized label lists; failures back off for 60 s.
- All decision logic above lives in **pure `*Policy` classes** with exhaustive JVM tests; API-specific calls live in tiny `*Api30`-style objects.

---

## 6. Capability catalog — the "ultimate tool"

### 6.1 Catalog
Waves: **W1** = MVP, **W2** = v1, **W3** = broad extras, **W4** = power/optional.

| Capability | SDK | Scripts can… | Tier / Android access | Limits and notes | Wave |
|---|---|---|---|---|---|
| `device.ui.observe` | `device.ui` | Snapshot, find/find_all by selector, wait_for, UI/app events, compact tree dump | T1 (or T3 `uiautomator`) | Hard-scoped to granted packages; games/canvas/Flutter may expose no nodes | W1 |
| `device.ui.act` | `device.ui` | Click/long-click/double-tap, set text, scroll_until, swipe/tap(x,y), global actions (back/home/recents/notifications/quick settings/lock/screenshot), hide keyboard | T1 (or T3 `input`) | Coordinate actions are flagged `COORDINATE`; cannot unlock a secure keyguard | W1 |
| `device.screenshot` | `device.ui.screenshot` | Full/region capture into the run's evidence dir | Chain: T1 `takeScreenshot` (API 30+) → MediaProjection (API 29+, one-time consent) → T3 `screencap` | `SCREENSHOT_BLOCKED` on secure windows; `PROJECTION_NOT_GRANTED`; best-effort on Android 10 | W1 |
| `device.ui.overlay` | `ui.overlay` | Floating panels, bubbles, highlight boxes | T1 accessibility overlay (no extra permission) or Display-over-apps | OEM quirks | W2 |
| `device.app.query` | `device.app` | Installed?, version, list, foreground package, wait_foreground | T0 (+ T1 events, optional Usage access) | Visibility filtering applies only from API 30 (declare `QUERY_ALL_PACKAGES` for sideload); all packages are visible on API 29 | W1 |
| `device.app.launch` | `device.app` | Launch by package/activity/deep link; verifies foreground | T0 (context per S2) or T3 | Background-start limits (§3.2) | W1 |
| `device.app.stop` | `device.app` | Stop an app via strategy chain; result `VERIFIED / BEST_EFFORT / FAILED` | T3 → T1 (App-info UI) → T0 | Verified only with T3 | W1 best-effort, W4 verified |
| `device.app.manage` | `device.app` | Install/uninstall APK, clear data, grant/revoke permissions, enable/disable | T3 | DANGEROUS | W4 |
| `device.intent` | `device.intent` | Start activity/service/broadcast intents; open URLs and Settings pages | T0 | Allowlist of actions/packages in the grant | W2 |
| `device.screen` | `device.screen` | is_on, is_unlocked, wake (best-effort), request keyguard dismiss (non-secure), lock, brightness | T0 / T1 / T3 | Never bypasses a secure lock | W1 read, W2 control |
| `device.audio` | `device.audio` | Volume, ringer, DND, TTS, play sound, vibrate, flashlight | T0 | DND needs policy access | W2 |
| `device.power` | `device.power` | Task-scoped wake lock, battery state, keep-awake | T0 | | W2 |
| `device.settings` | `device.settings` | Read system settings; write via Write-settings or secure settings | T0 / T3 | Write is gated | W2 read, W4 write |
| `device.radio` | `device.radio` | Wi-Fi/Bluetooth/airplane/mobile-data toggles | T3 | Wi-Fi toggling is blocked for normal apps since API 29; Bluetooth toggling works only up to API 32; airplane/mobile data need T3 | W4 |
| `device.notifications` | `device.notifications` | List, wait_for/match, read text, press action buttons, reply, dismiss | T0 NotificationListener | User must enable; filtered to granted packages | W2 |
| `device.events` | `events` | Subscribe to battery/charging/network/screen/boot/headset/app-foreground events | T0 (+T1) | Minimal payloads unless more is granted | W2 |
| `device.clipboard` | `device.clipboard` | get/set | T0 | Background read limits | W2 |
| `device.sms` / `device.telephony` | `device.sms` | Receive/read/send SMS; call state; place call | T0 | Dangerous; restricted for sideloaded apps; `full` flavor only | W3 |
| `files.private` | `files` | Task-private storage | T0 | | W1 |
| `files.shared` | `files` | Read/write user-picked folders (SAF), MediaStore | T0 | | W2 |
| `files.all` | `files` | Broad shared-storage access | T0: legacy-storage opt-out on API 29, "All files access" on API 30+ | DANGEROUS | W3 |
| `contacts` / `calendar` / `media` | same | Query/insert | T0 | Lazily requested runtime permissions | W3 |
| `store.profile` / `store.task` | `ctx.profile_store`, `ctx.task_store` | Persistent key-value per profile / per task | Host DB | Size caps; non-secret only | W1 |
| `net.http` | `http` | Requests with domain allowlist, size caps, header redaction | T0 | | W1 |
| `net.ws` / `net.socket` | `http.ws` / `net` | WebSocket; TCP/UDP | T0 | | W2 / W3 |
| `net.server` | `server` | Local HTTP endpoint for webhooks → triggers | T0 | Localhost by default; LAN opt-in with token | W2 |
| `net.direct` | — | Let Python libraries open raw sockets | — | Cannot be enforced per domain; documented | W1 |
| `location` | `location` | Last/current location; geofence triggers | T0 | Background location needs an extra grant | W3 |
| `camera` / `microphone` / `sensors` / `bluetooth` / `nfc` | same | Capture, record, read, scan | T0 | Permissions; background limits | W3 |
| `notify.post` | `notify` | Notifications with actions and progress | T0 | Notifications permission | W1 |
| `ui.prompt` | `ui` | `ask`, `toast`, `dialog`, `form` from a running script | T0 | Via activity or notification | W1 ask, W2 rest |
| `vision.*` | `vision` | OCR, template match, QR decode | T4 | Hints, not proof | W3 |
| `browser.*` | `browser` | Automate pages in a visible in-app WebView with real input events; drive the user's real browser through T1; optional CDP via T3 | T0 / T1 / T3 | No stealth features; challenges ⇒ `WAITING_USER` | W3 |
| `device.shell` | `device.shell` | Run allowlisted shell commands | T3 | DANGEROUS; allowlist default empty | W4 |
| `android.api.direct` | `autohost.android` | Direct Android API access from Python (`jclass`, system services) | T2 | DANGEROUS; breaks the sandbox by design | W2 |
| `schedule.self` | `ctx.next_run` | Propose the next run or adjust own schedule within declared bounds | Engine | User-visible as "script-proposed" | W2 |
| `secrets.read` | `ctx.secret` | Read declared secret fields of the active profile | Host | Implicit per profile fields | W1 |
| `state.checkpoint` | `ctx.state`, stages | Persist progress for resume | Host | Size-capped | W1 basic, W2 stages |

### 6.2 Android permissions: declared in the manifest, requested lazily
When a task is granted a capability whose provider needs an Android permission or special access, availability reads `NEEDS_SETUP(RUNTIME_PERMISSION: …)`; the **Fix** button launches a small explanatory request flow. Pre-flight (§11.5) re-checks before every run. Manifests differ per flavor (§3.3).

### 6.3 Capability module template (every new capability follows it)
1. `CapabilitySpec` (methods, error codes, risk level, required Android access)
2. Provider implementation with an honest `availability()`
3. Android-access mapping and setup action for Setup & Health
4. SDK wrapper (generated + hand-written ergonomics where needed)
5. Docs entry and example snippet (auto-generated into the catalog and into the `capabilities-showcase` sample task)
6. Unit/contract tests; instrumented tests if device-touching
7. Redaction rules if it handles sensitive data
8. Update the wave tracker in `docs/PROGRESS.md`

### 6.5 Additions from the reference review
| Capability | SDK | Scripts can… | Tier / Android access | Limits and notes | Wave |
|---|---|---|---|---|---|
| `device.ime` | `device.ime` | Commit text (Unicode, secrets) and clear; read/write clipboard in the background via our keyboard; typed outcome `Verified / Rejected / AcceptedUnverified / CommitOutcomeUnknown / InputSessionChanged` | T0 (our `InputMethodService`) | User must select the keyboard — or we select it once `WRITE_SECURE_SETTINGS` is granted | W1 |
| `device.ui` (settle/guards) | `device.ui` | `wait_for_settle`, `is_window_updating`, `click(retry_if_no_change=True)`, popup guard, relational selectors | T1 (T3) | See §5.7, §7.6 | W1 |
| `events.toast` | `events` | Toast text/package via notification-state events | T1 | Best effort | W2 |
| `tasks.run` | `tasks` | Start another task/profile and await its result (chaining) | Engine | Depth/cycle limits; counts toward locks | W2 |
| `device.display` | `device.display` | Dark mode, orientation, font scale | T3 / Write-settings | `cmd uimode`, `settings put` | W4 |
| `device.logcat` | `device.logcat` | Tail/capture logcat for evidence | T3 / `READ_LOGS` (PC grant) | Dev-grade; off by default | W4 |
| `device.app.clear_state` / `grant_permissions` | `device.app` | `pm clear`, `pm grant/revoke` | T3 | DANGEROUS | W4 |
| `device.location.mock` | `device.location` | Mock location provider | T3 + mock-location app-op | DANGEROUS | W4 |
| `device.sensors` | `device.sensors` | Accelerometer, gyro, light, … | T0 | Foreground limits | W3 |
| `ui.console` | `ui.console` | Floating log console (like a floating terminal) | T1 overlay | Respect "visible and stoppable" | W2 |
| `net.reverse` | `server` | Device-initiated WebSocket to a host (control/reporting) | T0 | Off by default; token + TLS | W3 |

---

## 7. Script runtime and SDK

### 7.1 Python via Chaquopy (ADR)
- Chaquopy is a Gradle plugin embedding CPython in-process, with a Java bridge. Current releases are open-source (license restrictions were removed in v12), each version bundles one Python version (3.10–3.14 in v17; **use 3.13+** for best compatibility with 16 KB-page devices), and `pip` installs happen at **build time**, binary wheels only (pure-Python wheels plus Chaquopy's native-wheel repository).
- **ABIs and old devices:** many Android 10 phones are 32-bit or low-RAM. Use the ABI list recorded in `DEVICES.md`; include every ABI the phone needs (plus x86_64 for emulators) in debug builds, check the chosen Chaquopy version's supported ABIs, and keep release size in check with per-ABI splits.
- **Fallback** if Spike S1 fails: another embedded CPython (BeeWare / python-for-android style, or a Termux-style standalone interpreter) behind the same `RuntimeProvider`.

### 7.2 Worker processes and cancellation
- One OS process per active run (§5.2) gives crash isolation, clean memory, and **guaranteed cancel**: cooperative first (next SDK call raises `Cancelled`), then a 5 s grace period, then process kill.
- If S1 shows multi-process Chaquopy is unreliable: fall back to one worker process with cooperative cancel plus async-exception injection, and document the loss of hard cancel.

### 7.3 SDK design rules (scripts are often written by AI agents)
- Few, consistent verbs and predictable names (`device.ui.click`, `device.app.launch`). One obvious way to do each thing.
- Synchronous from the script's view; every call has a default timeout; no infinite waits by default.
- Errors are exceptions with **actionable messages**: what failed, why, how to fix it (e.g. `CapabilityUnavailable: device.ui.act needs the Accessibility service — Setup → Accessibility`).
- Generated `.pyi` stubs with docstrings and examples; `autohost.capabilities()` lists what is available **right now**; `autohost.API_LEVEL` exposes the SDK/host level.
- Results are JSON-friendly. Compact UI dumps for debugging and for LLM-driven scripts: `device.ui.dump(compact=True)`.

### 7.4 SDK surface (sketch)
```text
autohost.ctx       config · profile · run · trigger · state · profile_store · task_store
                   secret(name) · result(code, data) · progress(frac, msg) · now() · sleep(s)  [cancellable]
                   next_run(after_minutes=…)                                    [adaptive scheduling]
autohost.log       info / warn / error(msg, **data)                             [auto-redacted]
autohost.device    info() · capabilities() · screen · app · ui · notifications · events
                   clipboard · intent · audio · power · settings · shell
autohost.ui        ask · toast · dialog · form · overlay
autohost.notify    post(title, text, actions=…)
autohost.http      get / post / request · ws · server
autohost.files     private · shared
autohost.browser   open() → Page                                                [later]
autohost.vision    ocr · match · qr                                             [later]
autohost.android   raw Android bridge                                           [gated]
autohost.flow      OPTIONAL toolkit: Screen · expect · transition · retry · stages
autohost.errors    NeedsUser · Retryable · Fatal · CapabilityUnavailable · PermissionDenied
                   Cancelled · Timeout · NotFound · StaleNode
```

### 7.5 Three example scripts (the shape the SDK must make trivial)
```python
# A) Recurring app interaction (schedule + profiles supply the "recurring" and "per account")
from autohost import ctx, device, sel, log

def main():
    pkg   = ctx.config["target_package"]
    label = ctx.config["button_label"]                       # e.g. "Continue"
    device.app.launch(pkg)
    device.app.wait_foreground(pkg, timeout=20)
    btn = device.ui.wait_for(sel.text(label, *ctx.config.get("label_variants", [])),
                             timeout=ctx.config["wait_seconds"])
    if btn is None:
        log.info("button not present; nothing to do")
        return ctx.result("nothing_to_do")
    device.ui.click(btn)
    ctx.profile_store["last_pressed_at"] = ctx.now()
    return ctx.result("pressed")
```
```python
# B) Background utility (no UI lock; runs with the screen off)
from autohost import ctx, http, notify

def main():
    r = http.get(ctx.config["url"], timeout=10)
    if r.status != 200:
        notify.post("Service check failed", f"HTTP {r.status}")
        ctx.next_run(after_minutes=5)       # adaptive reschedule within the task's allowed bounds
        return ctx.result("failed")
    return ctx.result("ok")
```
```python
# C) Event reaction (trigger: notification posted by a granted package)
import re
from autohost import ctx, device, notify

def main():
    n = ctx.trigger.payload                  # {"package","title","text","actions",...}
    m = re.search(r"\b(\d{6})\b", n["text"])
    if m:
        device.clipboard.set(m.group(1))
        notify.post("Code copied", "Paste it where needed")
```

### 7.6 UI-automation robustness (built into the host, not left to each script)
- **Selector engine runs in Kotlin** beside the live tree. Selectors: `sel.text(*labels, exact=False, regex=False)`, `sel.id(...)`, `sel.desc(...)`, `sel.cls(...)`, state filters (`clickable`, `enabled`, `checked`…), combinators (`&`, `|`, `.within(parent)`, `.nth(i)`).
- **Handles, not trees:** `find` returns opaque `NodeRef`s (run-scoped, short TTL). Actions re-resolve the node and fail closed with `STALE_NODE` if it changed; never click stale bounds.
- **Text matching** is normalized (casefold, NFKC, typographic quotes → ASCII, whitespace collapse) and accepts multiple labels for localized apps. Prefer `id`/`desc` selectors where available; `device.info()` exposes the locale.
- **Waits are event-driven** (content-change events) with backoff polling fallback; fixed sleeps are never the only check. `wait_for` returns a node or `None`; `expect(...)` raises.
- **Verification:** `device.ui.click(target, expect=<selector or Screen>, timeout=…)` makes "click succeeded" mean "the expected result appeared".
- **`autohost.flow` (optional toolkit):** `Screen` signatures (`all_text`, `any_text`, `none_text`, `ids`, `desc`, `package`), `classify([...])` → name or `UNKNOWN` (ambiguous matches ⇒ `UNKNOWN`), `transition(action, expect, timeout)`, `retry(policy)`, and `stages`. Simple scripts never need it.
- **Coordinates and vision:** allowed only when the grant says so; results carry `COORDINATE`/`VISION` and never count as sole proof of success unless the script opts in (logged).
- **Selector matching modes:** `text`, `desc`, `id`, `class`, `package` each support `eq`, `contains`, `starts_with`, `ends_with`, `regex` (case-insensitive by default; `exact=True` for strict). Numeric/state filters: `bounds` (inside/overlaps), `depth`, `drawing_order`, `index_in_parent`, `clickable/enabled/checked/selected/focused/editable/scrollable/visible`. **Relational filters:** `below`, `above`, `left_of`, `right_of`, `child_of`, `contains_child`, `contains_descendants`, `index`. Ambiguity rule: return the **deepest** matching node; `find_all` returns document order; search algorithm DFS (default) or BFS.
- **Click strategy chain** (`click(target, strategy="auto")`): node `ACTION_CLICK` → nearest clickable ancestor → gesture at the node's centre (result flagged `COORDINATE`). Every step re-resolves the node; stale ⇒ `STALE_NODE`.
- **Text input chain** (`set_text(target, text, secret=False)`): `ACTION_SET_TEXT` → AutoHost IME commit (typed `TextInputResult`) → paste via clipboard → T3 `input text`. The first strategy whose result is `Verified` wins; unverifiable results are reported, never assumed.
- **Compact UI dump** (`device.ui.dump(compact=True)`) — the canonical text form for logs, agents and LLM-driven scripts:
  ```
  App: <label> (<package>)  Activity: <name>  Keyboard: visible|hidden  Focused: '<text>'
  idx. ShortClass: "resource-id" "text/desc" [clickable,checked,editable,…] (l,t,r,b)
    2.1 …            # indentation = depth; indices are stable within one snapshot
  ```
  Filters: on-screen only, min size 5 px, ≥1 % visible (all configurable; `filter=false` returns everything). Password fields never include text. Optional normalized coordinates `[0..1000]`.

### 7.7 Dependencies (honest model)
1. **Bundled set** declared in Gradle per flavor. `core`: small pure-Python essentials (e.g. `requests`, `beautifulsoup4`, `pyyaml`, `python-dateutil`, `websocket-client`). `full`: adds heavier native packages Chaquopy provides (e.g. `numpy`, `pillow`, `opencv-python-headless`, `lxml`, `cryptography` — **verify current availability and APK-size impact**). Adding a library means rebuilding the host; that is a *capability expansion that benefits every task*, consistent with the vision.
2. **Vendored pure-Python** packages inside a task's `libs/` work with no rebuild (added to `sys.path`).
3. **Native wheels at runtime** are not supported in v1 (stretch spike only).
4. **Import-time static check** (§15) classifies each import as stdlib / bundled / vendored / unavailable / known-desktop-only, with suggestions.
5. **Import guard:** `java`, `jnius`-style modules and `ctypes` are blocked for task code unless `android.api.direct` is granted (§14).

### 7.8 Other languages
`RuntimeProvider` is the seam. Python ships first; **Phase 6 adds the declarative Flow runtime** (in-process, §7.9); Phase 11 adds JavaScript (QuickJS) as a third runtime over the same broker, proving extensibility.

### 7.9 Declarative flows (the built-in no-script runtime)
`runtime.type = "flow"` tasks ship `flow.yaml` (or JSON) instead of `main.py`. A small Kotlin interpreter (`:runtime:flow`) runs it **in the main process** through the same broker, so grants, scopes, profiles, schedules, logging, evidence, pause/cancel and recovery all behave exactly as for Python tasks.
- **Commands (v1):** `launchApp {package, clearState?}`, `stopApp`, `openLink`, `tapOn`, `longPressOn`, `inputText` (`secret: true` supported), `eraseText`, `pressKey` (back/home/enter/…), `scroll`, `scrollUntilVisible`, `swipe`, `hideKeyboard`, `waitFor` / `waitUntil {visible|notVisible, timeout}`, `waitForAnimationToEnd`, `assertVisible` / `assertNotVisible`, `copyTextFrom`, `setClipboard`, `takeScreenshot`, `notify`, `runFlow {file, when, env}`, `repeat {times | while}`, `retry {times, commands}`, `log`, `setResult`, `nextRun {afterMinutes}`, `ask` (user prompt), `defineVariables`.
- **Command modifiers:** `optional: true`, `label`, `timeout`, `when: {visible|notVisible|true|platformApi}`, `retryTapIfNoChange`, `waitToSettle`.
- **Selectors:** the full §7.6 selector set (text/id regex, relational, index, state) in YAML form.
- **Variables:** `${config.x}`, `${profile.x}`, `${secret.NAME}` (resolved only at the action; never logged), `${env.X}`, `${run.attempt}`; a small side-effect-free expression language (comparisons, `&&`, `||`, string concat). No arbitrary scripting in v1 (a QuickJS expression hook may come with the JS runtime).
- **Hooks:** `onFlowStart`, `onFlowComplete`; **tags** for grouping.
- **Errors:** every command failure is typed (`retryable` vs terminal) and carries the failing selector, a compact UI dump and evidence.
- **Recorder (Phase 6):** the user performs the task once with the **recorder overlay** on; accessibility events become flow steps. Selector choice ranks by stability: resource-id > content-desc > unique text > relational > bounds (coordinate steps are flagged and need confirmation). Each step stores a `pre_state` snapshot (node semantic keys + package/activity) and elapsed time. **Guarded replay:** before a step, compare the current state with `pre_state` (node-set Jaccard weighted 0.85 + package/activity 0.15, threshold 0.85, poll up to `state_timeout`); on mismatch apply `on_mismatch: stop | ask_user | continue` (default `stop` with evidence).
- The recorder emits flows, never opaque binaries; flows are plain text and diffable.

### 7.10 App packs (later, Phase 11)
Reusable per-app packs (`.aapack`): named selectors, named `Screen` signatures, locale label variants and tips for one package, referenced from tasks (`uses_packs`). They keep brittle app knowledge out of tasks and let a fix ship once for every task.

---

## 8. Task packages

### 8.1 Layout (`.aatask` = zip)
```
mytask.aatask
├─ task.json        # manifest (required)
├─ main.py          # entrypoint (manifest.runtime.entry)
├─ lib/ …           # optional task modules
├─ libs/ …          # optional vendored PURE-PYTHON packages (on sys.path)
├─ ui/main.json     # optional declarative UI   or   ui/index.html (+assets) for HTML UI
├─ assets/ …        # templates, images, fixtures
└─ README.md        # shown on the task detail screen
```

### 8.2 Manifest (schema v1, example)
```jsonc
{
  "schema": 1,
  "id": "com.example.recurring-press",          // reverse-DNS, unique, immutable
  "name": "Recurring Button Press",
  "version": "1.0.0",
  "description": "Opens an app and presses a labelled button.",
  "host_api": ">=1 <2",
  "runtime": {"type": "python", "entry": "main.py"},

  "needs": {
    "capabilities": [                            // hard requirements
      {"id": "device.app.launch", "scope": {"packages": ["${config.target_package}"]}},
      {"id": "device.ui.observe", "scope": {"packages": ["${config.target_package}"]}},
      {"id": "device.ui.act",     "scope": {"packages": ["${config.target_package}"]}}
    ],
    "optional": [
      {"id": "device.screenshot", "scope": {"packages": ["${config.target_package}"]}},
      {"id": "notify.post"}
    ],
    "apps": [{"package": "${config.target_package}"}]     // missing ⇒ "Needs install" state (+ store link)
  },

  "config_schema": {                              // task-wide settings; JSON-Schema subset + x-ui hints
    "type": "object",
    "required": ["target_package", "button_label"],
    "properties": {
      "target_package": {"type": "string", "title": "App", "x-ui": {"widget": "app-picker"}},
      "button_label":   {"type": "string", "title": "Button text", "default": "Continue"},
      "wait_seconds":   {"type": "integer", "default": 20}
    }
  },
  "profile_schema": {                             // per-profile values; secrets flagged
    "type": "object",
    "properties": {
      "label":    {"type": "string", "title": "Profile name"},
      "username": {"type": "string", "title": "Account"},
      "password": {"type": "string", "title": "Password", "x-secret": true},
      "target":   {"type": "object", "title": "Instance", "x-ui": {"widget": "instance-target"}}
    }
  },

  "triggers": {"manual": true,
               "schedule": {"allowed": true, "default": {"type": "interval", "every_minutes": 30}},
               "events": []},
  "execution": {"ui_exclusive": true, "needs_unlocked_screen": true, "pausable": true,
                "timeout_minutes": 10, "max_attempts": 3},
  "ui": {"mode": "auto"}                         // "auto" | declarative | html
}
```
- `${config.x}` / `${profile.x}` inside scopes are **resolved at grant time and bound to the resolved value**; changing the value re-prompts.
- Import produces a structured **ImportReport** (errors, warnings, compat results). Invalid ⇒ not installed.
- **Integrity:** SHA-256 of the bundle is stored and shown; bytes under an installed version are immutable. Optional later: author signatures + trusted-author list.

### 8.3 Import sources (one pipeline)
File picker / "Open with AutoHost"; share sheet; HTTPS URL (explicit action, hash shown); **dev folder** `/sdcard/Android/data/app.autohost/files/dev/<taskid>/` (the app-specific external directory: `adb push` works on every Android version with no storage permission; hot-reloads on next run; primary loop for authors and agents; a user-picked Documents folder via SAF is optional); built-in samples; **New from template** (copies a sample into the dev folder).
In dev mode, editing a manifest produces a **permission diff** for one-tap approval instead of a full re-review.

### 8.4 Versions and removal
Multiple versions may be installed. Additive config-schema changes migrate automatically; breaking ones prompt. Remove: confirm; choose keep-history or delete-everything; revoke grants; cancel schedules; delete bundle files.

### 8.5 Manifest additions in v3
- `runtime.type` ∈ `python` | `flow` (later `javascript`); `runtime.entry` is `main.py` or `flow.yaml`.
- `execution.idle_timeout_seconds` (default 300): a run that makes **no broker call and emits no event** for this long is warned, then cancelled (`FAILED:IDLE_TIMEOUT`). `execution.verification` ∈ `off | final | checkpoints | strict` controls how strictly UI actions must carry an `expect=` (strict: every action).
- `triggers.events[]` entries use the trigger-rule model in §11.9.
- `uses_packs[]` references app packs (§7.10).
- **Schema versioning and migrations:** `schema` is an integer; the host ships a chain of pure migration functions (`v1→v2…`) applied on import and on host upgrade; a manifest newer than the host is rejected with `HOST_TOO_OLD`.

---

## 9. Profiles, configuration, secrets

### 9.1 Model
- **Config** = task-wide settings (one set per task). **Profile** = per-account/per-instance values + secrets + persistent store (`ctx.profile_store`) + run history. Cloning a profile never duplicates the script.
- A run is bound to at most one profile. Profiles are archived rather than deleted (history kept; purge optional).
- Secrets: only fields flagged `x-secret`; stored Keystore-wrapped; delivered to the script only via `ctx.secret(name)` (and `set_text(..., secret=True)`); registered with the redactor so they never appear in logs, evidence text or exports.

### 9.2 Multi-account patterns (a task chooses; the host supports all)
| Pattern | How | Trade-offs |
|---|---|---|
| **P1 In-app switcher** | Script drives the app's own account switcher to the profile's account | Fast; only when the app supports it |
| **P2 Log out / log in per run** | Script logs out and in with the profile's credentials, verifying each step | Universal; slowest; 2FA/challenges need `WAITING_USER` |
| **P3 Parallel instances** | Profile `target` = Android user, work profile or cloned package; launch with `--user` (T3) or the clone's package name | Most reliable (stays logged in); needs T3 and a clone/work profile |
| **P4 One device per account** | One AutoHost per device | Out of scope for v1 |

---

## 10. Task UI and system surfaces

### 10.1 Task UI tiers (no new Kotlin per task)
- **Tier A — Auto UI:** forms generated from `config_schema` / `profile_schema` plus a Run button. Widgets: text, number, switch, select, multi-select, interval, time, date, app-picker, profile-picker, file/folder-picker, secret, instance-target.
- **Tier B — Declarative UI** (`ui/main.json`): containers (column/row/card/tabs), `text`, `markdown`, `input`, `toggle`, `select`, `button`, `progress`, `table`, `image`, `logview`, `stat`, `profile-picker`. Bindings `{{config.x}}`, `{{profile.y}}`, `{{run.status}}`, `{{state.z}}`. Actions: `run`, `run_stage(name)`, `call(handler, args)` (Python `@ui.handler("name")`), `navigate`, `confirm`.
- **Tier C — HTML UI** (`ui/index.html`): sandboxed WebView — no network, bundle assets only, strict CSP, JS bridge `autohost.invoke(handler, args)` and `autohost.subscribe(topic)`; only the task's own handlers are callable.
- UI handlers run in the warm UI-handler worker so interactions never block on long runs.

### 10.2 Imperative UI from a running script
`ui.ask(...)`, `ui.form(...)`, `ui.dialog(...)`, `ui.toast(...)`, `ui.overlay(...)` — shown via activity, notification or accessibility overlay as appropriate; results are returned to the script.

### 10.3 System surfaces
| Surface | Use | Mechanism |
|---|---|---|
| Ongoing notification | Status + **Stop/Pause** for every active run | Foreground service notification |
| **Floating control bubble** | Status + Stop visible over other apps (default on whenever a run uses `device.ui.act`) | Accessibility overlay window |
| Quick Settings tiles | A few generic tiles ("AutoHost 1–5") bindable to any task/profile | `TileService` (the user adds the tile from the edit panel; `requestAddTileService` exists only on API 33+) |
| Pinned shortcuts | One-tap launch of a task/profile from the launcher | `ShortcutManager` |
| Home-screen widget | Run button + last status for chosen tasks | Jetpack Glance |
| Script notification actions | `notify.post(actions=[...])` → handler runs | Notification actions |
| External triggers | Let other apps (e.g. Tasker) or other devices start tasks | Exported `RUN_TASK` intent guarded by a per-install token; local webhook server (`net.server`) |

**Additional surfaces (v3):**
| Surface | Use | Mechanism |
|---|---|---|
| **Inspector overlay** | Draw node bounds/ids/indices over any app; tap a node to copy a selector or add it to the recorder | Accessibility overlay + `RootResolver`; scoped to the inspected package |
| **Recorder overlay** | Floating Record / Pause / Stop with a step counter; produces a flow (§7.9) | Accessibility overlay + event capture |
| **Global stop gesture** | Hold Volume-Down (configurable) to cancel all runs | Accessibility key-event filtering; optional shell key-event fallback when T3 is available; off until the user enables it |
| **Tasker/Locale plugin** (optional, W3) | Let Tasker start AutoHost tasks and receive results | Locale plugin intents guarded by the external-trigger token |

---

## 11. Triggers, scheduling, execution conditions

### 11.1 Trigger types
Manual · Schedule · Event (device events, notifications, app foreground) · External (intent/webhook) · Chain (on another task's result) · Surface (tile/shortcut/widget/notification action) · Script-proposed (`ctx.next_run`). All produce the same thing: an enqueued run with `trigger` metadata and optional `payload`.

### 11.2 Schedule rules (JSON, evaluated by a pure-Kotlin `ScheduleEngine` with an injectable clock)
- `interval` (fixed-rate, or after-previous-run-ends + N), `daily`/`weekly` (times, days, time zone), restricted `cron`, `once`, `window` (only between HH:MM–HH:MM on chosen days).
- **Per-profile fan-out:** `profileIds[]` with `mode: sequential`, `staggered(offset)`, or `parallel` (non-UI tasks only).
- **Limits:** `max_total_runs`, `max_runs_per_day`, `end_at`, `stop_after_consecutive_failures`.
- **Conditions:** charging, battery ≥ X, unmetered network, screen awake/unlocked, time window.
- **Misfire policy:** `skip`, `run_once`, `catch_up(max_n)`.

### 11.3 Mechanics
- Persist `nextFireAt` per schedule; arm **one** exact alarm for the earliest due (`setExactAndAllowWhileIdle`) and re-arm after each fire. Permissions by API (§3.5): none on API 29–30, `SCHEDULE_EXACT_ALARM` on 31–32, `USE_EXACT_ALARM` (sideload) or user-granted `SCHEDULE_EXACT_ALARM` on 33+; check `canScheduleExactAlarms()` on 31+. If exact alarms are unavailable: fall back to WorkManager (flex window) and show a persistent **"timing may be late"** badge.
- Alarm receiver → starts the foreground service (allowed from the background on API 29–30; on API 31+ a fired exact alarm is a permitted start path — verify in S3) → `Engine.enqueue`.
- Re-arm on boot, app update, time-zone change and clock change; reconcile on app start.
- **Backends are pluggable** behind `SchedulerBackend`: `AlarmBackend` (exact alarms), `WorkBackend` (WorkManager), `JobBackend` (JobScheduler). Default `auto` prefers Alarm when exact alarms are available, else Work; the user can force one in Settings (OEM behaviour differs). Precise arming is used only for fires inside a near window (default 48 h); farther fires are covered by the reconcile worker.
- **Reconcile worker** (periodic WorkManager job, ~20 min): verifies the next alarm exists, detects missed fires (`nextFireAt < now − grace`), applies the misfire policy (run overdue now / skip / catch up), re-arms, and records `RECONCILED` events. The same reconcile runs at app start, boot, package replace, and time/time-zone change.

### 11.4 Locks and queue
Global **UI lock** (only one UI-acting run at a time), per-package lock, per-profile lock. Queue ordered by priority then due time; configurable `lock_timeout` → `SKIPPED:LOCK_TIMEOUT`.

### 11.5 Pre-flight (at fire time)
Resolve required capabilities and apps; evaluate conditions; apply the screen/unlock policy (§11.6). Outcomes: proceed, wait (bounded), `WAITING_USER(reason)` with a notification, or `SKIPPED(reason)`.

### 11.6 Screen and unlock policy (`needs_unlocked_screen`)
Screen off → best-effort wake (T0: a wake lock with `ACQUIRE_CAUSES_WAKEUP`, or a transparent activity using `setTurnScreenOn`/`setShowWhenLocked` — deprecated flags that still work on API 29; or `input keyevent` via T3). Non-secure keyguard → `requestDismissKeyguard` (API 26+). **Secure lock → cannot proceed:** wait up to a configurable limit, notify the user (`WAITING_USER(unlock)`), or skip. The always-on recipe (§3.4) is the supported way to avoid this for unattended use.

**ScreenGuard** (`:capabilities:screen`): a pure `ScreenGuardPolicy` decides from `(enabled, interactive, deviceLocked, secureLock, lastAttemptAt, now, throttle = 60 s)` one of `NOOP | WAKE_ONLY | RECOVER | DEGRADED(reason)`. While enabled the service holds a steady screen wake lock, re-evaluates every 30 s and on screen-on/off/user-present broadcasts, and for `RECOVER` starts a transparent **recovery activity** (`showWhenLocked`, `turnScreenOn`, `requestDismissKeyguard`) guarded by a generation token and a timeout. Non-secure keyguards are dismissed; secure ones yield `DEGRADED(SECURE_LOCK)` → `WAITING_USER(unlock)`. Every state is surfaced to the user, never silent.

### 11.7 Adaptive scheduling
`ctx.next_run(after_minutes=…)` or `at=…` proposes the next run (e.g. "retry when the limit resets"). The host clamps proposals to the task's declared bounds (`min_interval`, window, limits) and labels them "script-proposed" in the UI.

### 11.8 Event triggers
Sources: notification posted/removed (granted packages/patterns), app entered/exited (T1), battery/charging, network/Wi-Fi, screen on/off/user-present, boot, headset, toast (W2), SMS received (W3), geofence (W3), webhook, intent. **Privacy:** event delivery is scoped to what the grant names; app-foreground events carry only the package name unless more is granted; with no active run and no armed event trigger the accessibility service ignores everything.
**Delivery mechanism (`EventHub`):** most implicit broadcasts cannot be manifest-registered (since API 26), so the hub registers receivers dynamically inside a long-lived system-bound service (the Accessibility or Notification-listener service) when one is enabled, otherwise inside a low-priority foreground service that exists only while event triggers are armed; charging/network conditions can alternatively use WorkManager constraints.

### 11.9 Trigger rules (one data model for events and schedules)
A rule is `{id, task, profile?, source, params, cooldown_seconds, max_launch_count?, debounce_ms?, busy_policy, enabled}`. Schema: `schemas/trigger-rule.schema.json`.
- **Sources / params:** `time.delay {seconds}`, `time.absolute {at}`, `time.daily {time}`, `time.weekly {days,time}`; `notification.posted|removed {package?, title_regex?, text_regex?}`; `app.entered|exited {package}`; `battery.low|okay|level {op,percent}`; `power.connected|disconnected`; `screen.on|off`, `user.present`; `network.connected|type_changed {type}`, `network.wifi_ssid {ssid}`; `sms.received {sender_regex?, body_regex?}` (W3); `toast.shown {package?, text_regex?}` (W2); `external {name}` (intent/webhook); `task.chain {task, on: succeeded|failed|any}`.
- **Guards:** `cooldown_seconds` = minimum gap between launches; `max_launch_count` = lifetime cap; `debounce_ms` coalesces notification bursts into one launch; `busy_policy` ∈ `queue | drop | replace` decides what happens when the target is already running or the UI lock is held (`queue` has a bounded length, oldest dropped first).
- **Payload:** delivered as `ctx.trigger.payload` (and `${trigger.*}` in flows, rendered with escaping); never contains secrets or password-field text.
- **Operations:** strict validation with `TRIGGER_RULE_INVALID(field)`; `triggers.catalog` lists sources and params; `triggers.status` shows enabled, last fire, counts, cooldown remaining and busy-queue depth; `triggers.test` fires a synthetic event. Time rules reuse the `ScheduleEngine` (§11.2).

---

## 12. Run lifecycle, state, recovery

### 12.1 Statuses
`QUEUED → STARTING → RUNNING ⇄ PAUSED`; `RUNNING → WAITING_USER` (CAPTCHA/2FA/unlock/permission lost/needs input); terminal: `SUCCEEDED`, `FAILED`, `CANCELLED`, `INTERRUPTED` (worker died/reboot/battery), `SKIPPED` (conditions unmet/lock timeout/missed).
Each terminal state carries a **reason code** (e.g. `FAILED:EXCEPTION`, `FAILED:STAGE_FAILED(name)`, `INTERRUPTED:WORKER_DIED`, `SKIPPED:SCREEN_LOCKED`, `WAITING_USER:SECURITY_CHALLENGE`). Runs also store a small `result` (`ctx.result(code, data)`) shown in History.

### 12.2 Checkpoints (optional for simple tasks, standard for long ones)
- `ctx.state` — JSON dict persisted atomically by the host after each stage and on `ctx.save()`; size-capped; secrets refused.
- **Stages** (`@flow.stage(name, retries, on_resume)` or `ctx.once(name, fn)`) record `{name, status, attempts, startedAt, endedAt, error}` plus an **in-progress marker**. On resume, a stage found in progress follows `on_resume`: `verify` (task-provided postcondition), `redo`, or `manual` (ask the user).
- Guidance for task authors: write idempotent steps or record "done markers" in `profile_store` so resumed or retried runs never double-act.

### 12.3 Pause, cancel, resume
- **Pause** is cooperative (takes effect at the next SDK call); tasks that can't pause declare `pausable: false`.
- **Cancel:** flag → next SDK call raises `Cancelled` → 5 s grace → process kill → `CANCELLED`; token revoked; prompts closed.
- **Resume** = a new `attempt` of the same run id, loading checkpoint and stage records; **completed stages never repeat.** Auto-resume for `INTERRUPTED` is a bounded, configurable policy. Manual options: Resume / Retry stage / Take over / Abort.
- **`WAITING_USER`:** the run screen and a notification explain what to do; the user taps "Done — continue" (or the script's `ui.ask` returns). Timeouts are configurable.

### 12.4 Crash and boot reconciliation
On app start and after boot: reconcile `STARTING/RUNNING` rows with live workers; orphans become `INTERRUPTED`; the scheduler re-arms.

### 12.5 Idle watchdog and incidents
- **Idle watchdog:** `execution.idle_timeout_seconds` (§8.5). The orchestrator tracks `lastActivityAt` (any broker call or emitted event); at 80 % of the limit it emits a warning event, at 100 % it cancels with `FAILED:IDLE_TIMEOUT`.
- **Incidents:** a failed action opens an *incident* (stage, selector/capability, error code, evidence refs) that stays attached to the run view until a later success in the same stage resolves it or the run ends. History shows unresolved incidents first. `execution.verification` decides whether an unverified action opens an incident.

---

## 13. Observability, setup and the authoring loop

### 13.1 Visibility
- **Structured logs** (`RunEvent`): leveled, stage-tagged, JSON data, live tail, per-run and global caps, redaction applied in the worker and again in main.
- **Timeline** of stages (durations, attempts, errors).
- **Evidence:** screenshots and redacted UI trees (automatic on failure when `evidence_on_fail`, or on demand), stored in the run directory with a sensitivity label and retention policy.
- **Audit trail:** every broker call (capability, method, outcome, duration; arguments redacted).
- **Run export bundle (`.aarun`)**: redacted logs, stage records, checkpoint (secrets stripped), manifest, optional evidence, environment info (device model, API level, host/SDK versions, available tiers).

### 13.2 Setup & Health center (a registry of `HealthCheck`s)
Every row is a `HealthCheck`: `id`, `title`, `required | optional`, `appliesTo(env)`, `diagnose() → ok | issue(reasonCode, params)`, `hasAutofix()`, `fix() → FixOutcome`. Checks filter themselves by API level (§3.5). The UI and `autohost doctor` group results as **Needs fixing now** (required, no autofix) → **Can fix automatically** (e.g. re-enable accessibility or select the AutoHost keyboard via `WRITE_SECURE_SETTINGS`) → **Optional**. CLI exit codes: `0` healthy, `127` major issues.
Checks: Accessibility (enabled, connected, not blocked by Advanced Protection, restricted-settings help), Notifications permission, Notification access, Exact alarms, Battery exemption, OEM autostart guide, Unused-app/hibernation exemption, Display over apps (optional), Usage access (optional), Write settings (optional), AutoHost keyboard (optional), ScreenGuard/keep-awake, Shell tier (ADB-over-TCP / Wireless debugging / Shizuku / root), `WRITE_SECURE_SETTINGS` grant, Projection auto-accept, Dev API status, insecure-feature switches, always-on recipe (§3.4).
On Android 10 the shell-tier and `WRITE_SECURE_SETTINGS` rows show the exact PC commands (`autohost enable-shell`, `autohost grant-secure-settings`) with a copy button, and a notification after each reboot reminds the user when the shell tier needs re-arming. Re-checkable at any time; the scheduler consults the same registry.

### 13.3 Authoring loop (designed for a coding agent)
1. **Start:** New-from-template, or the agent writes `task.json` + `main.py` / `flow.yaml` locally.
2. **Push:** `autohost push <dir>` (Dev API; falls back to `adb push` into the app-specific dev folder).
3. **Look before writing:** `autohost ui dump`, `autohost ui try '<selector>'`, `autohost screenshot` — or the same through MCP tools — to see the real screen and test selectors on the real device.
4. **Run:** from the app or `autohost run <task> [--profile P]`; `autohost logs -f` streams structured logs.
5. **Debug:** on failure `autohost pull-run <id>` (MCP: `autohost_export_run`) gives an `.aarun` bundle: redacted logs, stage records, incidents, compact UI dumps, evidence, environment info.
6. **Record instead of write:** the recorder overlay produces a flow (§7.9); refine it by hand.
7. **Before long runs:** `autohost doctor` (same checks as Setup & Health).
8. **Agent context pack:** `autohost docs --pack` or the in-app button writes a **Skill-format folder** (`SKILL.md` + `references/`): SDK reference, capability catalog with *live availability on this device*, manifest and flow schemas, five examples, common pitfalls, device info (API level, locale, tiers). Generated from the specs (§5.5).
9. **Simulator `autohost-sim`:** runs the *same* SDK source and the flow interpreter against a `FakeDevice` (recorded UI-tree JSON, scripted provider responses, virtual clock) so logic is unit-testable without a phone.
10. **PC helpers (Android 10):** `autohost grant-secure-settings` (one-time `pm grant … WRITE_SECURE_SETTINGS`) and `autohost enable-shell` (`adb tcpip 5555` plus a guide to authorizing the key) — each prints what it did and what to tap on the phone.
11. **Debug build extras:** `adb`-only fallbacks (`am broadcast` / ContentProvider) for installing a task and reading status when the Dev API is off.

---

## 14. Security and privacy (honest model)

### 14.1 What we can promise
- Scripts reach device/service features **only through the broker**, which enforces grants, scopes, limits and writes an audit record.
- UI observation is **hard-scoped at the service level** to granted packages; other packages' nodes are never returned.
- Secrets live only in Keystore-wrapped storage; scripts receive a secret only for declared fields; logs/evidence/exports are scrubbed of registered secret values.
- Automation is visible and stoppable (§10.3). No telemetry or cloud by default.

### 14.2 What we cannot promise
All workers run under the app's own UID. A script that bypasses the SDK (via the `java` bridge, `ctypes`, reading app files) could in principle reach app data. Mitigations: the import guard blocks those routes unless `android.api.direct` is granted; workers hold no DB handle or Keystore keys; secrets are decrypted in the main process and sent per call; tasks are installed only by explicit user action with the bundle hash and a permission review. **Run tasks you trust.** Stronger isolation is future work (plugin APKs with their own UID; `isolatedProcess` workers — feasibility unknown with Chaquopy). `SECURITY.md` and the import UI must say this plainly.

### 14.3 Grants and scopes
- Install flow shows a **Permission Review**: for each capability, plain-language meaning, resolved scope, required/optional, and live availability (Available / Needs setup [Fix] / Unsupported [reason]). Denying an optional capability makes the SDK raise `PermissionDenied` for it.
- Changed capability or widened scope ⇒ re-review (dev mode: diff approval).
- **High-risk capabilities** (`device.shell`, `device.app.manage`, `android.api.direct`, `files.all`, SMS/telephony) require an extra confirmation and are listed prominently on the task detail screen.
- Broker enforces at call time: token valid → capability granted → scope matches (e.g. the foreground package is in `packages`) → provider available → rate limits → call → audit.

### 14.4 Redaction and hardening
Central `Redactor`: registered secret values and common encodings, password-node text, optional email masking, URL token parameters, regex rules; used by logs, evidence writer, exports, audit and SDK `log`. Hardening: `allowBackup=false` (provide explicit export instead), `FLAG_SECURE` on screens showing secrets, no cleartext HTTP by default, minimal exported components (token-guarded `RUN_TASK`, dev bridge in debug only), broker rejects unknown/expired tokens.

### 14.5 Insecure-feature switches (two-level gate)
High-risk capabilities require **both** a per-task grant **and** a global switch in Settings → Developer & security, **off by default**: `shell`, `raw_api`, `app_manage`, `all_files`, `sms`, `telephony`, `logcat`, `mock_location`, `dev_api_remote` (LAN access to the Dev API). A "relaxed mode" toggle enables all except explicit denies; **deny always wins**. Names are `scope:feature` (scope = capability family or `*`). The `InsecureFeatureInterceptor` enforces them; the CompatReport shows "disabled by global setting" with a Fix that opens the switch; every enable is audited and shown on the task detail screen.

### 14.6 Licence hygiene
AGPL/GPL reference projects are clean-room only (`docs/REFERENCES.md`). Reused Apache/MIT code keeps its notices in `THIRD_PARTY_NOTICES.md`. A CI step fails on dependencies with unknown or incompatible licences. The app's own licence is decided in Phase 0 (`DECISIONS.md`).

---

## 15. Compatibility checking

At import and before every run the **CompatReport** lists, per requirement: capabilities (`AVAILABLE` / `NEEDS_SETUP(action)` / `UNSUPPORTED(reason)` / `DENIED`), required apps (installed? version?), API level, Python imports, locale hints for text selectors, and Android permissions that will be requested. Reason codes are enumerated (`A11Y_SERVICE_DISABLED`, `A11Y_BLOCKED_ADVANCED_PROTECTION`, `API_TOO_LOW(30)`, `SHELL_TIER_NOT_PAIRED`, `PC_ASSIST_REQUIRED`, `PROJECTION_NOT_GRANTED`, `EXACT_ALARM_DENIED`, `RESTRICTED_SETTINGS_NOT_ALLOWED`, `APP_NOT_INSTALLED(pkg)`, …).
The report also names the **active provider in each fallback chain** (e.g. screenshots via MediaProjection on API 29) and its verification status (`VERIFIED_ON` device list, or `UNVERIFIED-on-hardware`).
**Static import scan (AST):** stdlib / bundled / vendored / unavailable / *known desktop-only* (`playwright`, `patchright`, `selenium`, `pyautogui`, `pynput`, `tkinter`, `PyQt*`, `win32*`, `subprocess` calls to desktop binaries) with suggestions ("use `device.ui`", "use the `browser` capability"). **No compatibility shims** are provided in v1; scripts are written or rewritten against the SDK. A task with an unmet *required* item cannot start; the UI shows the fix or the reason.

---

## 16. Data model (Room; migrations from day one; exported schema)
| Entity | Key fields |
|---|---|
| `Task` / `TaskVersion` | id, name, activeVersion, source, bundleSha256, enabled · manifestJson, bundlePath, importReportJson |
| `Grant` | taskId, capability, resolved scopeJson, granted, grantedAt |
| `TaskConfig` | taskId, valuesJson, schemaVersion |
| `Profile` | id, taskId, name, valuesJson (non-secret), secretKeys, targetJson, archived |
| `SecretBlob` | profileId, key, ciphertext, iv, keyAlias |
| `KvStore` | scope (task/profile), ownerId, key, valueJson |
| `TriggerDef` | id, taskId, type (schedule/event/external/chain), ruleJson, conditionsJson, limitsJson, misfirePolicy, profileIds, enabled, nextFireAt, counters |
| `Run` | id, taskId, profileId, trigger + payload, status, reasonCode, attempt, startedAt, endedAt, currentStage, progress, checkpointJson, resultJson, workerSlot |
| `StageRecord` | runId, name, status, attempts, startedAt, endedAt, errorJson, onResume |
| `RunEvent` | runId, ts, level, stage, message, dataJson (redacted) |
| `Evidence` | id, runId, stage, type, path, createdAt, expiresAt, sensitivity |
| `AuditRecord` | runId, ts, capability, method, outcome, durationMs |
| `AppSetting` | retention, caps, defaults |
Run directories: `filesDir/runs/<runId>/`. Task bundles: `filesDir/tasks/<id>/<version>/` (read-only). SQLCipher optional (decide in Phase 2); secrets are always Keystore-wrapped regardless.

Additional entities (v3): `TriggerRule` (source, paramsJson, cooldown, maxLaunch, debounce, busyPolicy, counters), `FlowRecording` (taskId, stepsJson, createdAt), `AppPack` (package, version, json), `HealthSnapshot` (checkId, result, at), `SettingFlag` (insecure-feature switches, developer toggles).

---

## 17. Module layout
```
autohost/
├─ app/                       # Application, manifests per flavor, nav host, AppGraph (manual DI), services/receivers
├─ build-logic/               # convention plugins (android library, compose, kotlin-jvm)
├─ core/
│  ├─ model/ common/ data/ engine/ broker/
│  ├─ designsystem/           # theme + shared Compose components
│  └─ devapi/                 # Dev API server (HTTP+WS JSON-RPC, ContentProvider adapter), token auth
├─ runtime/
│  ├─ api/                    # RuntimeProvider SPI
│  ├─ python/                 # Chaquopy integration, worker services, SDK python sources
│  └─ flow/                   # declarative flow interpreter (in-process), recorder model, guarded replay
├─ capabilities/
│  ├─ a11y/                   # service, ReadGate, TraversalGuard, RootResolver, SelectorEngine, gestures, overlays, AutoAcceptGate
│  ├─ ime/                    # AutoHost keyboard (text commit, clipboard)
│  ├─ app/ screen/ projection/ system/ net/ notify/ files/ intent/ events/ data/
│  ├─ shell/ vision/ browser/ prompt-ui/ rawbridge/
├─ feature/                   # home, library, taskdetail, profiles, runs, schedule, triggers, setup (health),
│                             # settings, devtools, taskui (declarative + HTML), surfaces, inspector, recorder
├─ plugin-sdk/                # AIDL + helpers for external capability APKs
├─ sdk-python/                # python package, .pyi, `autohost` CLI + MCP server, `autohost-sim` simulator
├─ testing/{fixture-app, fixtures, fakes}
├─ samples/                   # hello, press-button (py), press-button-flow, settings-reader, http-check, …
├─ schemas/ docs/ prompts/ tools/
└─ THIRD_PARTY_NOTICES.md
```
`core:model` and `core:engine` stay pure-JVM-testable. Capability modules depend only on broker interfaces. The Python SDK source is the single source for both the embedded SDK and `sdk-python/` (Gradle copy task). File-level detail: `docs/REPO_LAYOUT.md`.

---

## 18. Phases, deliverables, gates

**Milestones:** **MVP = end of Phase 4** (archetype A on a schedule, with profiles). **Authoring-ready = end of Phase 6** (an agent can inspect the phone, write or record a task, run it and debug it through MCP). **v1 = end of Phase 8.** Phases 9–11 add power tools, browser/vision/hardware, extensibility.
Task-level checklist: `docs/PROGRESS.md`. Gate procedures and required evidence: `docs/GATES.md`.

### Phase 0 — Feasibility spikes (throwaway code under `spikes/`; verdicts in `docs/SPIKES.md`)
| Spike | Question | Pass criteria | Fallback |
|---|---|---|---|
| **S1 Python runtime** | Chaquopy in two `:wN` processes: import `requests`, AIDL round trip to main, kill a worker cleanly; vendored pure-Python importable from a writable dir; the phone's actual ABI (check 32-bit) plus arm64/x86_64; 16 KB-page compatibility on an API 35+ emulator | Two concurrent interpreters; kill leaves main intact; round trip typically <50 ms | Single worker + cooperative cancel; or another embedded CPython |
| **S2 Accessibility reality (Android 10 phone)** | Enable the service; read another app's tree (also try a **wedged-read** simulation); click; `SET_TEXT`; scroll; **screenshot via MediaProjection with the consent dialog auto-accepted by the accessibility service** (record every dialog layout/label seen, behaviour after process death); **launch another app from the accessibility context, from an FGS, with the overlay permission**. On emulators: API 30+ `takeScreenshot`, API 33+ restricted-settings flow, Advanced Protection if available | Each action works against the fixture app **and** a real third-party app; auto-accept succeeds ≥95 % over 20 attempts; launch + screenshot chains ranked; emulator findings marked UNVERIFIED-on-hardware | Manual consent (`WAITING_USER`); adjust launch chain; raise T3 priority |
| **S3 Background survival** | On the phone: exact alarm (no permission on API 29) → foreground service → run ≥10 min; Doze and reboot; OEM behaviour; **keep-awake watchdog** (wake lock + recovery activity) with the screen off. Emulators (API 31, 33, 34+): exact-alarm paths, FGS-from-background limits, `specialUse` type | 6 h unattended test at 10-min cadence ≥95 % on time on the phone; emulator paths behave as §3.5 says | Document OEM steps; WorkManager fallback badge |
| **S4 Shell tier (optional, does not block P1–P3)** | **Android 10:** PC runs `adb tcpip 5555`; the app's own ADB client connects to `127.0.0.1:5555`, key authorized once (evaluate OSS ADB libraries incl. licence, and Shizuku's PC-started mode); `input`, `uiautomator dump`, `screencap`, `am force-stop`; one-time `pm grant … WRITE_SECURE_SETTINGS` then self-enable the accessibility service; **observe whether a UiAutomation connection suppresses the accessibility service** (decides provider exclusivity). If an Android 11+ device/emulator exists, try Wireless-debugging pairing | Commands work; reboot behaviour documented; secure-settings self-heal survives a reboot; exclusivity behaviour recorded | Shizuku adapter only; mark T3 "advanced" |
| **S5 Text input and IME** | Our `InputMethodService` commits Unicode/password text into several apps; clipboard read in the background via the IME; programmatic IME selection via secure settings; compare coverage of `ACTION_SET_TEXT` vs IME vs paste | Typed outcomes reproducible; coverage table recorded | Skip IME path; keep `SET_TEXT` + paste |
Browser/CDP/OCR spikes run at the start of Phase 10.
**Gate 0:** every spike has a verdict or chosen fallback; `DEVICES.md` (incl. the UNVERIFIED-on-hardware list) and `DECISIONS.md` (incl. the app licence) updated. No production code before this gate.

### Phase 1 — Walking skeleton: "a script clicks something in another app"
1. Gradle multi-module scaffold (`app`, `core:model/common/broker/engine`, `runtime:api/python`, `capabilities:a11y/app`, `testing:fixture-app`), build-logic, version catalog, flavors `core`/`full`, ktlint/detekt, one-command check, docs stubs.
2. `core:model` types; `core:common` (`Clock`, `ApiGate`, `AhResult`, `AhLog`, `Ids`, JSON).
3. Broker v0: AIDL, run tokens, registry, JSON envelope, **interceptor chain skeleton** (auth, scope, audit); temporary grant-all in dev, but **scope rejection already enforced** for UI calls.
4. Python worker (`:w0`) with SDK v0 (`ctx`, `log`, `device.app`, `device.ui` basics, errors); run lifecycle v0 (start, success/fail, cancel with kill, heartbeat); run foreground service + notification with Stop (version-guarded `startForeground`).
5. `A11yProvider` v0 behind an `A11yReader` interface (hardened in Phase 3): `snapshot`, `find`, `click`, `set_text`, `wait_for`, back/home; selector engine v0.
6. `AppProvider` v0: launch, foreground, installed.
7. Dev-folder loader (app-specific external dir; minimal manifest) → Library list → Run → live log screen with Stop.
8. Setup screen v0 (accessibility status + deep link, notifications permission).
9. Fixture app + an instrumented test running a Python task through the real service stack.
**Gate 1 (real device = the Android 10 phone):** (a) `hello` logs progress live; (b) `press-button` launches the fixture app, waits for the button, clicks it; (c) a script reads text and clicks in a **real third-party app** screen; (d) a `while True: sleep` script is stopped within 6 s from the notification, the worker process is gone, the UI stays responsive; (e) a Python exception appears as `FAILED` with traceback; (f) a UI call for an out-of-scope package is rejected by the broker (test).

### Phase 2 — Task platform, health checks, Dev API v0
`.aatask` import/validate/version/remove (all sources, ImportReport, integrity hash, dev-mode permission diff, manifest migrations skeleton); Room DB with migrations; repositories; Library and Task detail UIs; schema-driven config forms; **profiles** CRUD; Keystore-wrapped secrets + `profile_store`/`task_store`; grants + Permission Review; capability/app/API **resolver + CompatReport**; Python import scanner; **`HealthCheck` registry** and Setup & Health v1; New-from-template; **Dev API v0** (HTTP/WS JSON-RPC + token: ping, state, `ui.snapshot/find/click`, `app.launch`, `tasks.list/install`, `runs.start/status/tail`) and **`autohost` CLI v0** (`push`, `run`, `logs`, `ui dump`); samples (hello, press-button, settings-reader).
**Gate 2:** two tasks × two profiles keep separate config, secrets, stores and history; broken bundle → clear ImportReport; canary secret never in DB plaintext/logs/exports; changing a scoped config value re-prompts; unmet required capability/app → CompatReport with reason + Fix; an agent on the PC pushes and runs a task through the Dev API without touching the phone.

### Phase 3 — Device depth (hardened T1, IME, screenshots)
`SelectorEngine` full (match modes, relational filters, deepest-match); `NodeRef` handles with stale detection; **ReadGate, TraversalGuard, RootResolver, ForegroundResolver** (§5.7) with degraded snapshots and `NO_ACTIVE_WINDOW` vs `EMPTY_TREE`; event-driven waits; **settle** (`wait_for_settle`, `retry_if_no_change`); **PopupGuard**; click strategy chain; scroll_until, gestures, global actions, keyboard state; **AutoHost IME** + `TextInputResult` + `set_text` chain + background clipboard; **screenshot chain** (Accessibility ≥30 → MediaProjection + `ProjectionService` + **AutoAcceptGate** → T3 `screencap` later) with typed failures and throttling; toast events; compact dump; evidence capture + `expect=` verification; `autohost.flow` toolkit (`Screen`, `classify`, `transition`, `retry`); app **stop** chain; `device.screen` reads; expanded fixture app; published latency numbers (target p95 < 200 ms single selector on ≤2,000 nodes).
**Gate 3:** instrumented suite passes (click chain, `set_text` outcomes incl. Unicode + password, scroll_until, keyboard, screenshot [MediaProjection on the phone, Accessibility on an API 30+ emulator], secure-screen typed failure, stale-node fail-closed, out-of-scope returns nothing, coordinate results flagged, popup guard, settle); a simulated **wedged read** returns a degraded snapshot within budget; **unattended screenshot** on the phone succeeds ≥95 % over 20 cycles with the consent auto-accepted; **100 consecutive launch → wait → click → verify cycles ≥99 %** on the fixture app; `settings-reader` passes on the phone.

### Phase 4 — Triggers, scheduling and ScreenGuard (**MVP complete**)
`ScheduleEngine` (interval, daily/weekly, cron subset, once, window; limits; conditions; misfire; per-profile fan-out) with fake-clock tests; persistence; **pluggable backends + reconcile worker** (§11.3); locks and queue; pre-flight incl. screen/unlock policy; **ScreenGuard** (§11.6); `ctx.next_run`; Schedule UI; always-on recipe checklist; **boot self-heal** (re-arm schedules; re-enable the accessibility service when `WRITE_SECURE_SETTINGS` is granted); `press-button` hardened as the archetype-A reference.
**Gate 4:** fake-clock suite covers all rule types/limits/misfire; **3-hour soak** at 10-min cadence with two profiles on the fixture app ≥95 % on time, correct per-profile order, never two UI runs at once, recovery after a forced worker kill and after a reboot (accessibility returns without manual steps when the secure-settings grant exists); with exact alarms denied the "timing may be late" badge appears; the reconcile worker repairs a deliberately deleted alarm.

### Phase 5 — Reliability, observability and the authoring loop
Checkpoints and stages; pause/cancel/resume; `INTERRUPTED` recovery + auto-resume policy; `WAITING_USER` + `ui.ask`; **idle watchdog and incidents** (§12.5); evidence retention; run export bundle; audit viewer; **Dev API full** (+ `triggers.*`, `health.*`, `events.subscribe`); **CLI full** and **`autohost mcp`** (tools in §5.6); `autohost doctor`; **Skill-format agent pack**; generated artifacts pipeline + drift test; simulator `autohost-sim`; MCP eval scenarios.
**Gate 5:** a 3-stage task killed mid-stage resumes without repeating completed stages; manual-intervention round trip works; an exported bundle alone shows stage, reason code, incident, compact dump and redacted evidence; canary secret absent from all outputs; an idle script is cancelled by the watchdog; **a fresh agent session using only MCP + the agent pack inspects the phone, tries selectors, writes a task, runs it, reads a failure bundle and fixes it** (record the transcript).

### Phase 6 — Declarative flows and recorder (**authoring-ready**)
`:runtime:flow` interpreter (command set §7.9, selectors, `when`/`repeat`/`retry`/`optional`, variables + secrets, hooks), `flow.schema.json`, runs in-process through the broker; flow tasks use the same grants/profiles/schedules/logs/evidence/recovery; **recorder overlay** → flow with stability-ranked selectors and `pre_state` snapshots; **guarded replay** with `on_mismatch`; flow view/edit-as-text on task detail; simulator support for flows; `press-button-flow` sample.
**Gate 6:** the flow version of `press-button` runs on the phone with **no `:w*` worker process** (record `ps`/memory); a flow recorded from a fixture interaction replays; a deliberately changed screen makes replay stop with evidence; a flow task and a Python task coexist with independent schedules; recorded steps with coordinates require confirmation.

### Phase 7 — Task UI and surfaces
Tier B declarative renderer and Tier C HTML host (CSP, bridge); imperative `ui.*`; notification actions; **floating bubble**; QS tiles; pinned shortcuts; home widget; token-guarded `RUN_TASK` intent; **inspector overlay**; **global volume-key stop**; `ui.console`.
**Gate 7:** sample tasks demonstrating each UI tier trigger handlers and show live state; a tile and a shortcut start a run; the bubble's Stop and the volume-key stop halt a run while another app is foreground; the inspector overlay copies a working selector; an external intent without the token is rejected.

### Phase 8 — Capability breadth, raw bridge, event triggers (**v1 complete**)
Via the §6.3 template: notifications (listen/act), `EventHub` + event triggers + **trigger rules** (§11.9: cooldown, debounce, busy policy, catalog/status/test), clipboard, intents, files (shared/SAF), `net.ws`, `net.server` webhook, audio/TTS/vibrate/flashlight/power, `device.screen` control, overlays, `device.settings` read, `schedule.self`, `tasks.run`, **`android.api.direct`** (import guard on/off), **insecure-feature switches** (§14.5), lazy Android-permission flows, `capabilities-showcase` task.
**Gate 8:** ≥12 capabilities shipped via the template with showcase snippet, tests and honest availability; switches default off and are enforced; raw bridge blocked without grant and switch; a notification-triggered, a webhook-triggered and a chained task run end-to-end; cooldown/debounce/busy-queue behaviours proven by tests.

### Phase 9 — Shell tier and power tools
ADB client transports in order: **ADB-over-TCP (PC-assisted per boot — the Android 10 phone's path)**, Wireless-debugging pairing (API 30+, UNVERIFIED-on-hardware until run), Shizuku, root; `ShellUiProvider`; optional experimental `UiAutomationProvider` (exclusive with the accessibility service, §5.4); `ShellScreenshotProvider`; `device.shell` with allowlist; verified force-stop; clear state/grant permissions/mock location/dark mode/orientation/radios; `device.logcat`; `device.app.manage` + installer auto-accept; `autohost grant-secure-settings` helper → accessibility self-heal, IME auto-select, keep-awake; guided steps + post-reboot reminder.
**Gate 9 (Android 10 phone):** with Accessibility disabled, `settings-reader` passes through the shell provider; self-heal re-enables the service after a forced disable; allowlist and switches enforced by tests; reboot/re-arm behaviour documented; exclusivity rule verified.

### Phase 10 — Browser, vision, remaining data/hardware (W3)
Start with spikes: WebView automation with real input events, per-profile cookie isolation, CDP over an ADB stream, OCR/template match. Then `browser.webview` (Playwright-like subset), `browser.a11y`, `browser.cdp` if viable; `vision.*`; contacts/calendar/media/location/camera/mic/sensors/SMS (`full` flavor).
**Gate 10:** a sample task logs into a **local test web page** and scrapes it; an OCR demo reads a screenshot region; challenge pages are detected and routed to `WAITING_USER` (never bypassed).

### Phase 11 — Extensibility and release
JavaScript (QuickJS) runtime over the same broker; **plugin APK SDK** + one example external capability plugin; task repository/index import; **app packs**; in-app Task Studio; PC web inspector (`autohost ui`); reverse WebSocket transport; backup/restore (tasks + profiles minus secrets); release signing and verified-developer registration; upgrade/migration tests; user guide; OEM matrix notes. *Optional compatibility exercise:* port any existing Python automation by rewriting it against the SDK, logging every gap as an SDK/capability issue — never as a hack or shim.
**Gate 11 (final acceptance):** (F1) a brand-new task written from the generated pack with **no Kotlin changes** is installed, profiled and scheduled; (F2) a Python task and a flow task and a JS task coexist with independent state; (F3) an unavailable capability/app yields a clear CompatReport; (F4) a new capability provider (plugin APK or in-app module) is used by two tasks with zero task changes; (F5) all three runtimes run through the same broker.

---

## 19. Testing strategy
- **Test layers (no mocks of our own classes — fakes only):** (1) pure unit tests for policies and engines; (2) **in-process integration** with a `FakeDevice` + fake clock (real orchestrator, real broker and interceptors, fake providers); (3) instrumented tests against the fixture app; (4) E2E dogfooding — sample tasks and flows run on device; (5) **API matrix** — the same instrumented suite on emulators for API 30/33/34/latest and the phone, run again whenever a new system image appears. MCP evals: YAML scenarios drive `autohost mcp` tools against `FakeDevice` and the fixture app.
- **Device/API matrix:** real-device gates run on the Android 10 phone. Unit and Robolectric tests are parameterized over API 29, 30, 33, 34 and the latest; instrumented suites also run on emulators for API 30, 33, 34 and the latest. Every provider/path is recorded as `VERIFIED_ON` (devices) or `UNVERIFIED-on-hardware` in `docs/DEVICES.md`.
- **Pure unit (JVM):** manifest validation, selector matching/normalization, `Screen` classification, `ScheduleEngine` (virtual clock), redactor, checkpoint/resume state machine, scope/permission enforcement, import scanner.
- **Contract tests:** broker envelopes and error codes; SDK ↔ broker using an in-memory fake broker; AIDL compatibility; generated-artifact drift test.
- **Instrumented (emulator + real device):** fixture-app suites for accessibility/app capabilities; worker kill/restart; foreground-service and notification behaviour; WebView browser; shell tier (device only).
- **Python SDK tests (PC):** `pytest` + `autohost-sim` using the same SDK source; runs in CI without a phone.
- **Soak:** scheduler (hours), interpreter memory (workers recycle per run), log/evidence growth.
- **Security:** canary-secret grep across DB, logs, evidence, exports; out-of-scope node leakage; revoked-token rejection; import-guard bypass attempts (document results).
- **Fixtures:** sanitized UI-tree JSON from real apps under `testing/fixtures`; never commit credentials or personal screenshots.

---

## 20. Risks and mitigations
| Risk | Likelihood | Mitigation |
|---|---|---|
| Accessibility blocked (Advanced Protection, OEM policy, restricted settings) | Med | Named states + guidance; T3 alternative provider; no misdeclared accessibility-tool flag |
| Background app launch blocked | Med | S2-ranked launch chain; verify-after-launch; full-screen-intent fallback |
| OEM battery killers stop schedules | High | Exact alarms, FGS, exemptions, OEM guide, health dashboard, soak tests, self-heal |
| Target app UI changes | High | Selectors live in task code/config; verification + evidence; easy task updates |
| Installation friction (developer verification, Play Protect warnings) | Med | Register package under a limited-distribution account; `core`/`full` flavors; honest docs |
| Chaquopy multi-process quirks | Low–Med | S1; single-worker fallback |
| Native Python deps unavailable | Med | Curated bundled set per flavor; Kotlin providers for heavy lifting; honest linter |
| Wireless debugging instability | Med | Optional tier; Shizuku adapter; self-re-enable; clear re-arm UX |
| Same-UID sandbox weakness | Known | Documented; import guard; trusted-source UX; future plugin-APK isolation |
| Scope creep ("universal anything") | High | Phase gates; MVP at Phase 4; capability module template; no app-specific host code |
| Misuse against service rules | — | §0.4 boundaries enforced by scoping/visibility; no stealth features |
| No Accessibility screenshots and no on-device Wireless debugging on Android 10 | Certain | Provider chains (§3.5); UI-tree evidence first; PC-assisted shell tier; one-time `WRITE_SECURE_SETTINGS` grant |
| The Android 10 phone is 32-bit, low-RAM or has an old WebView | Med | Record ABI/RAM/WebView in `DEVICES.md`; adaptive worker slots; per-ABI builds; browser provider treated as optional |
| API 30+ paths cannot be tested on real hardware | Med | Emulator matrix; label UNVERIFIED-on-hardware; ask the user to test on a newer device when one is available |
| A UiAutomation-based provider suppresses accessibility services | Known | Mutual-exclusivity rule (§5.4); default `A11yProvider`; S4 records behaviour |
| Auto-accept of system dialogs breaks on OEM dialog variants | Med | View-id + localized-label matching, cooldown, fallback to `WAITING_USER`; S2 collects variants |
| Licence contamination from AGPL/GPL references | Low–Med | Clean-room rule, `THIRD_PARTY_NOTICES.md`, licence CI check |
| Dev API exposes device control on a network | Med | Off by default; token; adb-forward/localhost default; LAN behind an insecure-feature switch; audited |
| Flow runtime creeping into a full scripting language | Med | Frozen v1 command set; limited expressions; Python is the escalation path |

---

## 21. Defaults to use unless the user overrides
1. App name / package: `AutoHost` / `app.autohost`.
2. **minSdk 29** (Android 10); compileSdk/targetSdk latest stable (≥35). Do not lower the target to dodge newer-OS restrictions — handle them through the §3.5 matrix; API-gated features sit behind availability checks.
3. DI: none — manual constructor wiring through an `AppGraph` (workers use a minimal `WorkerGraph`); no Hilt/Dagger.
4. DB encryption: none at first (secrets always Keystore-wrapped); revisit in Phase 2.
5. Concurrency: adaptive worker slots (2 on ≤3 GB RAM or low-RAM devices, up to 4) + 1 UI-handler worker; UI lock = 1.
6. Retention: logs 30 days, evidence 14 days, user-adjustable.
7. Shell tier: our own ADB client — ADB-over-TCP first (the Android 10 path), Wireless-debugging pairing for API 30+, then Shizuku, then root (use a vetted OSS library if its licence allows).
8. Browser default provider: in-app WebView.
9. Second runtime: JavaScript (QuickJS).
10. Distribution: sideload; register the package under a limited-distribution developer account; release key stored outside the repo.
11. Python: newest Chaquopy-supported version ≥3.13; record in `VERSIONS.md`.
12. Build flavors: `core` and `full` (§3.3).
13. Export/backup: secrets never exported; export bundles redact by default.
14. Dev API off by default; ports 8080/8081; token required; localhost/adb-forward only unless `dev_api_remote` is enabled.
15. Flow format: YAML (JSON accepted); use a vetted parser whose licence fits the app licence.
16. Scheduler backend `auto`; reconcile worker every ~20 min.
17. App licence decided in Phase 0 (`DECISIONS.md`); until then reuse only Apache/MIT code and clean-room everything else.

---

## 22. Definition of done (platform level)
- [ ] Gate 10 (F1–F5) demonstrated with recorded evidence.
- [ ] No task- or service-specific code in `app/`, `core/`, `capabilities/`, `runtime/`.
- [ ] Every capability in every sample task resolves to an availability state with a reason and a Fix action where one exists.
- [ ] Cancel, kill, resume and recovery behaviours covered by automated tests.
- [ ] Secrets never appear in DB plaintext, logs, evidence summaries or exports.
- [ ] `SECURITY.md` states exactly what is and isn't isolated.
- [ ] SDK docs, `.pyi` stubs and the agent context pack are generated from specs and complete; a new task can be written from them alone.
- [ ] Setup & Health takes a fresh device to "ready" without outside instructions.
- [ ] Gates 0–8 recorded on the Android 10 phone (9–11 as reached); every API 30+ path is validated on an emulator or marked UNVERIFIED-on-hardware.
- [ ] The Dev API + MCP server drive the phone end to end (inspect → try → write/record → run → debug) with a recorded transcript.
- [ ] A flow task and a Python task coexist; the flow task runs with no worker process.
- [ ] Insecure-feature switches default off and are enforced by tests.
- [ ] `THIRD_PARTY_NOTICES.md` exists and the licence CI check passes.
