# REPO_LAYOUT — file-level layout
Exact module, package and file names. Create files **where and as named**; log an ADR if you deviate. Root package `app.autohost`. Paths abbreviate `src/main/kotlin/app/autohost/<module-package>/` as `…/`.
Legend: **(P#)** = phase that first creates the file. `*.test` = JVM test, `*.android` = instrumented.

## 1. Repo root
```
autohost/
├─ settings.gradle.kts          gradle.properties        gradle/libs.versions.toml
├─ build.gradle.kts             # root: plugin aliases + central ktlint/detekt block (ADR-030)
├─ .editorconfig                # ktlint style (official Kotlin style, android mode)
├─ config/detekt/detekt.yml     # detekt config, build-upon-default (ADR-030)
├─ build-logic/                 # deferred: convention plugins land here post-verification (ADR-030);
│                               #   autohost.android.library, autohost.android.compose, autohost.kotlin.jvm,
│                               #   autohost.android.app
├─ app/  core/  runtime/  capabilities/  feature/  plugin-sdk/  sdk-python/  testing/
├─ samples/  schemas/  docs/  prompts/  tools/  spikes/(P0 only, deleted at Gate 1)
├─ AGENTS.md  plan.md  HANDOFF.md  THIRD_PARTY_NOTICES.md  README.md
└─ .github/workflows/ (or equivalent CI)  ci.yml: check (tests+ktlint+detekt), APK build, progress --check
```
"settings.gradle.kts" includes (Phase 1-3 modules exist: :core:{model,common,broker,engine,data,designsystem}, :core:devapi, :runtime:{api,python}, :capabilities:{a11y,app,ime,screen,projection}, :app, :testing:fixture-app): `:app`, `:core:model`, `:core:common`, `:core:data`, `:core:engine`, `:core:broker`, `:core:designsystem`, `:core:devapi`, `:runtime:api`, `:runtime:python`, `:runtime:flow`, `:runtime:js`, `:capabilities:{a11y,ime,app,screen,projection,system,net,notify,files,intent,events,data,shell,vision,browser,prompt,rawbridge}`, `:feature:{home,library,taskdetail,profiles,runs,schedule,triggers,setup,settings,devtools,taskui,surfaces,inspector,recorder}`, `:plugin-sdk`, `:testing:fixture-app`, `:testing:fakes`. Create modules **when their phase starts**, not before.

## 2. core
### `:core:model` — `app.autohost.core.model` (pure Kotlin)
```
capability/ CapabilitySpec.kt PermissionSpec.kt MethodSpec.kt AndroidAccess.kt Availability.kt ReasonCode.kt Tier.kt Risk.kt     (P1)
manifest/   TaskManifest.kt NeedsSpec.kt ExecutionSpec.kt TriggerSpec.kt UiSpec.kt RuntimeSpec.kt ManifestJson.kt ManifestMigration.kt (P1/P2; UiSpec folded into TaskManifest.kt, P7)
selector/   Selector.kt SelectorTerm.kt MatchMode.kt Relation.kt NodeSummary.kt NodeRef.kt UiDump.kt                              (P1/P3)
            SelectorBuilder.kt (P7-11: smallest re-finding selector for a node; inspector copy + recorder)
            taskui/DeclarativeUi.kt (P7-01: Tier B document model — see taskui/ below)
run/        RunStatus.kt RunReason.kt RunSpec.kt RunResult.kt StageStatus.kt StageRecord.kt TriggerInfo.kt Incident.kt          (P1/P5)
schedule/   ScheduleRule.kt Limits.kt Conditions.kt MisfirePolicy.kt FanOut.kt CronSpec.kt                                          (P4 written)
trigger/    TriggerRule.kt TriggerSource.kt BusyPolicy.kt                                                                          (P4 written; P8 arms events)
flow/       FlowDocument.kt FlowCommand.kt FlowSelector.kt FlowExpr.kt                                                             (P6)
taskui/     DeclarativeUi.kt (Tier B document model + parser + bindings, P7-01)
error/      AhError.kt ErrorCode.kt                                                                                               (P1)
broker/     Envelope.kt BrokerEvent.kt                                                                                            (P1)
health/     HealthCheck.kt HealthResult.kt FixAction.kt FixOutcome.kt                                                              (P2)
```
### `:core:common` — `app.autohost.core.common`
`Clock.kt` (+ `SystemClockImpl`), `ApiGate.kt`, `AhResult.kt`, `AhLog.kt`, `Ids.kt`, `JsonSetup.kt`, `AppDispatchers.kt`, `ScopedTemp.kt`; `testFixtures/FakeClock.kt`. **(P1)**
### `:core:data` — `app.autohost.core.data`
```
db/AutoHostDatabase.kt  db/Converters.kt
db/entity/ TaskEntity TaskVersionEntity GrantEntity TaskConfigEntity ProfileEntity SecretBlobEntity KvEntity TriggerRuleEntity
           RunEntity StageRecordEntity RunEventEntity EvidenceEntity AuditRecordEntity HealthSnapshotEntity SettingFlagEntity
           FlowRecordingEntity AppPackEntity                                                                  (P2; Run/RunEvent in P1)
db/dao/    <Entity>Dao.kt for each
repo/      TaskRepository ProfileRepository GrantRepository RunRepository TriggerRepository KvRepository
           EvidenceStore SettingsRepository HealthRepository
secrets/   SecretStore.kt (Keystore AEAD) SecretRegistry.kt (values registered with the redactor)
files/     AppPaths.kt (all on-device paths, §7)
schemas/   <db version>.json (exported Room schema)
```
### `:core:engine` — `app.autohost.core.engine` (pure JVM where possible)
```
orchestrator/ RunOrchestrator.kt RunStateMachine.kt WorkerPool.kt HeartbeatMonitor.kt ResourceLocks.kt Preflight.kt
              IdleWatchdogPolicy.kt ResumePolicy.kt IncidentTracker.kt RetentionPolicy.kt RunStore.kt          (P5: watchdog/resume/incidents/retention written;
                                                                                                                WorkerPool/IdleWatchdog/RunRecovery of the old
                                                                                                                sketch were superseded by the orchestrator + P5 files)
export/       RunExporter.kt                                                                                    (P5-09: pure .aarun zip builder)
tasks/        TaskInstaller.kt BundleExtractor.kt ManifestValidator.kt ManifestMigrations.kt ImportReport.kt
              CompatResolver.kt ImportScanner.kt(interface) GrantService.kt ScopeTemplating.kt
trigger/      TriggerHub.kt TriggerRuntime.kt TriggerBusyQueue.kt DebounceBuffer.kt TriggerValidator.kt
schedule/     ScheduleEngine.kt SchedulerBackend.kt(interface) ReconcilePolicy.kt MisfireResolver.kt
              ExactAlarmPolicy(in SchedulerBackend.kt) AlarmBackend.kt WorkBackend.kt JobBackend.kt             (P4 written;
                                                                                                                backends Android-bound,
                                                                                                                ADR-041)
health/       HealthRegistry.kt LazyPermissions.kt (P8-14: capability → Android permission map, pure)
taskui/       UiHandlerPolicy.kt (P7-03: spec build + single-flight gate + outcome parsing + idle policy)
triggers/     EventMatcher.kt TriggerDecisionEngine.kt BusyGate.kt (P8-03: pure guard chain + busy gate)
events/       EventHub.kt TriggerEvent.kt EventSourceCatalog.kt (P8-02: pure hub + source catalog)
surfaces/     TileBindings.kt ExternalRunRequest.kt BubblePolicy.kt (P7-06/07/10: pure surface policies, unit-tested)
host/         SystemProvider.kt StoreProvider.kt StateProvider.kt SecretsProvider.kt CheckpointStore.kt FileKvStore.kt KvBackend.kt
              NotifyProvider.kt (P7-05) EventsProvider.kt (P8-02) SystemSettingsProvider.kt TasksRunProvider.kt (P8-11:
              settings reads + the task-chain runner behind tasks.run — depth/cycle limits pure, start/await via the app seam)
retention/    Retention.kt (DB sweep in :core:data; file-system sweep = orchestrator/RetentionPolicy.kt, P5-08)
```
### `:core:broker` — `app.autohost.core.broker`
```
src/main/aidl/app/autohost/broker/ ICapabilityBroker.aidl ITaskWorker.aidl IUiHandlerWorker.aidl               (P1; IUiHandlerWorker P7-03)
CapabilityProvider.kt CapabilityRegistry.kt ProviderSelector.kt CallContext.kt DeviceEnv.kt                      (P1)
BrokerBinder.kt BrokerPipeline.kt (interceptor chain runner) RunTokenStore.kt                                    (P1)
interceptor/ AuthInterceptor GrantInterceptor ScopeInterceptor InsecureFeatureInterceptor RateLimitInterceptor
             RecorderInterceptor AuditInterceptor                                                              (Auth/Scope/Audit P1; rest P2–P8)
Redactor.kt ScopeMatcher.kt RateLimiter.kt EnvelopeCodec.kt
```
### `:core:designsystem` — `app.autohost.core.designsystem` **(P1 minimal, grows)**
`theme/{Color,Type,Shape,Theme}.kt`, `components/{StatusChip,AvailabilityChip,CapabilityRow,ProfileChip,StageTimeline,LogLine,HealthBanner,EmptyState,SectionHeader,ConfirmDialog,KeyValueRow,ProgressBar}.kt`, `icons/`.
### `:core:devapi` — `app.autohost.core.devapi` **(P2)**
`DevApiServer.kt` (HTTP+WS), `RpcRouter.kt`, `RpcMethods*.kt` (one file per method group), `TokenStore.kt`, `DevApiProvider.kt` (ContentProvider adapter), `DevApiPolicy.kt`.

## 3. runtime
### `:runtime:api` — RuntimeProvider.kt RunHandle.kt RuntimeRegistry.kt **(P1)**
### `:runtime:python` — `app.autohost.runtime.python` **(P1)**
```
PythonRuntimeProvider.kt  PyBridge.kt (Kotlin object called from Python → broker)  ImportScannerPy.kt
worker/ BaseTaskWorkerService.kt TaskWorkerService0.kt … TaskWorkerService3.kt UiHandlerWorkerService.kt (P7-03 real: IUiHandlerWorker, single-flight, 30 s idle self-stop) WorkerBootstrap.kt WorkerGraph.kt (still P1 stubs)
src/main/python/autohost/…   # copied from /sdk-python/autohost by a Gradle task (single source of truth)
build.gradle.kts             # Chaquopy: python version, pip { install … } per flavor, abiFilters
```
### `:runtime:flow` — `app.autohost.runtime.flow` **(P6)**
`FlowRuntimeProvider.kt` (+ `BinderFlowBroker`, `RunControls`, `InProcessRunHandle` in the same file; heartbeats per ADR-054) `FlowSources.kt` (entry resolution + subflows; `YamlParserPlug.kt` per ADR-052) `FlowParser.kt` (JSON now, YAML plug per ADR-052 →`FlowDocument`) `FlowInterpreter.kt` (incl. the `FlowExec.stepGuard` seam, ADR-053) `CommandExecutors.kt` (one dispatch table for all 29 commands — ADR-051) `SelectorResolver.kt` `ExprEvaluator.kt` `VariableScope.kt` `JsonValues.kt` `Recorder.kt` (P6-07) `StepSnapshot.kt` (snapshot model + `RecordingSidecar`, ADR-053) `StateMatcher.kt` `GuardedReplay.kt` (P6-08); types in `:core:model` (`FlowDocument.kt`); tests: unit + the golden replay of `testing/golden/flow_vectors.json` (VectorDevice mirrors the simulator) + `FlowRuntimeProviderTest` + `RecorderTest` + `GuardedReplayTest`.

### `:runtime:js` — `app.autohost.runtime.js` **(P11-01/02 — ADR-076)**
`JsRuntimeProvider.kt` (+ `JsInProcessRunHandle` in the same file; dedicated engine thread + heartbeats, classification = the JS mirror of `execute_with_runtime`) `JsBridge.kt` (the op-table host + `JsBrokerCaller`/`JsRunInfo`/`JsRunControls` (injectable monotonic) + `IsoFormat`) `JsEngine.kt` (the pure seam + `JsEngineFactory`) `JsSources.kt` (entry resolution + the containment-checked `./lib/*.js` module loader) `JsRunPolicy.kt` (pure caps: 32/16 MB heap, 1 MB stack, deadline) `JsPrelude.kt` (resource loader); `src/main/resources/autohost/js/prelude.js` (the JS v1 surface — single source, the sim keeps a byte-identical copy); tests: `JsSourcesTest` `JsRunPolicyTest` `JsBridgeTest` `JsOutcomeTest` `JsRuntimeProviderTest` (scripted fake engine). The QuickJS binding lives ONLY in `app/runtimejs/QuickJsEngineFactory.kt` (owner-verified first-build touchpoint). Surface doc: `docs/SDK_JS.md`.

## 4. capabilities (each module: `…/<Name>Provider.kt` + `…/<Name>Availability.kt` + tests)
### `:capabilities:a11y` — `…capabilities.a11y` **(P1 v0; P3 hardened)**
```
AutoHostAccessibilityService.kt   A11yBridge.kt (process-local access to the connected service)
A11yReader.kt (interface)  ReadGate.kt  ReadGatePolicy.kt  TraversalGuard.kt  RootResolver.kt  ForegroundResolver.kt
UiTreeBuilder.kt  NodeRegistry.kt (NodeRef handles, TTL)  SelectorEngine.kt  TextNormalizer.kt  MatchModes.kt
Actions.kt (click chain, set text)  Gestures.kt  GlobalActions.kt  KeyboardState.kt  SettlePolicy.kt  PopupGuard.kt PopupGuardPolicy.kt
CompactDump.kt  ToastListener.kt  KeyFilter.kt (volume-key stop)
AutoAcceptGate.kt AutoAcceptPolicy.kt MediaProjectionAutoAccept.kt (P3)  PackageInstallerAutoAccept.kt (P9)
RecorderTap.kt (P6-09: armed-only interaction-event tap → A11yBridge.recorderEvents)  VolumeKeyHoldDetector.kt (P7-12: hold-to-stop state machine; service onKeyEvent filter)
OverlayHost.kt (bubble, inspector overlays; P7 — the recorder overlay itself lives in :app)
ScreenshotA11yApi30.kt  A11yUiProvider.kt  A11yAvailability.kt
res/xml/accessibility_service_config.xml
```
### `:core:engine` (P11 additions) — `plugins/PluginHost.kt` (+`PluginProvider`, ADR-079) `tasks/AppPacks.kt` (ADR-080) `tasks/StudioDraft.kt` (ADR-083) `backup/BackupCodec.kt` (ADR-082) `tasks/RepoIndex.kt` (ADR-078) `tasks/UpgradeMigrationTest.kt` **(P11 written)**
### `:core:devapi` (P11 additions) — `WsFrames.kt` (RFC 6455 codec both roles) + `ReverseDevApiLink.kt` (the phone-side reverse transport, ADR-081) **(P11-07 written)**
### `:capabilities:ime` — `AutoHostKeyboardService.kt` `InputConnectionTextEditor.kt` `TextInputResult.kt` `ImeProvider.kt` `ImeSelector.kt` (secure-settings select) `ImeBridge.kt` (process-local link; extra file vs the list — ADR-034) `res/xml/method.xml` **(P3 written)**
### `:capabilities:app` — `AppProvider.kt` `StopStrategies.kt` (P3-20) `PackageReader.kt`; `BackgroundKiller`/`AppStopUi` interfaces live in AppProvider.kt (chain wiring in AppGraph, ADR-037); LaunchStrategies/LaunchPolicy/ForegroundDetector folded into AppProvider.kt in P1 **(P1/P3 written)**
### `:capabilities:screen` — `ScreenProvider.kt` `ScreenGuardService.kt` `ScreenGuardPolicy.kt` `ScreenGuardRecoveryActivity.kt` `WakeHelper.kt` `KeyguardHelper.kt` **(P3 reads, P4 guard written)**
### `:capabilities:projection` — `ProjectionScreenshotProvider.kt` `ProjectionService.kt` (FGS `mediaProjection`) `ProjectionConsentActivity.kt` `ProjectionHolder.kt` `Screenshotter.kt` **(P3 written; a11y link injected as lambdas, ADR-035)**
### `:capabilities:net` — `NetPolicy.kt` (domain allowlist + scheme rules + header redaction) `HttpProvider.kt` (+`JavaHttpEngine`) `WsClientProvider.kt` (+`JvmWsSocketFactory`, RFC 6455 client) `WebhookServer.kt` (+`NetServerProvider`) **(P8-08 written; the W1 net.http provider landed here as a first-build gap close — the spec+SDK existed since P1 but no host provider was registered)**
### `:capabilities:files` — `SharedFilesPolicy.kt` (tree scope + relative-path guard) `FilesSharedProvider.kt` (SAF + MediaStore) `AndroidSharedFiles.kt` `FilesPrivateProvider.kt` (host-tier task-private storage; W1 first-build gap close) **(P8-07 written; module name `files` per the plan sketch)**
### `:capabilities:audio` — `AudioProvider.kt` (volume/ringer/DND/TTS/vibrate/torch/tone) `AndroidAudio.kt` `PowerProvider.kt` (battery/task-scoped wake lock/keep-awake) `AndroidPower.kt` **(P8-09 written; device.audio + device.power share the module)**
### `:capabilities:notify` — `NotifyProvider.kt` (P7-05, lives in `:core:engine/host`) `NotificationChannels.kt` **(P1/P7 written)**
### `:capabilities:notifications` — `AutoHostNotificationListener.kt` `NotificationBridge.kt` (process-local snapshot + posted/removed flow) `NotificationsProvider.kt` `NotificationAccessPolicy.kt` **(P8-01 written; ADR-060 — the module name is `notifications`, not `notify`, to keep the post/listen halves distinct)**
### `:capabilities:prompt` — `PromptProvider.kt` `PromptActivity.kt` (transparent) `PromptModels.kt` **(P5 written — lives in `:app/prompt`, ADR-055 deviation)**
### `:capabilities:clipboard` — `ClipboardPolicy.kt` `ClipboardProvider.kt` `AndroidClipboard.kt` **(P8-05 written)**
### `:capabilities:intents` — `IntentPolicy.kt` `IntentProvider.kt` `AndroidIntents.kt` **(P8-06 written; module name `intents` vs the sketched `intent`)**
### `:capabilities:events` — the **pure hub** lives in `:core:engine/events/{EventHub,TriggerEvent,EventSourceCatalog}.kt` + the provider in `:core:engine/host/EventsProvider.kt` (P8-02 deviation from the sketched `:capabilities:events`: the hub needs no Android module — Android sources are `app/events/{AndroidEventSources,EventHubController,EventHubService}.kt`)
### `:capabilities:shell` — `AdbProtocol.kt` `AdbKeyStore.kt` `AdbClient.kt` `ShellTransport.kt`(+`AdbTcpTransport`,`WirelessPairingTransport`,`ShizukuTransport`,`RootTransport`, the `ShellTransports` ordered holder) `WirelessPairing.kt` (SPAKE2 RFC 9382 core) `CommandAllowlist.kt` `ShellCommands.kt` `ShellProvider.kt` `ShellUiProvider.kt`(+`UiautomatorDump.kt`) `ShellScreenshotProvider.kt` **(P9 part 1: P9-01..07 — ADR-064/065/066)**; part 2 (P9-08..10 — ADR-067/068): `UiAutomationExclusivity.kt` `UiAutomationSessionClient.kt` `UiAutomationProvider.kt` (the experimental exclusive link), `LogcatOutput.kt`+`LogcatProvider.kt` (device.logcat), `DeviceControlProviders.kt` (`RadioProvider`/`DisplayProvider`/`MockLocationProvider`); `ShellCommands.kt` grew the manage/logcat/radio/display/settings-put builders (same quoting discipline). The Shizuku-backed Exec lives in `app/shell/ShizukuProcessExec.kt` (ADR-064 isolation) — the module itself carries no Shizuku dependency
### :capabilities:rawbridge` — `RawBridgeProvider.kt` (the `authorize` handshake; grant + raw_api switch enforced upstream in the interceptor chain, ADR-062) **(P8-12 written)**; the import guard's AST half is `sdk-python/autohost/_scan.py::raw_bridge_calls`, the pipeline half is `ImportPipeline(rawBridgeGuard=…)`
### `:capabilities:browser` — `…capabilities.browser` **(P10 part 1: P10-02..04 — ADR-070/071)**
### `:capabilities:data` — `…capabilities.data` **(P10 part 2: P10-06..08 — ADR-073/074)**
### `:plugin-sdk` — `ICapabilityProvider.aidl` (the discovery contract, ADR-079) `PluginCapabilityService.kt` (dependency-free base: declare()/envelope helpers; the host parses and gates EVERYTHING) **(P11-02 written)**; external plugin projects include this module — see `samples/plugin-example`
`DataPolicy.kt` (pure: row caps, 31-day windows, byte caps, dialable-destination rails — premium/short-code refusals, SMS body/part caps, per-run send budget), `PersonalDataProviders.kt` (`PersonalDataStores` seams + Contacts/Calendar/Media providers + `LazyPermissionProvider` base), `HardwareProviders.kt` (`HardwareSeams` + Location with the honest BACKGROUND_LOCATION gate / one-shot Camera / bounded Mic / permissionless one-shot Sensors), `SmsTelephonyProviders.kt` (SmsPolicy + the `full`-flavor-gated Sms/Telephony providers — `delivered:false` and `verified:false` always). Tests: `DataClusterTest.kt` (policy + provider shapes over fakes)
`BrowserPolicy.kt` (pure gate chain: NetPolicy-mirroring domain allowlist, http/https + `task:` bundle paths only, open/read permission split), `ChallengeDetector.kt` (pure challenge engine — captcha/anti-bot/rate-limit/denied markers; login pages are NOT challenges), `WebPage.kt` (page/action/extract/cookie models), `WebViewBrowserProvider.kt` (T0 default link — open/navigate/click/type/extract/cookies/clear_cookies/pages/close over the `Host` seam; `input_mode` honesty), `A11yBrowserProvider.kt` (T1 user-browser link — text clicks, focused-field typing, visible-text extract; cookies refused `COOKIES_UNSUPPORTED_LINK`), `CdpClient.kt` (pure CDP: id-correlated JSON over the `Transport`/`Connection` seams), `CdpBrowserProvider.kt` (T3 draft — `adb forward` through the shell tier, `status` reports `verified_on_hardware:false` until S27). Tests: `BrowserModuleTest.kt` (policy + detector + provider shapes over fakes)
### `:capabilities:vision` — `…capabilities.vision` **(P10 part 1: P10-05 — ADR-072)**
`VisionPolicy.kt` (pure region/threshold/stride validation), `TemplateMatcher.kt` (pure zero-mean NCC, coarse stride + refine, thread-safe), `OcrEngine.kt` (+`MlKitOcrEngine.kt` — the ONLY ML Kit touchpoint), `QrDecoder.kt` (+`ZxingQrDecoder.kt` — the ONLY ZXing touchpoint), `VisionProvider.kt` (`ocr`/`template_match`/`qr_decode` over the `Images` seam; `hint:true` on every result). Tests: `VisionModuleTest.kt` (policy + golden vectors + provider shapes)

## 5. feature modules (each: `<X>Screen.kt`, `<X>ViewModel.kt`, `<X>UiState.kt`, `<X>Route.kt`, previews)
`home` (P2), `library` (P1 list→P2 full), `taskdetail` (P2), `profiles` (P2), `runs` (P1 live log→P5 detail/history), `schedule` (P4), `triggers` (P8), `setup` (Health center; P1 v0→P2), `settings` (P2; Developer & security P8), `devtools` (Dev API toggle, grants; P2), `taskui` (P7), `surfaces` (P7), `inspector` (P7), `recorder` (P6).
**ADR-055 deviation:** the `:feature:` split has not happened — screens and controllers live in `:app` (as they have since P5). Task-UI files: `app/taskui/{UiHandlerController,TaskUiController}.kt`, `app/ui/taskui/{DeclarativeRenderer,HtmlUiHost}.kt`; the pure Tier B document model is in `:core:model` (`taskui/DeclarativeUi.kt`) and the handler policy in `:core:engine` (`taskui/UiHandlerPolicy.kt`). Re-evaluate the split at Phase 11 with a working build.
**P8 additions:** `app/fix/{FixHub,PermissionRequestActivity}.kt` (P8-14 fix-action routing + the lazy permission flow), `app/webhook/WebhookController.kt` (P8-08 endpoint lifecycle + S23), `app/surfaces/TaskOverlaySink.kt` (P8-10 a11y overlay sink behind `ui.overlay`).
**P10 additions (part 2):** `app/data/AndroidStores.kt` (ContentResolver-backed contacts/calendar/MediaStore seams), `app/data/AndroidHardware.kt` (LocationManager one-shot, legacy Camera one-shot, MediaRecorder bounded, SensorManager one-shot latch), `app/data/AndroidTelephony.kt` (SmsManager multipart-capped sends, TelephonyManager state, ACTION_CALL); AppGraph registers the 9 data/sms providers + `socketProvider`/`reverseWsProvider`, extends the run-terminal collector with socket/reverse handle teardown + the SMS send-budget clear, and `refreshDataPermissionState()` refreshes the lazy-permission truth. `:capabilities:net` grew `SocketProviders.kt` (`SocketProvider` TCP/UDP + `ReverseWsProvider`) and `:core:broker`'s InsecureFeatures gained `net_reverse`.
**P10 additions (part 1):** `app/browser/BrowserController.kt` (page registry, visible-activity launches, profile lock, `task:` bundle resolution, run-terminal closeAll), `app/browser/BrowserActivity.kt` (the visible WebView surface — real MotionEvent/key-event injection, JS fallback flagged synthetic), `app/browser/A11yBrowserLink.kt` (binds the a11y reader/actions to the browser seam), `app/browser/CdpTransportFactory.kt` (devtools /json/list + RFC 6455 client codec over the P8-08 socket factory; `VisionImages` — screenshotter-backed, run-scoped file containment); AppGraph registers the browser chain (webview → a11y → cdp) + `visionProvider`, extends the run-terminal collector with `browserController.closeAll` + `cdpBrowserProvider.closeForRun`, and keeps `visionImages.activeRunId` on the newest active run.
**P11 additions (part 1):** `app/runtimejs/QuickJsEngineFactory.kt` (P11-02, ADR-076 — the ONLY file importing the QuickJS binding; runEntry/prelude→module→main contract, interrupt watcher, owner-verified first build); AppGraph constructs `jsRuntime` (JsBrokerCaller adapter over the binder, fail-closed secrets, shared Redactor, low-RAM cap input) and registers it after `flowRuntime`; `jsRuntime.setDeathListener` maps engine-internal crashes to INTERRUPTED.
**P9 additions:** `app/shell/ShizukuProcessExec.kt` (P9-04, the only Shizuku-API touchpoint); part 2: `app/shell/UiAutomationRunner.kt` (the Instrumentation runner, manifest `<instrumentation>`), `app/shell/UiAutomationController.kt` (session lifecycle + exclusivity), `app/shell/SecureSettingsHelper.kt` (ADR-067 one write door), `app/selfheal/ShellSelfHeal.kt` (shell-fallback heal + IME auto-select + keep-awake), `app/shell/RearmReminder.kt` (P9-12 boot reminder), `app/install/InstallController.kt` (installer-UI path + auto-accept) and `res/xml/file_paths.xml` (FileProvider). AppGraph constructs the `ShellTransports` chain and registers `uiAutomationProvider` between `a11yProvider` and `shellUiProvider`; `appManageProvider`/`logcatProvider`/`radioProvider`/`displayProvider`/`mockLocationProvider` join the shell block; `settingsProvider` gains the ADR-067 writer seam.
**P11 additions (part 1):** `core/engine` gains `tasks/RepoIndex.kt` (ADR-078 — pure index.json v1 parse + select verdicts + sha verify); `:runtime:js` joins the build (see §3) with `settings.gradle.kts` including it and `libs.versions.toml` gaining `quickjs-kt-android 1.0.3`.
Screen-by-screen design: `docs/UI_SPEC.md`.

## 6. app — `app.autohost` (P1)
```
AutoHostApp.kt (Application: branches on process name — main vs worker; builds AppGraph/WorkerGraph)
AppGraph.kt  WorkerGraph.kt  MainActivity.kt  nav/AppNavHost.kt  nav/Routes.kt
service/RunForegroundService.kt  service/ProjectionHostBridge.kt
receiver/BootReceiver.kt  AlarmReceiver.kt  ExternalTriggerReceiver.kt  NotificationActionReceiver.kt
schedule/ScheduleController.kt (P4: arming/fire/reconcile/next_run glue — ADR-041/043)  schedule/TriggerController.kt (P8-03: trigger-rules runtime — guards, busy gates, time arming, external/chain fire; ADR-058)
events/AndroidEventSources.kt  events/EventHubController.kt  events/EventHubService.kt (P8-02: dynamic receivers + the low-priority FGS, ADR-057)
security/InsecureSwitchStore.kt (P8-13: volatile switch snapshot + app_settings persistence + audit)
selfheal/SecureSettingsSelfHeal.kt (P4-14)   service/ScheduleFireWorker.kt ScheduleFireJobService.kt ReconcileWorker.kt (P4-05/06)
bridge/RunPersistenceBridge.kt CrashReconciler.kt (P5-01/05/18: runStore→Room mirror; orphan recovery + bounded auto-resume)
export/AppRunExporter.kt (P5-09: gathers stores → RunExporter zip, redacted)
service/RetentionWorker.kt (P5-08: daily DB + run-dir sweep)
recorder/RecorderController.kt RecorderOverlay.kt (P6-09: a11y tap → Recorder → flow.json + sidecar; TYPE_ACCESSIBILITY_OVERLAY strip)
taskui/UiHandlerController.kt TaskUiController.kt (P7-01/03: handler dispatch + Tier B/C state & actions; ADR-055)
notify/AndroidNotificationSink.kt (P7-05: notify.post → alerts channel; actions → NotificationActionReceiver)
prompt/UiPromptProvider.kt PromptActivity.kt UiTextOverlay.kt (P5-06 ask/toast; P7-04 dialog/form/overlay — provider registered since P7)
ui/taskui/DeclarativeRenderer.kt HtmlUiHost.kt (P7-01/02: Tier B renderer, Tier C sandboxed WebView host)
ui/RunScreen.kt (P5-10: timeline/incidents/audit/pause/resume/export)  ui/RunsHistoryScreen.kt (P5-18)  ui/RecorderReviewScreen.kt (P6-09: steps review, coordinate confirmation gate G6-e)  ui/TaskScreens.kt (+ Flow tab P6-10, + UI tab P7-01/02, + pin-shortcut row P7-08)  …
surfaces/ (P7-06..12, ADR-056 — the plan's tile/widget/shortcut directories collapsed into one package):
  SurfacesPrefs.kt (SharedPreferences store: bubble mode, tile bindings, external token, key filter, widget choice)
  SurfaceRuns.kt (the one run-start path for tiles/shortcuts/widget/intent; trigger surface|external + source payload; widget refresh throttle)
  BubbleOverlay.kt BubbleController.kt (P7-06: draggable a11y overlay strip — status/Open/Stop; BubblePolicy-driven visibility)
  AutoHostTileService.kt (+ Tile1..5Service) (P7-07: bound run on click; unbound click opens Surfaces)
  Shortcuts.kt SurfaceRunActivity.kt (P7-08: requestPinShortcut + dynamic sync; translucent trampoline → run)
  TaskRunsWidget.kt (P7-09: Glance; rows = task + last Room status + Run button; receiver TaskRunsWidgetReceiver)
  RunTaskReceiver.kt (P7-10: exported token-guarded RUN_TASK)
  InspectorOverlay.kt InspectorController.kt (P7-11: bounds canvas, tap → SelectorBuilder → clipboard; live foreground scope)
ui/SurfacesScreen.kt (P7-06..12: bubble mode, tile bindings + API33 add-tile, widget picker, external token, inspector, key filter, pin shortcuts)
importer/ImportActivity.kt
plugins/PluginController.kt (P11-02, ADR-079: query/bind/attach capability plugins; fragment → AppGraph.attachPluginPermissions)
packs/PackController.kt (P11-04, ADR-080: .aapack install/list/remove; injectPacks → config.packs at run start)
backup/BackupController.kt (P11-08, ADR-082: SAF export/restore through BackupCodec + the standard import path; source "backup")
devapi/ReverseLinkController.kt (P11-07, ADR-081: the dial loop + honest state; Settings S26a)
runtimejs/QuickJsEngineFactory.kt (P11-01: the ONLY QuickJS touchpoint, owner-verified first build)
ui/studio/TaskStudioScreen.kt  ui/studio/StudioController.kt (P11-05, ADR-083: dev-folder drafts + the repo tab; Library → Studio)
src/core/AndroidManifest.xml   src/full/AndroidManifest.xml   src/debug/… DebugBridgeReceiver.kt FixtureHooks.kt
res/…  (strings.xml, themes, xml/{backup rules, network_security_config, file_paths, shortcuts}.xml)
```
Manifest contents: `docs/ANDROID_MANIFEST.md`.

## 7. On-device filesystem (`AppPaths.kt` is the only place that builds these)
```
filesDir/tasks/<taskId>/<version>/…            # extracted bundle, made read-only
filesDir/runs/<runId>/{evidence/,tmp/,export/}  # evidence images, compact dumps, temp, export staging
filesDir/keys/                                  # Keystore-wrapped ADB key blobs
cacheDir/imports/                               # temporary downloads/zips
getExternalFilesDir("dev")/<taskId>/…           # dev folder (adb push target)  /sdcard/Android/data/<id>/files/dev/
getExternalFilesDir("exports")/                 # .aarun bundles for sharing
```

## 8. Python SDK, CLI, simulator — `sdk-python/`
```
pyproject.toml                                  # name autohost-sdk, stdlib only; extras: dev (pytest, mypy, ruff)
autohost/ __init__.py  _runtime.py (entry: run(run_spec_json))  _transport.py (BrokerTransport | SimTransport)  _envelope.py
          ctx.py log.py errors.py sel.py flow.py ui.py notify.py http.py files.py store.py android.py(gated)
          device/{__init__,app,ui,screen,ime,notifications,clipboard,intent,events,audio,power,settings,shell}.py
          packs.py (P11-04: pack/selector/screen/label/tips over config.packs; PackMissing/SelectorMissing)
          _generated/  (from CapabilitySpecs — do not edit)
          *.pyi alongside (stubs in this pack)
autohost_cli/  __main__.py client.py(JSON-RPC) commands/{push,run,logs,ui,screenshot,pull_run,doctor,enable_shell,grant_secure,docs_pack,mcp}.py mcp_server.py
               webui.py (P11-06: the token-safe inspector proxy + page)  bridge.py (P11-07: the reverse listener + localhost HTTP proxy)  repo.py (P11-03: the mirrored index rules + https/file fetch + sha-verified install)
autohost_sim/  fake_device.py ui_fixture.py virtual_clock.py sim_transport.py flow_sim.py
autohost_sim/  js_host.py (P11-03, ADR-077 — run_js_task: node shim transport + the Python op server + classification parity)  js/node_shim.cjs (dumb stdio transport; ESM via require-of-ESM)  js/prelude.js (byte-identical copy of the runtime resource — drift-tested)
tests/ …  tests/mcp-evals/*.yaml
```

## 9. testing, samples, schemas
`testing/fixture-app` (see `docs/FIXTURE_APP.md`), `testing/fakes` (`FakeDevice`, `FakeProvider`, `FakeBroker`, `FakeUiTree`), `testing/fixtures/ui-trees/*.json` (sanitized). `samples/*` = real bundles (this pack ships `hello`, `press-button`, `press-button-flow`, `settings-reader`, `http-check`, and from P11 `js-press-button` (the third-runtime archetype), `pack-presser` (ADR-080 app packs), `photo-organizer` (the P11-12 port exercise) plus `plugin-example` (a STANDALONE plugin APK project, ADR-079)). `schemas/*` = JSON Schemas validated in CI against samples.

## 10. Generated artifacts (Phase 5; never hand-edit)
`schemas/capabilities.json` ← all `CapabilitySpec`s; `sdk-python/autohost/_generated/*`; `docs/CAPABILITIES.md`; `docs/SDK.md`; in-app catalog data; the **Skill-format agent pack** (`SKILL.md` + `references/`). Gradle task `generateSdkArtifacts`; a test fails on drift.

## 11. Seed files in this pack and where they go
| In the pack | Destination in the repo |
|---|---|
| `core/broker/src/main/aidl/**` | same path |
| `plugin-sdk/src/main/aidl/**` | same path (Phase 11) |
| `sdk-python/autohost/**/*.pyi` | same path |
| `schemas/*`, `samples/*`, `prompts/*`, `tools/progress.py`, `docs/*`, `AGENTS.md`, `plan.md`, `THIRD_PARTY_NOTICES.md` | same path |
