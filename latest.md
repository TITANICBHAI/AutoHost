# CI digest - run 8 - branch work/s02-green - commit baf69d1
tests step outcome: failure

## Verdict: RED - 1349 tests ran, 14 failed, 0 Kotlin compile errors, 3 failed Gradle tasks, 4 modules with no results

## Failed Gradle tasks
- :runtime:agent:test
- :core:llm:test
- :capabilities:shell:testDebugUnitTest

## Failing tests (first 40 of 14)
- [capabilities/shell] ShellUiAndScreenshotProviderTest.acts scope by the device ui act grant - java.lang.AssertionError: expected:<true> but was:<false>
- [capabilities/shell] ShellUiAndScreenshotProviderTest.find uses the shared selector engine - java.lang.NullPointerException: null cannot be cast to non-null type kotlinx.serialization.json.JsonObject
- [capabilities/shell] Spake2P256Test.two parties derive identical keys and confirm each other - java.lang.IllegalArgumentException: Empty key
- [capabilities/shell] Spake2P256Test.a wrong password fails confirmation - java.lang.IllegalArgumentException: Empty key
- [capabilities/shell] UiAutomationProviderTest.secret text is allowed on this provider (ADR-068) - java.lang.NullPointerException
- [capabilities/shell] UiAutomationProviderTest.click verifies through the runner and the expect recheck - java.lang.NullPointerException
- [capabilities/shell] UiAutomationProviderTest.out-of-scope act is refused before any runner traffic - java.lang.AssertionError: expected:<SCOPE_VIOLATION> but was:<NOT_FOUND>
- [capabilities/shell] UiAutomationSessionClientTest.runner errors surface as typed RpcError - java.lang.AssertionError: expected RpcError
- [capabilities/shell] UiautomatorDumpTest.escapes round-trip and nested structure keeps depth - java.lang.AssertionError: expected:<0> but was:<1>
- [core/llm] LlmGatewayTest.given a 429 then a success when called then the retry lands and the usage is audited - java.lang.AssertionError
- [runtime/agent] AgentRuntimeProviderTest.given a finishing loop when the run completes then SUCCEEDED with a finished event and heartbeats - java.lang.AssertionError: expected:<SUCCEEDED> but was:<null>
- [runtime/agent] AgentRuntimeProviderTest.given a pause before the first turn when resumed then the run completes (pause is cooperative) - java.lang.AssertionError: a paused run starts no new turn expected null, but was:<{"type":"finished","data":{"status":"SUCCEEDED","message":"done"}}>
- [runtime/agent] AgentRuntimeProviderTest.given a failing loop when the run completes then FAILED with the loop reason - java.lang.AssertionError: expected:<FAILED> but was:<null>
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
- capabilities/shell: 65 / 9 / 0
- capabilities/vision: 15 / 0 / 4
- core/broker: 42 / 0 / 0
- core/common: 21 / 0 / 0
- core/data: 5 / 0 / 0
- core/designsystem: 6 / 0 / 0
- core/devapi: 26 / 0 / 2
- core/engine: 296 / 0 / 2
- core/llm: 36 / 1 / 0
- core/model: 47 / 0 / 0
- runtime/agent: 175 / 4 / 0
- runtime/flow: 93 / 0 / 24
- runtime/js: 41 / 0 / 0

## Style baseline (report only): 18945 ktlint/detekt findings
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
