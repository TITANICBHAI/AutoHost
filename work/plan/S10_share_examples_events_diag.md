# S10 — Share, all examples (+ app picker), "when something happens", diagnostics. Branch `work/s10-extend`.
1. **Share an automation.** Move `zipDirectory` out of `BackupController` into `core:engine/tasks/BundleZipper.kt` (BackupController calls it; round-trip test `zip → BundleExtractor.stage`, no file outside the task folder, no database/secret content).
   Task page → **Share** → `cache/share/<name>.aatask` → `ACTION_SEND` through the FileProvider (add `<cache-path name="share" path="share/" />`; P04 already removed the whole-`files/` entry). Before sharing show the plain permission list ("Whoever opens this will be asked to allow: …").
2. **All examples.** Replace the hand-copied `app/src/main/assets/templates` with a Gradle `Copy` from `samples/` (exclude `README.md`, `__pycache__`, `plugin-example`); a test asserts they match. Group by need with a badge from `PermissionLabels`: *No setup* (hello, capabilities-showcase, task-ui-demo, task-ui-html) · *Internet* (http-check) ·
   *Controls apps* (settings-reader, press-button, press-button-flow, js-press-button, pack-presser) · *Reads the screen/photos* (ocr-demo, photo-organizer) · *Browser* (web-login-scrape). One plain sentence each (first line of the sample README). Add one example for `ui.prompt` (ask the user mid-run) and one for `notify.post` with an action.
3. **App picker.** Samples already declare `"x-ui": {"widget": "app-picker"}` on `target_package`; `ConfigFormModel.Field` ignores `x-ui` (REF 22). Add `widget: String?` to the model, render `app-picker` as a searchable installed-apps picker (reuse the search in `AskSheet.kt`; no package typing), and per UI_SPEC S06 `folder-picker`/`file-picker` via the system picker. Test the model reads `x-ui.widget`.
4. **"When something happens"** (replaces the developer form `TriggersSection`, `TaskScreens.kt:192`): `TriggerForm` (pure + tests, like `ScheduleForm`):
   | User picks | trigger type |
   |---|---|
   | plug in / unplug the charger | `power.connected` / `power.disconnected` |
   | battery below N % | `battery.level` (threshold) / `battery.low` |
   | connect to Wi-Fi [network] | `network.wifi_ssid` |
   | open / close [app] | `app.entered` / `app.exited` |
   | a notification from [app] contains [text] | `notification.posted` (plain "contains"; regex only under *More options*) |
   | screen on / unlock | `screen.on` / `user.present` |
   | after [automation] finishes | `task.chain` |
   One shared control *"Don't run more than once every [5] minutes"* (cooldown/debounce; technical names under *More options*). `sms.received`, `toast.shown` only with Labs on. All trigger ids exist in `schemas/trigger-rule.schema.json` (23 types).
   Also on the task page: [Pin a shortcut] · [Add to Quick Settings] · [Show in the widget] (existing controllers from `SurfacesScreen`; the catalog from S04 makes them work for any task).
5. **About + Copy diagnostics:** versions, device, compat/health summary, last crash, last 5 run results (ids + status only — **no** config values, **no** secrets; reuse the export redaction). The canary-secret test also runs on this text. Add a GitHub issue template that asks for it.
6. **ImportActivity (REF 25):** stage/install on `Dispatchers.IO` via `lifecycleScope` (no `!!`, no `runBlocking`), progress state; confirm a task cannot run before its Permission Review is approved (if it can, that is a P0 security bug — report it).
**Gate:** CI green with the new tests; phone: share a script task and open it on a fresh install; "plug in the charger → run Hello" works with three taps and no typing.
