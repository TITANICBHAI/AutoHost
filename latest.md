# CI digest - run 56 - branch work/s02-green - commit 1253689
tests step outcome: success

## Verdict: GREEN - 1737 tests ran, 0 failed, 0 Kotlin compile errors, 0 failed Gradle tasks, 4 modules with no results

## Modules that produced no test results (compile error, timeout, or no tests)
- runtime/api
- runtime/python
- plugin-sdk
- testing/fixture-app

## Per module (tests / failed / skipped)
- app: 512 / 0 / 0
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
- core/llm: 36 / 0 / 0
- core/model: 47 / 0 / 0
- runtime/agent: 206 / 0 / 0
- runtime/flow: 117 / 0 / 24
- runtime/js: 41 / 0 / 0

## Where the time went (finished tasks only; a task stopped by the timeout leaves no timings)
- core/llm: 32 s of test time
- app: 25 s of test time
- capabilities/browser: 21 s of test time
- capabilities/shell: 4 s of test time
- core/engine: 3 s of test time
- slowest: 30.0 s HttpUrlConnectionEngineTest.given a hung server when the call is cancelled then it returns within about a second [core/llm]
- slowest: 10.2 s CdpBrowserProviderTest.navigate forwards through the shell then evaluates the page [capabilities/browser]
- slowest: 10.0 s CdpBrowserProviderTest.close removes the forward [capabilities/browser]
- slowest: 8.0 s AddTaskDialogJourneyTest.Add a task opens a pop-up with three ways to add one and Close shuts it [app]
- slowest: 2.0 s SelectorBenchTest.find latency stays in single-digit milliseconds on large trees [core/model]
- slowest: 1.5 s UiAutomationControllerTest.spawn failure with a dead runner is refused honestly [app]
- slowest: 1.5 s UiAutomationControllerTest.spawn failure with a dead runner is refused honestly [app]
- slowest: 1.2 s SettingsWalkJourneyTest.every Settings screen opens and Back returns to the Settings list [app]

## Style baseline (report only): 21964 ktlint/detekt findings
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
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:95:62 Parameter should start on a newline
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:95:84 Newline expected before closing parenthesis
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:101:42 Newline expected after opening parenthesis
- plugin-sdk/src/main/java/app/autohost/plugin/PluginCapabilityService.kt:101:58 Parameter should start on a newline
