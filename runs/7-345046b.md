# CI digest - run 7 - branch work/s02-green - commit 345046b
tests step outcome: failure

## Verdict: RED - 1349 tests ran, 22 failed, 0 Kotlin compile errors, 3 failed Gradle tasks, 4 modules with no results

## Failed Gradle tasks
- :core:llm:test
- :runtime:agent:test
- :capabilities:shell:testDebugUnitTest

## Failing tests (first 40 of 22)
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
- [runtime/agent] AgentRuntimeProviderTest.given a finishing loop when the run completes then SUCCEEDED with a finished event and heartbeats - java.lang.AssertionError
- [runtime/agent] AgentRuntimeProviderTest.given a pause before the first turn when resumed then the run completes (pause is cooperative) - java.lang.AssertionError: a paused run starts no new turn expected null, but was:<{"type":"finished","data":{"status":"SUCCEEDED","message":"done"}}>
- [runtime/agent] AgentSecuritySuiteTest.given a cancel while the model call is in flight when the job is cancelled then cancellation propagates uncaught - java.lang.AssertionError: a cancelled run must end CANCELLED, not with an Outcome (the exception was swallowed)
- [runtime/agent] AgentSecuritySuiteTest.given a fully compromised model obeying the screen injection when run then EVERY attempt fails closed - java.lang.AssertionError
- [runtime/agent] AgentViewTest.given a node id from the previous generation when resolved then STALE_OBSERVATION - java.lang.AssertionError
- [runtime/agent] AgentViewTest.given only the focus moved when fingerprinted then the fingerprint is the same (focused excluded, the RM-A4 rule) - org.junit.ComparisonFailure: expected:<....widget.EditText|a||[clickable|40,812,1040,940 android.widget.EditText|b||]|40,812,1040,940> but was:<....widget.EditText|a||[|40,812,1040,940 android.widget.EditText|b||clickab…
- [runtime/agent] CompletionVerifierTest.given an unknown answer shape when verified then Unverified (fail closed, never confirmed) - java.lang.AssertionError: expected:<Unverified> but was:<Rejected>
- [runtime/agent] CompletionVerifierTest.given a finish when verified then the final observation is written as ui_snapshot evidence BEFORE the ask - java.lang.AssertionError: expected:<(ui.prompt, confirm)> but was:<(ui.prompt, ask)>
- [runtime/agent] RunBudgetTest.given the token budget when the reserve does not fit then BUDGET_TOKENS - org.junit.ComparisonFailure: expected:<BUDGET_[TOKENS]> but was:<BUDGET_[CONTEXT]>
- [runtime/agent] ToolCatalogTest.given the host tools when inspected then they declare no capability - java.lang.AssertionError: expected null, but was:<null>
- [runtime/agent] ToolPipelineTest.given host tools when executed then ok with host=true and no budget or broker - java.lang.NullPointerException: null cannot be cast to non-null type kotlinx.serialization.json.JsonPrimitive
- [runtime/agent] ToolPipelineTest.given the approved target gone when revalidated then STALE_NODE - java.lang.AssertionError: expected:<STALE_NODE> but was:<null>

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
- runtime/agent: 175 / 12 / 0
- runtime/flow: 93 / 0 / 24
- runtime/js: 41 / 0 / 0

## Style baseline (report only): 8596 ktlint/detekt findings
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
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:102:22 A multiline expression should start on a new line
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:123:39 Newline expected after opening parenthesis
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:123:56 Parameter should start on a newline
- app/src/androidTest/kotlin/app/autohost/PythonRunEndToEndTest.kt:123:71 Parameter should start on a newline
