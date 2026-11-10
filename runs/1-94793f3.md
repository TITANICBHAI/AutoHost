# CI digest - run 1 - branch main - commit 94793f3
tests step outcome: failure

## Verdict: RED - 877 tests ran, 20 failed, 151 Kotlin compile errors, 9 failed Gradle tasks, 9 modules with no results

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
- :runtime:flow:test
- :capabilities:data:testDebugUnitTest
- :core:engine:testDebugUnitTest
- :capabilities:shell:compileDebugUnitTestKotlin
- :capabilities:net:testDebugUnitTest
- :capabilities:notifications:testDebugUnitTest
- :app:compileCorePyDebugUnitTestKotlin

## Failing tests (first 40 of 20)
- [capabilities/data] DataPolicyTest.dialable numbers pass and keep their digits - org.junit.ComparisonFailure: expected:<[+]15551234567> but was:<[]15551234567>
- [capabilities/data] PersonalDataProvidersTest.contacts query needs the read permission - java.lang.NullPointerException
- [capabilities/data] PersonalDataProvidersTest.contacts insert requires write and returns an id - java.lang.NullPointerException
- [capabilities/data] SmsTelephonyProvidersTest.sms send budget is per run and cleared on terminal - java.lang.AssertionError
- [capabilities/net] HttpProviderTest.body is marshalled as b64 and headers echo redacted - java.lang.NullPointerException: null cannot be cast to non-null type kotlinx.serialization.json.JsonPrimitive
- [capabilities/net] NetPolicyTest.wildcard covers deeper labels but not the bare domain - java.lang.AssertionError: expected:<class app.autohost.capabilities.net.NetPolicy$DomainVerdict$Denied (Kotlin reflection is not available)> but was:<class app.autohost.capabilities.net.NetPolicy$DomainVerdict$Allowed (…
- [capabilities/net] SocketProviderTest.tcp connect respects the domain allowlist - java.lang.AssertionError: expected:<PERMISSION_DENIED> but was:<SCOPE_VIOLATION>
- [capabilities/net] SocketProviderTest.tcp handles cap at eight - java.lang.AssertionError
- [capabilities/net] SocketProviderTest.udp payloads are capped at the datagram ceiling - java.lang.AssertionError: expected:<INVALID_ARGS> but was:<SCOPE_VIOLATION>
- [capabilities/net] SocketProviderTest.connect failures are honest capability errors - java.lang.AssertionError: expected:<CAPABILITY_UNAVAILABLE> but was:<SCOPE_VIOLATION>
- [capabilities/net] SocketProviderTest.tcp connect opens a run-scoped handle - java.lang.AssertionError
- [capabilities/net] SocketProviderTest.closeAllForRun releases every handle - java.lang.AssertionError: expected:<2> but was:<0>
- [core/engine] ScheduleFormGoldenTest.invalid forms return exactly the listed error keys - java.lang.IllegalArgumentException: misfire catch_up must be a positive integer
- [runtime/flow] FlowParserTest.nested repeat steps are parsed recursively - java.lang.AssertionError
- [runtime/flow] FlowRuntimeProviderTest.subflows resolve by file name and stem, task json is excluded - app.autohost.runtime.flow.FlowSourceException: flow 'flow.json' failed to parse: flow invalid: schema must be the number 1
- [runtime/flow] FlowRuntimeProviderTest.installed yaml plug parses flow yaml end to end - app.autohost.runtime.flow.FlowSourceException: flow 'flow.yaml' failed to parse: flow invalid: schema must be the number 1
- [runtime/flow] FlowRuntimeProviderTest.happy path runs in-process and emits finished with the outcome - app.autohost.runtime.flow.FlowSourceException: flow 'flow.json' failed to parse: flow invalid: schema must be the number 1
- [runtime/flow] GuardedReplayTest.package change pins the inclusive threshold boundary - java.lang.AssertionError: expected:<0.85> but was:<0.9249999999999999>
- [runtime/flow] RecorderTest.password input never stores text - it becomes a secret placeholder - org.junit.ComparisonFailure: expected:<${secret.INPUT_[]1}> but was:<${secret.INPUT_[INPUT_]1}>
- [runtime/flow] SelectorResolverTest.state keys fold into single state term - org.junit.ComparisonFailure: expected:<..."terms":[{"state":{"[clickable":true,"enabled":fals]e}}]}> but was:<..."terms":[{"state":{"[enabled":false,"clickable":tru]e}}]}>

## A task TIMED OUT - the last tests that started (the last one is the hung test)
- WebhookServerTest > hook fires the named external trigger STARTED
- WsClientProviderTest > unknown host is a scope violation and unknown handle not found STARTED
- WsClientProviderTest > send writes a masked text frame STARTED
- WsClientProviderTest > closeAllForRun force-closes run handles STARTED
- WsClientProviderTest > connect returns a handle with the negotiated subprotocol STARTED
- SecretStoreTest > canary secret never survives a redact pass over mixed output STARTED
- SecretStoreTest > redactor scrubs exact values and common encodings STARTED
- SecretStoreTest > tampered blob decrypts to null (fail closed, no exception escapes) STARTED
- SecretStoreTest > allOf and keysOf expose the profile snapshot - deleteForProfile removes blobs STARTED
- SecretStoreTest > put encrypts and get decrypts - values never appear as plaintext rows STARTED

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
- capabilities/data: 18 / 4 / 0
- capabilities/files: 12 / 0 / 0
- capabilities/ime: 3 / 0 / 0
- capabilities/intents: 7 / 0 / 0
- capabilities/net: 29 / 8 / 0
- capabilities/projection: 4 / 0 / 0
- capabilities/rawbridge: 2 / 0 / 0
- capabilities/screen: 22 / 0 / 0
- capabilities/vision: 15 / 0 / 4
- core/broker: 42 / 0 / 0
- core/common: 21 / 0 / 0
- core/data: 5 / 0 / 0
- core/designsystem: 6 / 0 / 0
- core/devapi: 26 / 0 / 2
- core/engine: 296 / 1 / 2
- core/model: 47 / 0 / 0
- runtime/flow: 93 / 7 / 24
- runtime/js: 41 / 0 / 0

## Style baseline (report only): 18575 ktlint/detekt findings
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
