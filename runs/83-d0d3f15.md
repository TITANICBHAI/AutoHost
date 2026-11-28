# CI digest - run 83 - branch work/s02-green - commit d0d3f15
tests step outcome: failure

## Verdict: RED - 1844 tests ran, 7 failed, 0 Kotlin compile errors, 1 failed Gradle tasks, 4 modules with no results

## Failed Gradle tasks
- :app:testCoreLiteDebugUnitTest

## Failing tests (first 40 of 7)
- [app] AddTaskDialogJourneyTest.classMethod - java.lang.AssertionError: Failed to fetch maven artifact org.robolectric:android-all-instrumented:15-robolectric-13954326-i7
- [app] AgentChatJourneyTest.classMethod - java.lang.AssertionError: Failed to fetch maven artifact org.robolectric:android-all-instrumented:15-robolectric-13954326-i7
- [app] LaunchSmokeTest.classMethod - java.lang.AssertionError: Failed to fetch maven artifact org.robolectric:android-all-instrumented:15-robolectric-13954326-i7
- [app] MacroEditorJourneyTest.classMethod - java.lang.AssertionError: Failed to fetch maven artifact org.robolectric:android-all-instrumented:15-robolectric-13954326-i7
- [app] ScreenshotsTest.classMethod - java.lang.AssertionError: Failed to fetch maven artifact org.robolectric:android-all-instrumented:15-robolectric-13954326-i7
- [app] SettingsWalkJourneyTest.classMethod - java.lang.AssertionError: Failed to fetch maven artifact org.robolectric:android-all-instrumented:15-robolectric-13954326-i7
- [app] UiJourneyTest.classMethod - java.lang.AssertionError: Failed to fetch maven artifact org.robolectric:android-all-instrumented:15-robolectric-13954326-i7

## Modules that produced no test results (compile error, timeout, or no tests)
- runtime/api
- runtime/python
- plugin-sdk
- testing/fixture-app

## Per module (tests / failed / skipped)
- app: 541 / 7 / 0
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
- core/engine: 305 / 0 / 2
- core/llm: 44 / 0 / 0
- core/model: 47 / 0 / 0
- runtime/agent: 276 / 0 / 0
- runtime/flow: 117 / 0 / 24
- runtime/js: 41 / 0 / 0

## Where the time went (finished tasks only; a task stopped by the timeout leaves no timings)
- core/llm: 32 s of test time
- capabilities/browser: 20 s of test time
- app: 5 s of test time
- capabilities/shell: 4 s of test time
- core/model: 3 s of test time
- slowest: 30.0 s HttpUrlConnectionEngineTest.given a hung server when the call is cancelled then it returns within about a second [core/llm]
- slowest: 10.1 s CdpBrowserProviderTest.navigate forwards through the shell then evaluates the page [capabilities/browser]
- slowest: 10.0 s CdpBrowserProviderTest.close removes the forward [capabilities/browser]
- slowest: 2.0 s SelectorBenchTest.find latency stays in single-digit milliseconds on large trees [core/model]
- slowest: 1.5 s UiAutomationControllerTest.spawn failure with a dead runner is refused honestly [app]
- slowest: 1.5 s UiAutomationControllerTest.spawn failure with a dead runner is refused honestly [app]
- slowest: 1.0 s LlmGatewayTest.given a 429 then a success when called then the retry lands and the usage is audited [core/llm]
- slowest: 0.8 s AdbClientTest.handshake then shell v2 round-trip with separated streams and exit code [capabilities/shell]

## Style baseline (report only): 22709 ktlint/detekt findings
- app/src/lite/kotlin/app/autohost/runtime/ActualRuntimeContributions.kt:14:1 Class body should not start with blank line
- app/src/lite/kotlin/app/autohost/runtime/ActualRuntimeContributions.kt:15:32 Newline expected after opening parenthesis
- app/src/lite/kotlin/app/autohost/runtime/ActualRuntimeContributions.kt:15:50 Parameter should start on a newline
- app/src/lite/kotlin/app/autohost/runtime/ActualRuntimeContributions.kt:15:81 Newline expected before closing parenthesis
- app/src/py/kotlin/app/autohost/runtime/ActualRuntimeContributions.kt:12:1 Class body should not start with blank line
- app/src/py/kotlin/app/autohost/runtime/ActualRuntimeContributions.kt:13:32 Newline expected after opening parenthesis
- app/src/py/kotlin/app/autohost/runtime/ActualRuntimeContributions.kt:13:50 Parameter should start on a newline
- app/src/py/kotlin/app/autohost/runtime/ActualRuntimeContributions.kt:13:81 Newline expected before closing parenthesis
- app/src/py/kotlin/app/autohost/runtime/ActualRuntimeContributions.kt:13:101 First line of body expression fits on same line as function signature
- app/src/py/kotlin/app/autohost/runtime/ActualRuntimeContributions.kt:16:68 First line of body expression fits on same line as function signature
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:6:1 Unused import
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:11:1 Unused import
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:12:1 Unused import
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:32:1 Class body should not start with blank line
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:35:32 Newline expected after opening parenthesis
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:35:48 Parameter should start on a newline
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:35:62 Newline expected before closing parenthesis
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:54:61 A multiline expression should start on a new line
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:54:61 Newline expected before expression body
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:56:24 A multiline expression should start on a new line
