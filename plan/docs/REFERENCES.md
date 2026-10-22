# REFERENCES — what we learned from other projects, and the licence rules

This file records the **reference review** that shaped plan v3. Use it when a task says "see REFERENCES §x".

## 0. Licence rules (read first)
| Project | Licence | What you may do |
|---|---|---|
| **Mobilerun Portal** (`droidrun/mobilerun-portal`) | **AGPL-3.0** | **Clean-room only.** Read for *ideas*; never copy code, structure, identifiers or comments. Write your own implementation from the behaviour described here. |
| **OpenAutoJs / Auto.js family** (`openautojs/openautojs`) | **GPL-3.0 + a non-commercial clause** (MPL-based) | **Clean-room only.** Same rule. |
| **Maestro** (`mobile-dev-inc/maestro`) | Apache-2.0 | Ideas freely; code may be adapted **with notices preserved** and a line in `THIRD_PARTY_NOTICES.md`. |
| **Appium** (`appium/appium`) | Apache-2.0 | Same as Maestro. |
| **Mobilerun** framework (`droidrun/mobilerun`) | MIT | Ideas freely; code may be adapted with the MIT notice preserved. |
| **Google Artemis** (`google/artemis`) | Apache-2.0 (as reported) | Same as Maestro. Review the repo directly before relying on any claim. |
| **Minitap mobile-use** | Apache-2.0 | Same. Note the public attribution dispute around Artemis: **keep attribution correct.** |

"Clean-room" means: one person (the planner/agent reading this file) distils the *behaviour*; the implementer writes new code from this description. Do not paste snippets from AGPL/GPL projects into the repo, even "just to get started".
Keep `THIRD_PARTY_NOTICES.md` at the repo root for any Apache/MIT code actually reused.

## 1. Summary of lessons → where they landed in the plan
| # | Lesson | Source | Landed in |
|---|---|---|---|
| L1 | Accessibility reads can **wedge** (binder hangs). Bound every read with a timeout, run it single-flight on a small pool, and serve the last good snapshot flagged `degraded` instead of blocking. | Portal | plan §5.7, REPO_LAYOUT `A11yReadGate` |
| L2 | Tree traversal needs a **guard**: depth cap (128), cycle detection, every node read wrapped for `RuntimeException`, strict recycling. | Portal | `TraversalGuard` |
| L3 | Resolve **multiple window roots** (active window + higher-layer application windows such as popups/dialogs), not just `rootInActiveWindow`. Distinguish *no active window* from *window with no semantic nodes* (Flutter/game/WebView) — the latter is not an error. | Portal | `RootResolver`, errors `NO_ACTIVE_WINDOW` vs `EMPTY_TREE` |
| L4 | Foreground app = **window evidence** (window-state events + the window root's package), not the event's package alone. | Portal | `ForegroundResolver` |
| L5 | **Custom IME** for reliable Unicode text entry and for background clipboard read; text input returns a **typed outcome** (Verified / Rejected / AcceptedUnverified / CommitOutcomeUnknown / InputSessionChanged). The IME can be selected programmatically once `WRITE_SECURE_SETTINGS` is granted. | Portal, Maestro | `device.ime`, `set_text` strategy chain |
| L6 | **MediaProjection consent can be auto-accepted** through the accessibility service (system dialog in `com.android.systemui`, several dialog layouts, cooldowns, failure back-off). This is what makes the Android 10 screenshot path unattended. Same pattern for package-installer prompts. | Portal | `AutoAcceptGate`, screenshot chain |
| L7 | **Keep-awake watchdog**: pure policy (enabled, interactive, locked, throttle 60 s) + steady wake lock + 30 s poll + screen receiver + a transparent recovery activity (`showWhenLocked`, `turnScreenOn`, `requestDismissKeyguard`) with tokens/generations and a surfaced *degraded reason*. | Portal | `ScreenGuard` (Phase 4) |
| L8 | **Local control API** with three transports (ContentProvider via adb, HTTP + WebSocket JSON-RPC with a bearer token, reverse WebSocket where the device dials out) and a "no-accessibility" headless mode. | Portal, Artemis | `Dev API` (§5.6) |
| L9 | **Trigger rules** as data: sources (time delay/absolute/daily/weekly, notification posted/removed, app entered/exited, battery low/okay/level, power connected/disconnected, user-present, network connected/type-changed, SMS), cooldown, max launch count, notification **debounce buffer**, **busy queue** (what happens when a rule fires while a run is active), catalog + status endpoints, strict validation. | Portal | plan §11.9, `schemas/trigger-rule.schema.json` |
| L10 | API-version code isolated in tiny `*Api30` objects; decision logic extracted to pure `*Policy` classes with exhaustive JVM tests. Debug-only **fixture activities** (popup window, deep link, unbounded scroll) double as instrumented-test targets. | Portal | CONVENTIONS, FIXTURE_APP |
| L11 | **Guarded macro replay**: record each action with pre/post UI snapshots and elapsed time; replay checks state similarity (node-set Jaccard 0.85 weight + package/activity 0.15, threshold 0.85); on mismatch **stop** or **hand off**. | Mobilerun | `FlowRuntime` + recorder (Phase 6) |
| L12 | **Compact, indexed UI text** for LLM/agent consumption and for logs: phone-state header + `index. Class: "id" "text" [flags] (bounds)`; concise vs detailed filters (on-screen, min size 5 px, ≥1 % visible); optional normalized coordinates. | Mobilerun | `device.ui.dump(compact)` spec (§7.6) |
| L13 | **Credentials referenced by key**, resolved at action time; enable/disable flags per secret; agents never see values. | Mobilerun | `secret=` handling, flows `${secret.X}` |
| L14 | **App cards**: per-app guidance packs (selectors, screens, hints) kept apart from tasks and loadable by any task. | Mobilerun | "app packs" (Phase 11) |
| L15 | **MCP server + Skill-format docs** are the standard way coding agents drive devices and learn an SDK. | Mobilerun, Maestro, Artemis | `autohost mcp`, agent pack as `SKILL.md` + references |
| L16 | **Selector breadth**: id/text/desc/class/package × equals/contains/startsWith/endsWith/regex; bounds, depth, drawing order, boolean states; DFS/BFS; relational filters (below/above/leftOf/rightOf/childOf/containsChild/containsDescendants); `index`; "deepest matching element". | Auto.js (concept), Maestro | `SelectorEngine` |
| L17 | **Click fallback chain**: node action → nearest clickable ancestor → gesture at node centre (flagged `COORDINATE`). | Auto.js (concept) | `device.ui.click` |
| L18 | **Settle after every action**: compare hierarchy snapshots until stable (or timeout); `retryTapIfNoChange`; `isWindowUpdating` primitive. | Maestro | `wait_for_settle`, `click(retry_if_no_change)` |
| L19 | **Declarative flows** (YAML commands: tapOn, inputText, scroll(Until), assert*, launchApp, stopApp, runFlow with `when`, repeat, retry, optional, env, hooks) interpreted by a small in-process orchestrator — no scripting runtime needed. | Maestro | `FlowRuntime` (Phase 6) |
| L20 | **Errors classified retryable vs terminal**; orchestrator owns retries and lifecycle. | Maestro | `AhError.retryable` |
| L21 | **Test layering with an in-process FakeDriver** (+ fake timer); "no mocks of our own classes"; E2E dogfooding with the product itself; run the same E2E suite against new Android system images. | Maestro | TESTING §19, `FakeDevice` |
| L22 | **Agent docs**: module map with roles, test layers, conventions, "What NOT to do", artifacts split into inputs / outputs / temp with a closeable temp-file handler. | Maestro | AGENTS.md, CONVENTIONS |
| L23 | **Insecure features**: dangerous capabilities are *off by default*, enabled by explicit `scope:feature` allow-lists (+ relaxed mode), with deny winning. | Appium | plan §14.5, Settings → Developer |
| L24 | **Doctor checks**: each check has `diagnose()`, `fix()`, `hasAutofix()`, `isOptional()`; results grouped into *needs manual fix* vs *autofixable* vs *optional*; exit codes. | Appium | `HealthCheck` (Setup & Health, `autohost doctor`) |
| L25 | **Command middleware**: plugins wrap commands (`handle(next, …)`). | Appium | broker `Interceptor` chain (§5.4) |
| L26 | **Versioned extension manifests with migrations**; schema-validated config. | Appium | `task.json` schema versions + migrations |
| L27 | **Idle/command timeout** per session so hung scripts are reaped. | Appium | `execution.idle_timeout_seconds` |
| L28 | **Pluggable scheduler backends** (WorkManager / JobScheduler / AlarmManager), a **periodic reconcile worker** that re-arms lost work and runs overdue tasks, scheduling precisely only inside a near window. | Auto.js (concept) | plan §11.3 |
| L29 | **Global stop gesture** via accessibility key-event filtering (volume key) with a shell-key fallback. | Auto.js (concept) | surfaces (§10.3) |
| L30 | **UiAutomation vs accessibility services**: a UiAutomation connection (instrumentation-style drivers) suppresses regular accessibility services; Artemis deliberately avoids holding it. Our T1 service and any UiAutomation-based provider are **mutually exclusive**. | Artemis, Appium | provider selection rules (§5.4) |
| L31 | **Dynamic-first locating** (accessibility node → coordinates/vision), a pre-action **safety net** that detects a system popup about to swallow the tap, failures kept as open **incidents** until a later success resolves them, configurable **verification levels**. | Artemis | `PopupGuard`, run incidents, `verification` policy |
| L32 | **Toasts** are observable through accessibility notification-state events. | Maestro | `events.toast` |
| L33 | On-device conveniences exposed by a shell tier: clear app state, grant permissions, mock location, dark mode, orientation, airplane mode. | Maestro | catalog §6.5 |

## 2. Where to look (study list per module)
Paths are inside each project's repository. Clean-room rules above apply.

**Accessibility layer (`:capabilities:a11y`) — Portal (AGPL, ideas only)**
`app/src/main/java/com/mobilerun/portal/`: `core/AccessibilityTreeBuilder.kt`, `core/AccessibilityTraversalGuard.kt`, `core/AccessibilityRootResolver.kt`, `core/StateRepository.kt`, `api/A11yReadGate.kt`, `service/MobilerunAccessibilityService.kt`, `service/GestureController.kt`, `service/ForegroundApplicationWindowResolver.kt`, `service/ForegroundApplicationTransitionTracker.kt`, `service/AccessibilityScreenshotApi30.kt`; tests under `app/src/test/.../core/*`.
**System-dialog auto-accept** — `service/MediaProjectionAutoAccept.kt`, `service/AutoAcceptGate.kt`, `service/PackageInstallerAutoAccept.kt`, `service/MediaProjectionScreenshotter.kt`.
**IME** — `input/MobilerunKeyboardIME.kt`, `input/InputConnectionTextEditor.kt`; Maestro: `maestro-android/src/main/java/dev/mobile/maestro/input/MaestroInputMethodService.kt`.
**Keep-awake** — `keepalive/KeepAliveRecoveryPolicy.kt`, `KeepAliveController.kt`, `KeepAliveService.kt`, `KeepAliveRecoveryActivity.kt` (+ their `*PolicyTest.kt`).
**Triggers/events** — `triggers/*`, `events/*`, and (in the Portal repo, not ours) `docs/triggers.md`, `docs/local-api.md`, `docs/reverse-connection.md`.
**Fixtures** — `app/src/debug/java/.../core/{PopupWindowFixtureActivity,DeepLinkFixtureActivity,UnboundedRangeFixtureActivity}.kt`.

**Flows/orchestration — Maestro (Apache-2.0)**
`maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt`, `yaml/*` (command parsers), `maestro-orchestra-models/.../Commands.kt` and `ElementSelector.kt`, `maestro-client/src/main/java/maestro/Maestro.kt` (settle, retry-if-no-change), `maestro-android/src/androidTest/.../{MaestroDriverService,ViewHierarchy,InputInjector,ToastAccessibilityListener}.kt`, `maestro-proto/.../maestro_android.proto`, `maestro-test/` (`FakeDriver`, `FakeLayoutElement`, `FakeTimer`), `e2e/` (workspaces + `run_tests`), `AGENTS.md`, MCP server under `maestro-cli`.

**Extensibility/safety — Appium (Apache-2.0)**
`packages/appium/lib/insecure-features.ts`, `lib/doctor/doctor.ts`, `packages/types/lib/doctor.ts`, `lib/extension/{manifest,manifest-migrations,extension-config}.ts`, `packages/base-plugin`, `packages/execute-driver-plugin`, `packages/schema`, `packages/fake-driver`.

**Python/agent ergonomics — Mobilerun (MIT)**
`mobilerun/macro/{recorder,replay,matcher,state}.py`, `mobilerun/credential_manager/*`, `mobilerun/tools/filters/*`, `mobilerun/tools/formatters/indexed_formatter.py`, `mobilerun/app_cards/*` and `config/app_cards/android/*`, `mobilerun/mcp/*`, `SKILL.md`.

**Selectors/scheduler/API breadth — Auto.js family (GPL + NC; concepts only)**
`automator/.../UiGlobalSelector.kt`, `filter/*`, `UiObject.kt`, `search/{BFS,DFS}.kt`; `autojs/.../runtime/api/*` (namespace list); `app/.../timing/{TimedTaskScheduler,TimedTaskManager,TimedTask}.kt`; `app/.../autojs/key/GlobalKeyObserver.java`; `autojs/.../project/ProjectConfig.kt`.

## 3. Adopt / adapt / avoid
| Topic | Decision |
|---|---|
| `isAccessibilityTool="true"` (Portal sets it) | **Avoid.** AutoHost is not an accessibility tool; misdeclaring invites policy/trust trouble (plan §3.2). |
| `MANAGE_EXTERNAL_STORAGE`, cleartext HTTP, `allowBackup=true` (Portal) | **Avoid by default**; `full` flavor only for All-files; cleartext off; backup off. |
| `dataSync` foreground service (Portal) | **Avoid**; use `specialUse` (API 34+) and no type on API 29–33; `mediaProjection` only for the projection service. |
| Hilt/DI frameworks | **Avoid.** Maestro and Portal construct services manually; workers run in separate processes. Use manual wiring (`AppGraph`). |
| Python agent loops / cloud connectors (Mobilerun) | **Out of scope**; AutoHost is a script host, not an LLM agent. LLM-driven tasks may exist as ordinary tasks using `net.http`. |
| Stealth/anti-detection drivers (Mobilerun `StealthDriver`) | **Never.** Violates plan §0.4. |
| Packaging each script into its own APK (Auto.js `apkbuilder`) | **Out of scope** — it is exactly the per-automation app we are avoiding. |
| XPath selectors (Appium) | **Skip** in v1; relational selectors cover the need. |
