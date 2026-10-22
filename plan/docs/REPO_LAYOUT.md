# REPO_LAYOUT — file-level layout
Exact module, package and file names. Create files **where and as named**; log an ADR if you deviate. Root package `app.autohost`. Paths abbreviate `src/main/kotlin/app/autohost/<module-package>/` as `…/`.
Legend: **(P#)** = phase that first creates the file. `*.test` = JVM test, `*.android` = instrumented.

## 1. Repo root
```
autohost/
├─ settings.gradle.kts          gradle.properties        gradle/libs.versions.toml
├─ build-logic/                 # convention plugins: autohost.android.library, autohost.android.compose,
│                               #   autohost.kotlin.jvm, autohost.android.app
├─ app/  core/  runtime/  capabilities/  feature/  plugin-sdk/  sdk-python/  testing/
├─ samples/  schemas/  docs/  prompts/  tools/  spikes/(P0 only, deleted at Gate 1)
├─ AGENTS.md  plan.md  THIRD_PARTY_NOTICES.md  README.md
└─ .github/workflows/ (or equivalent CI)  ci.yml: check, lint, licence check, progress --check
```
`settings.gradle.kts` includes: `:app`, `:core:model`, `:core:common`, `:core:data`, `:core:engine`, `:core:broker`, `:core:designsystem`, `:core:devapi`, `:runtime:api`, `:runtime:python`, `:runtime:flow`, `:capabilities:{a11y,ime,app,screen,projection,system,net,notify,files,intent,events,data,shell,vision,browser,prompt,rawbridge}`, `:feature:{home,library,taskdetail,profiles,runs,schedule,triggers,setup,settings,devtools,taskui,surfaces,inspector,recorder}`, `:plugin-sdk`, `:testing:fixture-app`, `:testing:fakes`. Create modules **when their phase starts**, not before.

## 2. core
### `:core:model` — `app.autohost.core.model` (pure Kotlin)
```
capability/ CapabilitySpec.kt PermissionSpec.kt MethodSpec.kt AndroidAccess.kt Availability.kt ReasonCode.kt Tier.kt Risk.kt     (P1)
manifest/   TaskManifest.kt NeedsSpec.kt ExecutionSpec.kt TriggerSpec.kt UiSpec.kt RuntimeSpec.kt ManifestJson.kt ManifestMigration.kt (P1/P2)
selector/   Selector.kt SelectorTerm.kt MatchMode.kt Relation.kt NodeSummary.kt NodeRef.kt UiDump.kt                              (P1/P3)
run/        RunStatus.kt RunReason.kt RunSpec.kt RunResult.kt StageStatus.kt StageRecord.kt TriggerInfo.kt Incident.kt          (P1/P5)
schedule/   ScheduleRule.kt Limits.kt Conditions.kt MisfirePolicy.kt FanOut.kt                                                    (P4)
trigger/    TriggerRule.kt TriggerSource.kt BusyPolicy.kt                                                                          (P4/P8)
flow/       FlowDocument.kt FlowCommand.kt FlowSelector.kt FlowExpr.kt                                                             (P6)
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
orchestrator/ RunOrchestrator.kt RunStateMachine.kt WorkerPool.kt HeartbeatMonitor.kt IdleWatchdog.kt RunRecovery.kt
              CheckpointStore.kt ResourceLocks.kt Preflight.kt
tasks/        TaskInstaller.kt BundleExtractor.kt ManifestValidator.kt ManifestMigrations.kt ImportReport.kt
              CompatResolver.kt ImportScanner.kt(interface) GrantService.kt ScopeTemplating.kt
trigger/      TriggerHub.kt TriggerRuntime.kt TriggerBusyQueue.kt DebounceBuffer.kt TriggerValidator.kt
schedule/     ScheduleEngine.kt SchedulerBackend.kt(interface) ReconcilePolicy.kt MisfireResolver.kt
health/       HealthRegistry.kt
retention/    Retention.kt
```
### `:core:broker` — `app.autohost.core.broker`
```
src/main/aidl/app/autohost/broker/ ICapabilityBroker.aidl ITaskWorker.aidl                                     (P1)
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
worker/ BaseTaskWorkerService.kt TaskWorkerService0.kt … TaskWorkerService3.kt UiHandlerWorkerService.kt WorkerBootstrap.kt WorkerGraph.kt
src/main/python/autohost/…   # copied from /sdk-python/autohost by a Gradle task (single source of truth)
build.gradle.kts             # Chaquopy: python version, pip { install … } per flavor, abiFilters
```
### `:runtime:flow` — `app.autohost.runtime.flow` **(P6)**
`FlowRuntimeProvider.kt` `FlowParser.kt` (YAML/JSON→`FlowDocument`) `FlowInterpreter.kt` `CommandExecutors/*.kt` (one per command) `SelectorResolver.kt` `ExprEvaluator.kt` `VariableScope.kt` `Recorder.kt` `StepSnapshot.kt` `StateMatcher.kt` `GuardedReplay.kt`; tests with `FakeDevice`.

## 4. capabilities (each module: `…/<Name>Provider.kt` + `…/<Name>Availability.kt` + tests)
### `:capabilities:a11y` — `…capabilities.a11y` **(P1 v0; P3 hardened)**
```
AutoHostAccessibilityService.kt   A11yBridge.kt (process-local access to the connected service)
A11yReader.kt (interface)  ReadGate.kt  ReadGatePolicy.kt  TraversalGuard.kt  RootResolver.kt  ForegroundResolver.kt
UiTreeBuilder.kt  NodeRegistry.kt (NodeRef handles, TTL)  SelectorEngine.kt  TextNormalizer.kt  MatchModes.kt
Actions.kt (click chain, set text)  Gestures.kt  GlobalActions.kt  KeyboardState.kt  SettlePolicy.kt  PopupGuard.kt PopupGuardPolicy.kt
CompactDump.kt  ToastListener.kt  KeyFilter.kt (volume-key stop)
AutoAcceptGate.kt AutoAcceptPolicy.kt MediaProjectionAutoAccept.kt (P3)  PackageInstallerAutoAccept.kt (P9)
OverlayHost.kt (bubble, inspector, recorder overlays; P7)
ScreenshotA11yApi30.kt  A11yUiProvider.kt  A11yAvailability.kt
res/xml/accessibility_service_config.xml
```
### `:capabilities:ime` — `AutoHostKeyboardService.kt` `InputConnectionTextEditor.kt` `TextInputResult.kt` `ImeProvider.kt` `ImeSelector.kt` (secure-settings select) `res/xml/method.xml` **(P3)**
### `:capabilities:app` — `AppProvider.kt` `LaunchStrategies.kt` (chain) `LaunchPolicy.kt` `ForegroundDetector.kt` `StopStrategies.kt` `PackageReader.kt` **(P1/P3)**
### `:capabilities:screen` — `ScreenProvider.kt` `ScreenGuardService.kt` `ScreenGuardPolicy.kt` `ScreenGuardRecoveryActivity.kt` `WakeHelper.kt` `KeyguardHelper.kt` **(P3 reads, P4 guard)**
### `:capabilities:projection` — `ProjectionScreenshotProvider.kt` `ProjectionService.kt` (FGS `mediaProjection`) `ProjectionConsentActivity.kt` `ProjectionHolder.kt` `Screenshotter.kt` **(P3)**
### `:capabilities:net` — `HttpProvider.kt` `DomainAllowlist.kt` `WsProvider.kt` `LocalServer.kt` (`net.server`) **(P1 http; P8 rest)**
### `:capabilities:notify` — `NotifyProvider.kt` `NotificationChannels.kt` `AutoHostNotificationListener.kt` `NotificationsProvider.kt` **(P1 post; P8 listen)**
### `:capabilities:prompt` — `PromptProvider.kt` `PromptActivity.kt` (transparent) `PromptModels.kt` **(P5)**
### `:capabilities:events` — `EventHub.kt` `sources/{Battery,Power,Network,Screen,Notification,AppForeground,Toast,Boot}Source.kt` **(P8)**
### `:capabilities:files` `:capabilities:intent` `:capabilities:system` (audio/power/settings) `:capabilities:data` (contacts/calendar/media) **(P8/P10)**
### `:capabilities:shell` — `AdbClient.kt` `AdbProtocol.kt` `AdbKeyStore.kt` `ShellTransport.kt`(+`AdbTcpTransport`,`WirelessPairingTransport`,`ShizukuTransport`,`RootTransport`) `ShellCommands.kt` `ShellProvider.kt` `ShellUiProvider.kt` `ShellScreenshotProvider.kt` `UiAutomationProvider.kt`(experimental) `CommandAllowlist.kt` `SecureSettingsHelper.kt` **(P9)**
### `:capabilities:rawbridge` — `RawBridgeGate.kt` `ImportGuard.kt` (py hook list) **(P8)**; `:capabilities:vision`, `:capabilities:browser` **(P10)**

## 5. feature modules (each: `<X>Screen.kt`, `<X>ViewModel.kt`, `<X>UiState.kt`, `<X>Route.kt`, previews)
`home` (P2), `library` (P1 list→P2 full), `taskdetail` (P2), `profiles` (P2), `runs` (P1 live log→P5 detail/history), `schedule` (P4), `triggers` (P8), `setup` (Health center; P1 v0→P2), `settings` (P2; Developer & security P8), `devtools` (Dev API toggle, grants; P2), `taskui` (P7: `DeclarativeRenderer.kt`, `HtmlUiHost.kt`, `UiBridge.kt`), `surfaces` (P7: tiles, shortcuts, widget, bubble service), `inspector` (P7), `recorder` (P6).
Screen-by-screen design: `docs/UI_SPEC.md`.

## 6. app — `app.autohost` (P1)
```
AutoHostApp.kt (Application: branches on process name — main vs worker; builds AppGraph/WorkerGraph)
AppGraph.kt  WorkerGraph.kt  MainActivity.kt  nav/AppNavHost.kt  nav/Routes.kt
service/RunForegroundService.kt  service/ProjectionHostBridge.kt
receiver/BootReceiver.kt  AlarmReceiver.kt  ExternalTriggerReceiver.kt  NotificationActionReceiver.kt
tile/TaskTileService1.kt … TaskTileService5.kt (P7)   widget/TaskWidget.kt (P7)   shortcut/ShortcutPublisher.kt (P7)
importer/ImportActivity.kt
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
          _generated/  (from CapabilitySpecs — do not edit)
          *.pyi alongside (stubs in this pack)
autohost_cli/  __main__.py client.py(JSON-RPC) commands/{push,run,logs,ui,screenshot,pull_run,doctor,enable_shell,grant_secure,docs_pack,mcp}.py mcp_server.py
autohost_sim/  fake_device.py ui_fixture.py virtual_clock.py sim_transport.py flow_sim.py
tests/ …  tests/mcp-evals/*.yaml
```

## 9. testing, samples, schemas
`testing/fixture-app` (see `docs/FIXTURE_APP.md`), `testing/fakes` (`FakeDevice`, `FakeProvider`, `FakeBroker`, `FakeUiTree`), `testing/fixtures/ui-trees/*.json` (sanitized). `samples/*` = real bundles (this pack ships `hello`, `press-button`, `press-button-flow`, `settings-reader`, `http-check`). `schemas/*` = JSON Schemas validated in CI against samples.

## 10. Generated artifacts (Phase 5; never hand-edit)
`schemas/capabilities.json` ← all `CapabilitySpec`s; `sdk-python/autohost/_generated/*`; `docs/CAPABILITIES.md`; `docs/SDK.md`; in-app catalog data; the **Skill-format agent pack** (`SKILL.md` + `references/`). Gradle task `generateSdkArtifacts`; a test fails on drift.

## 11. Seed files in this pack and where they go
| In the pack | Destination in the repo |
|---|---|
| `core/broker/src/main/aidl/**` | same path |
| `plugin-sdk/src/main/aidl/**` | same path (Phase 11) |
| `sdk-python/autohost/**/*.pyi` | same path |
| `schemas/*`, `samples/*`, `prompts/*`, `tools/progress.py`, `docs/*`, `AGENTS.md`, `plan.md`, `THIRD_PARTY_NOTICES.md` | same path |
