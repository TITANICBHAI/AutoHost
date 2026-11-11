# CI digest - run 4 - branch work/s02-green - commit 49c7088
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
- NotificationAccessPolicyTest > parses the system setting format STARTED
- NotificationAccessPolicyTest > blank and malformed settings are disabled STARTED
- NotificationsProviderTest > availability is honest about the listener connection STARTED
- NotificationsProviderTest > press sends through the handler slot STARTED
- NotificationsProviderTest > list returns scoped items only STARTED
- NotificationsProviderTest > wait_for times out honestly when nothing matches STARTED

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
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:3:1 Imports must be ordered in lexicographic order without any empty lines in-between with "java", 
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:21:1 Class body should not start with blank line
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:22:25 A multiline expression should start on a new line
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:24:9 Expected a blank line for this declaration
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:29:28 A multiline expression should start on a new line
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:39:23 A multiline expression should start on a new line
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:43:18 A multiline expression should start on a new line
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:44:60 A comment in a 'value_argument_list' is only allowed when placed on a separate line (cannot b
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:51:1 Exceeded max line length (120) (cannot be auto-corrected)
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:51:29 Argument should be on a separate line (unless all arguments can fit a single line)
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:51:49 Argument should be on a separate line (unless all arguments can fit a single line)
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:51:59 Argument should be on a separate line (unless all arguments can fit a single line)
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:51:85 Missing newline before ")"
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:51:86 Missing newline before ")"
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:51:97 Argument should be on a separate line (unless all arguments can fit a single line)
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:51:126 Missing newline before ")"
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:57:22 Newline expected after opening parenthesis
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:57:38 Parameter should start on a newline
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:57:54 Newline expected before closing parenthesis
- capabilities/a11y/src/androidTest/kotlin/app/autohost/capabilities/a11y/A11yUiProviderTestHarness.kt:69:1 Exceeded max line length (120) (cannot be auto-corrected)
