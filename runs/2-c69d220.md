# CI digest - run 2 - branch main - commit c69d220
tests step outcome: failure

## Verdict: RED - 877 tests ran, 0 failed, 151 Kotlin compile errors, 5 failed Gradle tasks, 9 modules with no results

## Kotlin compile errors (first 40 of 151)
- core/llm/src/test/kotlin/app/autohost/core/llm/ChatCodecTest.kt:33:92 Unresolved reference 'JsonObject'.
- core/llm/src/test/kotlin/app/autohost/core/llm/ChatCodecTest.kt:33:103 Cannot infer type for type parameter 'K'. Specify it explicitly.
- core/llm/src/test/kotlin/app/autohost/core/llm/ChatCodecTest.kt:33:103 Cannot infer type for type parameter 'V'. Specify it explicitly.
- core/llm/src/test/kotlin/app/autohost/core/llm/ChatCodecTest.kt:64:103 Unresolved reference 'JsonObject'.
- core/llm/src/test/kotlin/app/autohost/core/llm/ChatCodecTest.kt:64:114 Cannot infer type for type parameter 'K'. Specify it explicitly.
- core/llm/src/test/kotlin/app/autohost/core/llm/ChatCodecTest.kt:64:114 Cannot infer type for type parameter 'V'. Specify it explicitly.
- core/llm/src/test/kotlin/app/autohost/core/llm/HttpUrlConnectionEngineTest.kt:101:38 'fun launch(context: CoroutineContext = ..., start: CoroutineStart = ..., block: suspend CoroutineScope.() -> Unit): Job' is deprecate…
- core/llm/src/test/kotlin/app/autohost/core/llm/LlmGatewayTest.kt:37:53 Argument type mismatch: actual type is 'ByteArray', but 'String' was expected.
- core/llm/src/test/kotlin/app/autohost/core/llm/LlmGatewayTest.kt:43:61 Unresolved reference 'profile'.
- core/llm/src/test/kotlin/app/autohost/core/llm/LlmGatewayTest.kt:45:90 Unresolved reference 'key'.
- core/llm/src/test/kotlin/app/autohost/core/llm/LlmGatewayTest.kt:113:62 Argument type mismatch: actual type is '<anonymous>', but 'LlmGatewayTest.FakeProfiles' was expected.
- core/llm/src/test/kotlin/app/autohost/core/llm/LlmGatewayTest.kt:124:51 Argument type mismatch: actual type is '<anonymous>', but 'LlmGatewayTest.FakeProfiles' was expected.
- core/llm/src/test/kotlin/app/autohost/core/llm/LlmGatewayTest.kt:138:50 Argument type mismatch: actual type is '<anonymous>', but 'LlmGatewayTest.FakeProfiles' was expected.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentEvalHarnessTest.kt:96:47 Unresolved reference 'buildJsonObject'.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentEvalHarnessTest.kt:96:65 Unresolved reference 'put'.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentEvalHarnessTest.kt:124:30 Argument type mismatch: actual type is 'Function0<AgentLoop.FinishResult.Confirmed>', but 'AgentLoop.FinishVerifier' was expected.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentEvalHarnessTest.kt:190:41 No 'get' operator method providing array access.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentEvalHarnessTest.kt:194:41 No 'get' operator method providing array access.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentEvalHarnessTest.kt:198:41 No 'get' operator method providing array access.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentLoopTest.kt:168:9 Name contains illegal characters: ;.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentManifestsTest.kt:54:22 Unresolved reference 'pausible'.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentManifestsTest.kt:119:9 Name contains illegal characters: :.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentRuntimeProviderTest.kt:100:34 Argument type mismatch: actual type is 'Function0<AgentLoop.FinishResult.Confirmed>', but 'AgentLoop.FinishVerifier' was expect…
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentSectionBuilderTest.kt:19:83 Argument type mismatch: actual type is 'Any', but 'JsonElement' was expected.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentSectionBuilderTest.kt:65:9 Name contains illegal characters: ..
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentSecuritySuiteTest.kt:110:30 Argument type mismatch: actual type is 'Function0<AgentLoop.FinishResult.Confirmed>', but 'AgentLoop.FinishVerifier' was expected.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentSecuritySuiteTest.kt:226:30 Argument type mismatch: actual type is 'Function0<AgentLoop.FinishResult.Confirmed>', but 'AgentLoop.FinishVerifier' was expected.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentSecuritySuiteTest.kt:285:30 Argument type mismatch: actual type is 'Function0<AgentLoop.FinishResult.Confirmed>', but 'AgentLoop.FinishVerifier' was expected.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentSecuritySuiteTest.kt:313:28 Destructuring of type 'AgentLoop.Outcome' requires operator function 'component1()'.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentSecuritySuiteTest.kt:313:28 Destructuring of type 'AgentLoop.Outcome' requires operator function 'component2()'.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentViewTest.kt:80:28 Unresolved reference 'resolveForCall'.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentViewTest.kt:89:28 Unresolved reference 'resolveForCall'.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentViewTest.kt:97:28 Unresolved reference 'resolveForCall'.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentViewTest.kt:98:43 One type argument expected. Use class 'Ok' if you do not intend to pass type arguments.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AgentViewTest.kt:98:56 Unresolved reference 'value'.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AppSensitivityTest.kt:26:26 Cannot infer type for type parameter 'R'. Specify it explicitly.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AppSensitivityTest.kt:26:56 Unresolved reference 'jsonPrimitive'.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AppSensitivityTest.kt:26:80 Cannot infer type for type parameter 'T'. Specify it explicitly.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AppSensitivityTest.kt:35:35 Unresolved reference 'jsonPrimitive'.
- runtime/agent/src/test/kotlin/app/autohost/runtime/agent/AppSensitivityTest.kt:35:85 Unresolved reference 'jsonPrimitive'.

## Failed Gradle tasks
- :core:llm:compileTestKotlin
- :runtime:agent:compileTestKotlin
- :capabilities:shell:compileDebugUnitTestKotlin
- :capabilities:notifications:testDebugUnitTest
- :app:compileCorePyDebugUnitTestKotlin

## A task TIMED OUT - the last tests that started (the last one is the hung test)
- SocketProviderTest > connect failures are honest capability errors STARTED
- SocketProviderTest > tcp connect opens a run-scoped handle STARTED
- SocketProviderTest > closeAllForRun releases every handle STARTED
- WebhookServerTest > wrong token is rejected with 401 and audited STARTED
- WebhookServerTest > unknown hook path is a 404 STARTED
- WebhookServerTest > hook fires the named external trigger STARTED
- WsClientProviderTest > unknown host is a scope violation and unknown handle not found STARTED
- WsClientProviderTest > send writes a masked text frame STARTED
- WsClientProviderTest > closeAllForRun force-closes run handles STARTED
- WsClientProviderTest > connect returns a handle with the negotiated subprotocol STARTED

## Modules that produced no test results (compile error, timeout, or no tests)
- core/llm
- runtime/api
- runtime/agent
- runtime/python
- plugin-sdk
- capabilities/notifications
- capabilities/shell
- testing/fixture-app
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
- capabilities/projection: 4 / 0 / 0
- capabilities/rawbridge: 2 / 0 / 0
- capabilities/screen: 22 / 0 / 0
- capabilities/vision: 15 / 0 / 4
- core/broker: 42 / 0 / 0
- core/common: 21 / 0 / 0
- core/data: 5 / 0 / 0
- core/designsystem: 6 / 0 / 0
- core/devapi: 26 / 0 / 2
- core/engine: 296 / 0 / 2
- core/model: 47 / 0 / 0
- runtime/flow: 93 / 0 / 24
- runtime/js: 41 / 0 / 0

## Style baseline (report only): 18586 ktlint/detekt findings
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
