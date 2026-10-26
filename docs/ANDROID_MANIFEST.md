# ANDROID_MANIFEST — manifest, permissions, services, configs
Source for `app/src/main/AndroidManifest.xml` (+ flavor/debug overlays) and the XML resources. Applies to `minSdk 29`, compile/target latest stable. Verify every `API` note on the primary phone and emulators (plan §3.5).

## 1. Flavors and build types
```kotlin
// app/build.gradle.kts (shape only; versions come from libs.versions.toml)
android {
  defaultConfig { minSdk = 29; applicationId = "app.autohost" }
  flavorDimensions += "tier"
  productFlavors {
    create("core") { dimension = "tier" }   // a11y, notification access, net, files, notifications — NO sms/call/camera/mic/contacts
    create("full") { dimension = "tier" }   // core + sensitive permissions (contacts, calendar, location, camera, mic, SMS, phone, BT, NFC, all-files)
  }
  buildTypes { debug { applicationIdSuffix = ".debug"; /* Dev API + fixtures + debug bridge */ }
               release { /* Dev API server not startable unless flavor full + developer toggle */ } }
}
```
Same `applicationId` for both flavors (one installed at a time). `pm grant` commands use the actual package (`app.autohost` or `app.autohost.debug`) — the CLI reads it from the connected device.
Chaquopy: set `abiFilters` to the ABIs recorded in `DEVICES.md` (+ `x86_64` for emulators) for debug; per-ABI splits for release.

## 2. `<application>` attributes
`android:name=".AutoHostApp"` · `allowBackup="false"` · `dataExtractionRules`/`fullBackupContent` excluding everything · `requestLegacyExternalStorage="true"` (honoured on API 29 only) · `usesCleartextTraffic="false"` + `networkSecurityConfig` · `supportsRtl="true"` · **no** `largeHeap`. `AutoHostApp.onCreate` reads `Application.getProcessName()`: main → build `AppGraph`; `:wN` → build `WorkerGraph` and start Python; never open the DB in workers.

## 3. Permissions
| Permission | Flavors | API / attributes | Why |
|---|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | both | — | net, conditions |
| `FOREGROUND_SERVICE` | both | 28+ | run service |
| `FOREGROUND_SERVICE_SPECIAL_USE` | both | 34+ | run/event-hub FGS |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | both | 34+ | projection FGS |
| `POST_NOTIFICATIONS` | both | 33+ (runtime) | notifications |
| `RECEIVE_BOOT_COMPLETED` | both | — | re-arm schedules |
| `WAKE_LOCK` | both | — | ScreenGuard, runs |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | both | — | Setup item |
| `SCHEDULE_EXACT_ALARM` | both | `maxSdkVersion="32"` | exact alarms API 31–32 |
| `USE_EXACT_ALARM` | both | 33+ (sideload auto-grant) | exact alarms API 33+; verify in S3 |
| `QUERY_ALL_PACKAGES` | both | 30+ | package visibility (sideload) |
| `SYSTEM_ALERT_WINDOW` | both | special access, optional | background activity start exemption, overlays |
| `PACKAGE_USAGE_STATS` | both | `tools:ignore="ProtectedPermissions"`, optional | foreground detection fallback |
| `WRITE_SETTINGS` | both | special access, optional | brightness/rotation |
| `WRITE_SECURE_SETTINGS` | both | `tools:ignore="ProtectedPermissions"` — **declared so `pm grant` works** | self-heal a11y, IME select, keep-awake |
| `KILL_BACKGROUND_PROCESSES` | both | — | stop chain (best effort) |
| `ACCESS_NOTIFICATION_POLICY` | both | special access, optional | DND |
| `VIBRATE` | both | — | `device.audio` |
| `USE_FULL_SCREEN_INTENT` | both | 29+ (special on 34+) | launch fallback, needs-user prompts |
| `READ_EXTERNAL_STORAGE` | both | `maxSdkVersion="32"` | legacy files |
| `WRITE_EXTERNAL_STORAGE` | both | `maxSdkVersion="29"` | legacy files |
| `READ_LOGS` | full | `tools:ignore="ProtectedPermissions"` | `device.logcat` via PC grant |
| `MANAGE_EXTERNAL_STORAGE` | full | 30+ special | `files.all` |
| `READ_MEDIA_IMAGES/VIDEO/AUDIO` | full | 33+ | media |
| `READ_CONTACTS`, `WRITE_CONTACTS`, `READ_CALENDAR`, `WRITE_CALENDAR` | full | runtime | data |
| `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` | full | runtime | location |
| `CAMERA`, `RECORD_AUDIO` | full | runtime | hardware |
| `READ_SMS`, `RECEIVE_SMS`, `SEND_SMS`, `READ_PHONE_STATE`, `CALL_PHONE` | full | runtime | sms/telephony |
| `BLUETOOTH`+`BLUETOOTH_ADMIN` (`maxSdkVersion="30"`), `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (31+), `NFC` | full | — | hardware |
Rules: the `core` manifest contains **no** sensitive group permission. Every permission has a Setup/Health row or a lazy request flow.

## 4. Components
```xml
<!-- Accessibility service -->
<service android:name="app.autohost.capabilities.a11y.AutoHostAccessibilityService"
         android:exported="false" android:label="@string/a11y_service_label"
         android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
  <intent-filter><action android:name="android.accessibilityservice.AccessibilityService"/></intent-filter>
  <meta-data android:name="android.accessibilityservice" android:resource="@xml/accessibility_service_config"/>
</service>

<!-- Input method -->
<service android:name="app.autohost.capabilities.ime.AutoHostKeyboardService"
         android:exported="true" android:label="@string/ime_label"
         android:permission="android.permission.BIND_INPUT_METHOD">
  <intent-filter><action android:name="android.view.InputMethod"/></intent-filter>
  <meta-data android:name="android.view.im" android:resource="@xml/method"/>
</service>

<!-- Notification listener (listen capability; user enables in settings) -->
<service android:name="app.autohost.capabilities.notify.AutoHostNotificationListener"
         android:exported="true" android:label="@string/app_name"
         android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
  <intent-filter><action android:name="android.service.notification.NotificationListenerService"/></intent-filter>
</service>

<!-- Run foreground service -->
<service android:name=".service.RunForegroundService" android:exported="false"
         android:foregroundServiceType="specialUse">
  <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="automation_runtime"/>
</service>
<!-- EventHub foreground service (only while event triggers are armed and no system-bound host service is available) -->
<service android:name="app.autohost.capabilities.events.EventHubService" android:exported="false"
         android:foregroundServiceType="specialUse">
  <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="event_listener"/>
</service>
<!-- Screenshot projection (FGS type required from API 29) -->
<service android:name="app.autohost.capabilities.projection.ProjectionService" android:exported="false"
         android:foregroundServiceType="mediaProjection"/>
<!-- ScreenGuard -->
<service android:name="app.autohost.capabilities.screen.ScreenGuardService" android:exported="false"
         android:foregroundServiceType="specialUse">
  <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="screen_guard"/>
</service>

<!-- Python workers: one process each; UI-handler worker on its own process -->
<service android:name="app.autohost.runtime.python.worker.TaskWorkerService0" android:process=":w0" android:exported="false"/>
<!-- … TaskWorkerService1 (:w1), 2 (:w2), 3 (:w3) -->
<service android:name="app.autohost.runtime.python.worker.UiHandlerWorkerService" android:process=":ui" android:exported="false"/>

<!-- Quick Settings tiles (P7-07, BUILT): Tile1Service..Tile5Service in .surfaces (one component per
     tile; bindings to task/profile/stage live in SurfacesPrefs, edited in Settings → Surfaces) -->
<service android:name=".surfaces.Tile1Service" android:exported="true" android:icon="@drawable/ic_autohost"
         android:label="AutoHost 1" android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
  <intent-filter><action android:name="android.service.quicksettings.action.QS_TILE"/></intent-filter>
</service>
<!-- … Tile2Service..Tile5Service identical, labels "AutoHost 2".."AutoHost 5" -->

<!-- Receivers -->
<receiver android:name=".receiver.BootReceiver" android:exported="false">
  <intent-filter>
    <action android:name="android.intent.action.BOOT_COMPLETED"/>
    <action android:name="android.intent.action.MY_PACKAGE_REPLACED"/>
    <action android:name="android.intent.action.TIMEZONE_CHANGED"/>
    <action android:name="android.intent.action.TIME_SET"/>
    <action android:name="android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"/>
  </intent-filter>
</receiver>
<receiver android:name=".receiver.AlarmReceiver" android:exported="false"/>
<receiver android:name=".receiver.NotificationActionReceiver" android:exported="false"/>
<!-- P8-01 (BUILT): notification listener — the system binds it when the user grants notification
     access; posted/removed feed the EventHub, press/dismiss are performed by the listener only -->
<service android:name="app.autohost.capabilities.notifications.AutoHostNotificationListener"
         android:exported="false" android:label="AutoHost notification listener">
  <intent-filter><action android:name="android.service.notification.NotificationListenerService"/></intent-filter>
</service>
<!-- P8-02 (BUILT): low-priority specialUse FGS hosting the DYNAMIC event receivers while a
     receiver/network-callback driven event trigger is armed (plan §11.8 — implicit broadcasts
     cannot be manifest-registered since API 26). Exists only while needed (ADR-057). -->
<service android:name=".events.EventHubService" android:exported="false"
         android:foregroundServiceType="specialUse">
  <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="event_triggers"/>
</service>
<!-- External trigger (P7-10, BUILT): the ONE exported trigger surface — token-checked fail-closed
     in ExternalRunRequest; reject without valid token, log + BAD_TOKEN result -->
<receiver android:name=".surfaces.RunTaskReceiver" android:exported="true">
  <intent-filter><action android:name="app.autohost.RUN_TASK"/></intent-filter>
</receiver>
<!-- Home widget (P7-09, BUILT): Glance receiver -->
<receiver android:name=".surfaces.TaskRunsWidgetReceiver" android:exported="false" android:label="AutoHost tasks">
  <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE"/></intent-filter>
  <meta-data android:name="android.appwidget.provider" android:resource="@xml/task_runs_widget_info"/>
</receiver>

<!-- Activities -->
<activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTask">
  <intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
</activity>
<activity android:name=".importer.ImportActivity" android:exported="true" android:theme="@style/Theme.AutoHost.Transparent">
  <intent-filter>   <!-- open .aatask files -->
    <action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.DEFAULT"/>
    <category android:name="android.intent.category.BROWSABLE"/>
    <data android:scheme="content"/><data android:scheme="file"/>
    <data android:mimeType="application/x-autohost-task"/><data android:mimeType="application/zip"/><data android:mimeType="application/octet-stream"/>
  </intent-filter>
  <intent-filter>   <!-- share sheet -->
    <action android:name="android.intent.action.SEND"/><category android:name="android.intent.category.DEFAULT"/>
    <data android:mimeType="application/zip"/><data android:mimeType="application/octet-stream"/>
  </intent-filter>
</activity>
<activity android:name="app.autohost.capabilities.prompt.PromptActivity" android:exported="false" android:theme="@style/Theme.AutoHost.Transparent" android:excludeFromRecents="true"/>
<activity android:name="app.autohost.capabilities.projection.ProjectionConsentActivity" android:exported="false" android:theme="@android:style/Theme.Translucent.NoTitleBar" android:excludeFromRecents="true"/> <!-- theme deviation: capability modules cannot see Theme.AutoHost.Transparent (ADR-035) -->
<activity android:name="app.autohost.capabilities.screen.ScreenGuardRecoveryActivity" android:exported="false"
          android:showWhenLocked="true" android:turnScreenOn="true" android:excludeFromRecents="true" android:theme="@style/Theme.AutoHost.Transparent"/>
<activity android:name=".PermissionRequestActivity" android:exported="false" android:theme="@style/Theme.AutoHost.Transparent"/>
<!-- P7-08/09 (BUILT): trampoline behind pinned shortcuts + widget Run buttons -->
<activity android:name=".surfaces.SurfaceRunActivity" android:exported="false"
          android:excludeFromRecents="true" android:noHistory="true" android:theme="@android:style/Theme.Translucent.NoTitleBar"/>

<!-- Providers -->
<provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.files" android:exported="false" android:grantUriPermissions="true">
  <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths"/>
</provider>
<!-- Dev API ContentProvider (debug and full+developer only): exported but rejects any caller uid other than shell(2000)/root(0)/own -->
<provider android:name="app.autohost.core.devapi.DevApiProvider" android:authorities="${applicationId}.devapi" android:exported="true"/>
```
`<queries>
        <!-- P11-02 (ADR-079): capability plugins expose a service with this action. -->
        <intent>
            <action android:name="app.autohost.plugin.CAPABILITY_PROVIDER" />
        </intent>`: `<intent><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent>` (documentation; `QUERY_ALL_PACKAGES` supersedes on API 30+).

## 5. XML resources
**`res/xml/accessibility_service_config.xml`** — do **not** add `isAccessibilityTool`.
```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeWindowsChanged|typeViewTextChanged|typeNotificationStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagReportViewIds|flagIncludeNotImportantViews"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:canTakeScreenshot="true" tools:targetApi="30"
    android:canRequestFilterKeyEvents="true"
    android:notificationTimeout="50"
    android:description="@string/a11y_service_description"
    android:settingsActivity="app.autohost.MainActivity"/>
```
No `android:packageNames` (scope is applied dynamically by `A11yScope`). `flagRequestFilterKeyEvents` is switched on **in code** (`setServiceInfo`) only while the volume-key stop is enabled.
**`res/xml/method.xml`** — `<input-method xmlns:android="http://schemas.android.com/apk/res/android" android:settingsActivity="app.autohost.MainActivity"/>`
**`res/xml/network_security_config.xml`** — `<base-config cleartextTrafficPermitted="false"/>` (per-task cleartext opt-in is enforced in `net.http`, not here); debug overlay may allow localhost.
**`res/xml/data_extraction_rules.xml` / `backup_rules.xml`** — exclude all domains.
**`res/xml/file_paths.xml`** — `<files-path name="exports" path="runs/"/>` and `<external-files-path name="exports" path="exports/"/>`.

## 6. Notification channels (`NotificationChannels.kt`)
| Id | Importance | Use |
|---|---|---|
| `runs` | LOW | ongoing run notification (Stop/Pause) |
| `needs_user` | HIGH | `WAITING_USER` prompts |
| `alerts` | DEFAULT | task-posted alerts (`notify.post`) |
| `health` | DEFAULT | health warnings, post-reboot shell re-arm reminder |
| `event_hub` | MIN | event listener FGS |
| `script_default` | DEFAULT | script notifications without a channel |

## 7. Verification checklist (do in P1-01 / P3 / P4 and record in DEVICES.md)
- [ ] App starts in main and each `:wN` process without opening the DB in workers.
- [ ] A service declaring `foregroundServiceType="specialUse"` starts foreground on **API 29** with the two-argument `startForeground`, and with the type on API 34+.
- [ ] `pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS` succeeds (permission declared).
- [ ] `ExternalTriggerReceiver` rejects a missing/invalid token.
- [ ] `core` flavor manifest has no sensitive runtime permission (CI check).
- [ ] Release build has the Dev API provider disabled.

**P4 status note (session 6):** the receivers/services/activities this file lists for Phase 4 are now written in `app/src/main/AndroidManifest.xml` — `BootReceiver` (boot/replace/time/timezone/exact-alarm-perm), `AlarmReceiver`, `ScheduleFireJobService` (BIND_JOB_SERVICE), `ScreenGuardService` (specialUse, `screen_guard` subtype), `ScreenGuardRecoveryActivity` (`showWhenLocked`+`turnScreenOn`). WorkManager's `SystemJobService` merges via the library manifest. Component names in code (`JobBackend`, receivers) must stay in sync with this file; verify the exact-alarm permission states per API in S3/G4-e.

**P5 status note (session 8):** `PromptActivity` (`.prompt.PromptActivity`) is now declared — `exported=false`, `excludeFromRecents`, dialog theme; it sets `FLAG_SECURE` in code (§14.4) and is launched from the `ui.prompt` notification's PendingIntent. The daily `RetentionWorker` needs no manifest entry (WorkManager). All other Phase-5 behaviour (audit sink, incident tracker, checkpoint store, export, history UI) is process-internal. Component names in code (`UiPromptProvider` registration, `PromptActivity.intent`) must stay in sync with this file.


### P8-14 — `.fix.PermissionRequestActivity`
Transparent (`Theme.Translucent.NoTitleBar`), `exported="false"`, `excludeFromRecents`. The single surface behind runtime-permission fixes (`REQUEST_RUNTIME_PERMISSION:<PERM>`) and the SAF folder pick (`PICK_FOLDER` → `ACTION_OPEN_DOCUMENT_TREE` with persistable grants). Outcomes land in `PermissionOutcomes` (process-wide); picked trees persist to `saf.last_tree`. No new permissions are declared by this component — it requests what the provider's availability already named.

### P9-08 — `<instrumentation>` `app.autohost.shell.UiAutomationRunner`
`android:targetPackage="app.autohost"` — the experimental exclusive UiAutomation link (ADR-068). Spawned through the shell tier with `am instrument -e port <p> app.autohost/app.autohost.shell.UiAutomationRunner` (no `-w`: am returns, the runner serves until `{"op":"quit"}` or process death). Never active while the accessibility service is connected (the controller closes the session on any a11y reconnect — G9-e). No new permissions.

### P9-09 — `androidx.core.content.FileProvider` (`app.autohost.fileprovider`)
`exported=false`, `grantUriPermissions=true`, paths in `res/xml/file_paths.xml` (task-data `files/taskdata/`, `files/`, cache `install/`). Serves the APK content URI for `device.app.manage` install mode "installer" (ACTION_VIEW `application/vnd.android.package-archive` + FLAG_GRANT_READ_URI_PERMISSION). The `pm` install path needs no provider.

### P10-02 — `.browser.BrowserActivity`
Declared `exported=false` with `configChanges="orientation|screenSize|keyboardHidden"` (no theme override — a full-screen surface, never a dialog). The visible browser surface for `browser.webview` (ADR-070): dark chrome strip + Close button, back-gesture = close-through-controller. No new permissions (the WebView uses none beyond the app's own INTERNET grant; `task:` pages read only the task bundle). Browser pages carry no background service — the run FGS (§5.2) already covers the active-run window.

### P10 status note (session 17)
No new manifest entries were needed: the full-flavor permission block (contacts/calendar/location/camera/mic/SMS/telephony/media + `READ_LOGS`/`MANAGE_EXTERNAL_STORAGE`) has been declared since the P1 seed and is now actually consumed by the P10 providers — `core` never ships those permissions, which is exactly what `device.sms`/`device.telephony`'s `UNSUPPORTED_FLAVOR` availability reports. The `.browser.BrowserActivity` entry (above) is P10's only new component.
