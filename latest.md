# CI digest - run 49 - branch work/s02-green - commit 1e7b3c7
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
- capabilities/shell: 5 s of test time
- core/engine: 3 s of test time
- core/model: 3 s of test time
- slowest: 30.0 s HttpUrlConnectionEngineTest.given a hung server when the call is cancelled then it returns within about a second [core/llm]
- slowest: 10.2 s CdpBrowserProviderTest.navigate forwards through the shell then evaluates the page [capabilities/browser]
- slowest: 10.0 s CdpBrowserProviderTest.close removes the forward [capabilities/browser]
- slowest: 2.0 s SelectorBenchTest.find latency stays in single-digit milliseconds on large trees [core/model]
- slowest: 1.5 s UiAutomationControllerTest.spawn failure with a dead runner is refused honestly [app]
- slowest: 1.2 s AdbClientTest.keystore persists and restores the identity [capabilities/shell]
- slowest: 1.0 s LlmGatewayTest.given a 429 then a success when called then the retry lands and the usage is audited [core/llm]
- slowest: 0.7 s AdbClientTest.auth signature verifies against the token with the public key [capabilities/shell]

## Style baseline (report only): 21807 ktlint/detekt findings
- app/src/lite/kotlin/app/autohost/runtime/ActualRuntimeContributions.kt:14:1 Class body should not start with blank line
- app/src/lite/kotlin/app/autohost/runtime/ActualRuntimeContributions.kt:15:32 Newline expected after opening parenthesis
- app/src/lite/kotlin/app/autohost/runtime/ActualRuntimeContributions.kt:15:50 Parameter should start on a newline
- app/src/lite/kotlin/app/autohost/runtime/ActualRuntimeContributions.kt:15:81 Newline expected before closing parenthesis
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
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:66:1 Exceeded max line length (120) (cannot be auto-corrected)
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:68:22 A multiline expression should start on a new line
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:91:65 A multiline expression should start on a new line
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:91:65 Newline expected before expression body
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:93:24 A multiline expression should start on a new line
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:100:1 Exceeded max line length (120) (cannot be auto-corrected)
