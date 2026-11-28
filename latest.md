# CI digest - run 76 - branch work/s02-green - commit d69b173
tests step outcome: failure

## Verdict: RED - 1298 tests ran, 0 failed, 22 Kotlin compile errors, 2 failed Gradle tasks, 6 modules with no results

## Kotlin compile errors (first 40 of 22)
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:19:12 Unresolved reference 'sun'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:69:25 Unresolved reference 'HttpServer'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:74:17 Unresolved reference 'stop'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:82:17 Unresolved reference 'HttpServer'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:83:32 Cannot infer type for value parameter 'exchange'. Specify it explicitly.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:84:33 Unresolved reference 'requestBody'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:85:36 Unresolved reference 'requestHeaders'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:87:22 Unresolved reference 'responseHeaders'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:88:22 Unresolved reference 'sendResponseHeaders'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:89:22 Unresolved reference 'responseBody'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:90:22 Unresolved reference 'close'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:19:12 Unresolved reference 'sun'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:69:25 Unresolved reference 'HttpServer'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:74:17 Unresolved reference 'stop'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:82:17 Unresolved reference 'HttpServer'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:83:32 Cannot infer type for value parameter 'exchange'. Specify it explicitly.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:84:33 Unresolved reference 'requestBody'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:85:36 Unresolved reference 'requestHeaders'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:87:22 Unresolved reference 'responseHeaders'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:88:22 Unresolved reference 'sendResponseHeaders'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:89:22 Unresolved reference 'responseBody'.
- app/src/test/kotlin/app/autohost/agent/AgentWiringEndToEndTest.kt:90:22 Unresolved reference 'close'.

## Failed Gradle tasks
- :app:compileCorePyDebugUnitTestKotlin
- :app:compileCoreLiteDebugUnitTestKotlin

## Modules that produced no test results (compile error, timeout, or no tests)
- runtime/api
- runtime/python
- plugin-sdk
- testing/fixture-app
- app
- app

## Per module (tests / failed / skipped)
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
- core/llm: 42 / 0 / 0
- core/model: 47 / 0 / 0
- runtime/agent: 273 / 0 / 0
- runtime/flow: 117 / 0 / 24
- runtime/js: 41 / 0 / 0

## Where the time went (finished tasks only; a task stopped by the timeout leaves no timings)
- core/llm: 32 s of test time
- capabilities/browser: 21 s of test time
- capabilities/shell: 4 s of test time
- core/model: 3 s of test time
- core/engine: 3 s of test time
- slowest: 30.0 s HttpUrlConnectionEngineTest.given a hung server when the call is cancelled then it returns within about a second [core/llm]
- slowest: 10.2 s CdpBrowserProviderTest.navigate forwards through the shell then evaluates the page [capabilities/browser]
- slowest: 10.0 s CdpBrowserProviderTest.close removes the forward [capabilities/browser]
- slowest: 2.0 s SelectorBenchTest.find latency stays in single-digit milliseconds on large trees [core/model]
- slowest: 1.0 s LlmGatewayTest.given a 429 then a success when called then the retry lands and the usage is audited [core/llm]
- slowest: 0.8 s AdbClientTest.keystore persists and restores the identity [capabilities/shell]
- slowest: 0.7 s BundleExtractorTest.oversized entries are rejected [core/engine]
- slowest: 0.6 s NotificationsProviderTest.wait_for times out honestly when nothing matches [capabilities/notifications]

## Style baseline (report only): 22610 ktlint/detekt findings
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
