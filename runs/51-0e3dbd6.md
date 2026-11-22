# CI digest - run 51 - branch work/s02-green - commit 0e3dbd6
tests step outcome: failure

## Verdict: RED - 1450 tests ran, 0 failed, 0 Kotlin compile errors, 1 failed Gradle tasks, 4 modules with no results

## Failed Gradle tasks
- :app:testCoreLiteDebugUnitTest

## A task TIMED OUT - the last tests that started (the last one is the hung test)
- MacroEditorJourneyTest > the step menu adds a step that needs no app STARTED
- MacroEditorJourneyTest > the editor opens from the Build tab and shows its sections STARTED
- ReleaseHardeningTest > backup is off (the checklist row + AGENTS rule — no backups of run data) STARTED
- ReleaseHardeningTest > the NSC denies cleartext everywhere except the loopback hosts STARTED
- ReleaseHardeningTest > cleartext is off with the NSC attached (the W6-4 and X-14 fix) STARTED
- ReleaseHardeningTest > the insecure switches default to OFF with deny-wins (the switches row) STARTED
- ReleaseHardeningTest > the Dev API is OFF and loopback-bound by default (the first two checklist rows) STARTED
- SettingsWalkJourneyTest > every Settings screen opens and Back returns to the Settings list STARTED
- SettingsWalkJourneyTest > AI settings opened from the Agent tab go back to the chat STARTED
- SettingsWalkJourneyTest > on the first screen the phone's Back button does leave the app STARTED

## Where the test JVM was stuck (its last thread dump, kept at most a minute before the end)
    test JVM 3961: 42 threads
    "Test worker" #1 prio=5 os_prio=0 cpu=730.22ms elapsed=424.61s tid=0x0000773584034d50 nid=0xf7b waiting on condition [0x0000773588b1b000]
    "SDK 35 Main Thread @kotlinx.coroutines.test runner#9079" #24 prio=5 os_prio=0 cpu=15485.99ms elapsed=420.92s tid=0x0000773584c09bf0 nid=0xfd1 waiting on condition [0x00007735584f7000]
    java.lang.Thread.State: TIMED_WAITING (parking)
    at jdk.internal.misc.Unsafe.park(java.base@17.0.21/Native Method)
    - parking to wait for <0x000000008c973620> (a kotlinx.coroutines.BlockingCoroutine)
    at java.util.concurrent.locks.LockSupport.parkNanos(java.base@17.0.21/LockSupport.java:252)
    at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:97)
    at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:70)
    at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
    at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
    at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    at app.autohost.SettingsWalkJourneyTest.asReturningPerson(SettingsWalkJourneyTest.kt:80)
    at app.autohost.SettingsWalkJourneyTest.on the first screen the phone's Back button does leave the app(SettingsWalkJourneyTest.kt:110)
    at jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(java.base@17.0.21/Native Method)
    at jdk.internal.reflect.NativeMethodAccessorImpl.invoke(java.base@17.0.21/NativeMethodAccessorImpl.java:77)
    (about a minute earlier: cpu 15486 -> 15486 ms, same top frames)
    "DefaultDispatcher-worker-2 @coroutine#9075" #27 daemon prio=5 os_prio=0 cpu=129.11ms elapsed=417.21s tid=0x00007734a1d59c10 nid=0xfd6 waiting on condition [0x00007735582f9000]
    java.lang.Thread.State: TIMED_WAITING (parking)
    at jdk.internal.misc.Unsafe.park(java.base@17.0.21/Native Method)
    - parking to wait for <0x000000008caec0b0> (a kotlinx.coroutines.BlockingCoroutine)
    at java.util.concurrent.locks.LockSupport.parkNanos(java.base@17.0.21/LockSupport.java:252)
    at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:97)
    at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:70)
    at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
    at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
    at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    at app.autohost.webhook.WebhookController.enabled(WebhookController.kt:32)
    at app.autohost.webhook.WebhookController$reconcile$1.invokeSuspend(WebhookController.kt:48)
    at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:34)
    at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:100)
    (about a minute earlier: cpu 129 -> 129 ms, same top frames)
    "arch_disk_io_0 @coroutine#7162" #31 daemon prio=5 os_prio=0 cpu=456.72ms elapsed=416.68s tid=0x00007734a20941b0 nid=0xfda waiting on condition [0x00007735043fd000]
    java.lang.Thread.State: TIMED_WAITING (parking)
    at jdk.internal.misc.Unsafe.park(java.base@17.0.21/Native Method)
    - parking to wait for <0x000000008b800068> (a kotlinx.coroutines.BlockingCoroutine)
    at java.util.concurrent.locks.LockSupport.parkNanos(java.base@17.0.21/LockSupport.java:252)
    at kotlinx.coroutines.BlockingCoroutine.joinBlocking(Builders.kt:97)
    at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking(Builders.kt:70)
    at kotlinx.coroutines.BuildersKt.runBlocking(Unknown Source)
    at kotlinx.coroutines.BuildersKt__BuildersKt.runBlocking$default(Builders.kt:48)
    at kotlinx.coroutines.BuildersKt.runBlocking$default(Unknown Source)
    at app.autohost.ai.AppProfileStore.save(AppProfileStore.kt:29)
    at app.autohost.ui.AiSettingsScreenKt$AiSettingsHost$2$1.invokeSuspend(AiSettingsScreen.kt:76)
    at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:34)
    at androidx.compose.ui.test.ApplyingContinuationInterceptor$SendApplyContinuation.resumeWith(ApplyingContinuationInterceptor.kt:52)
    (about a minute earlier: cpu 457 -> 457 ms, same top frames)
    garbage-first heap total 241664K, used 190253K [0x0000000080000000, 0x0000000100000000)

## Modules that produced no test results (compile error, timeout, or no tests)
- runtime/api
- runtime/python
- plugin-sdk
- testing/fixture-app

## Per module (tests / failed / skipped)
- app: 231 / 0 / 0
- capabilities/a11y: 114 / 0 / 0
- capabilities/app: 32 / 0 / 0
- capabilities/audio: 8 / 0 / 0
- capabilities/browser: 30 / 0 / 0
- capabilities/clipboard: 4 / 0 / 0
- capabilities/data: 18 / 0 / 0
- capabilities/files: 12 / 0 / 0
- capabilities/ime: 3 / 0 / 0
- capabilities/intents: 7 / 0 / 0
- capabilities/net: 29 / 0 / 0
- capabilities/notifications: 8 / 0 / 0
- capabilities/projection: 4 / 0 / 0
- capabilities/rawbridge: 2 / 0 / 0
- capabilities/screen: 22 / 0 / 0
- capabilities/shell: 65 / 0 / 0
- capabilities/vision: 15 / 0 / 4
- core/broker: 42 / 0 / 0
- core/common: 21 / 0 / 0
- core/data: 5 / 0 / 0
- core/designsystem: 6 / 0 / 0
- core/devapi: 26 / 0 / 2
- core/engine: 299 / 0 / 2
- core/llm: 36 / 0 / 0
- core/model: 47 / 0 / 0
- runtime/agent: 206 / 0 / 0
- runtime/flow: 117 / 0 / 24
- runtime/js: 41 / 0 / 0

## Where the time went (finished tasks only; a task stopped by the timeout leaves no timings)
- core/llm: 32 s of test time
- capabilities/browser: 20 s of test time
- capabilities/shell: 4 s of test time
- core/model: 3 s of test time
- core/engine: 2 s of test time
- slowest: 30.0 s HttpUrlConnectionEngineTest.given a hung server when the call is cancelled then it returns within about a second [core/llm]
- slowest: 10.2 s CdpBrowserProviderTest.navigate forwards through the shell then evaluates the page [capabilities/browser]
- slowest: 10.0 s CdpBrowserProviderTest.close removes the forward [capabilities/browser]
- slowest: 2.0 s SelectorBenchTest.find latency stays in single-digit milliseconds on large trees [core/model]
- slowest: 1.5 s UiAutomationControllerTest.spawn failure with a dead runner is refused honestly [app]
- slowest: 1.1 s AdbClientTest.keystore persists and restores the identity [capabilities/shell]
- slowest: 1.0 s LlmGatewayTest.given a 429 then a success when called then the retry lands and the usage is audited [core/llm]
- slowest: 0.8 s BundleExtractorTest.oversized entries are rejected [core/engine]

## Style baseline (report only): 21807 ktlint/detekt findings
- app/src/lite/kotlin/app/autohost/runtime/ActualRuntimeContributions.kt:14:1 Class body should not start with blank line
- app/src/lite/kotlin/app/autohost/runtime/ActualRuntimeContributions.kt:15:32 Newline expected after opening parenthesis
- app/src/lite/kotlin/app/autohost/runtime/ActualRuntimeContributions.kt:15:50 Parameter should start on a newline
- app/src/lite/kotlin/app/autohost/runtime/ActualRuntimeContributions.kt:15:81 Newline expected before closing parenthesis
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:37:1 Class body should not start with blank line
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:47:27 Newline expected after opening parenthesis
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:47:47 Parameter should start on a newline
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:47:70 Parameter should start on a newline
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:47:91 Parameter should start on a newline
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:47:118 Newline expected before closing parenthesis
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:48:21 A multiline expression should start on a new line
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:69:26 A multiline expression should start on a new line
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:71:9 Expected a blank line for this declaration
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:72:9 Expected a blank line for this declaration
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:73:9 Expected a blank line for this declaration
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:83:57 A multiline expression should start on a new line
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:83:57 Newline expected before expression body
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:91:63 First line of body expression fits on same line as function signature
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:95:31 Newline expected after opening parenthesis
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:95:45 Parameter should start on a newline
