# CI digest - run 9 - branch work/s02-green - commit 963cbf9
tests step outcome: failure

## Verdict: RED - 1349 tests ran, 1 failed, 0 Kotlin compile errors, 1 failed Gradle tasks, 4 modules with no results

## Failed Gradle tasks
- :runtime:agent:test

## Failing tests (first 40 of 1)
- [runtime/agent] AgentSecuritySuiteTest.given a fully compromised model obeying the screen injection when run then EVERY attempt fails closed - java.lang.AssertionError

## Modules that produced no test results (compile error, timeout, or no tests)
- runtime/api
- runtime/python
- plugin-sdk
- testing/fixture-app

## Per module (tests / failed / skipped)
- app: 188 / 0 / 0
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
- core/engine: 296 / 0 / 2
- core/llm: 36 / 0 / 0
- core/model: 47 / 0 / 0
- runtime/agent: 175 / 1 / 0
- runtime/flow: 93 / 0 / 24
- runtime/js: 41 / 0 / 0

## Style baseline (report only): 18954 ktlint/detekt findings
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
