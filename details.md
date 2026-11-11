# CI details - 0 distinct Kotlin compile errors, 39 failing tests

## All Kotlin compile errors (sorted, de-duplicated)

## All failing tests
- [app] AskSheetStateTest.given a picker entry when toggled and started then it reaches the starter like a typed one - java.lang.AssertionError: expected:<[com.example.calc]> but was:<[com.android.settings, com.example.calc]>
- [app] AskSheetStateTest.given a picker entry when toggled and started then it reaches the starter like a typed one - java.lang.AssertionError: expected:<[com.example.calc]> but was:<[com.android.settings, com.example.calc]>
- [capabilities/shell] AdbClientTest.legacy adbd falls back to merged shell output with null exit code - app.autohost.capabilities.shell.AdbProtocol$AdbProtocolException: OPEN was not acknowledged with OKAY (CLSE)
- [capabilities/shell] AdbClientTest.unauthorized device gets the public key offer and still completes after approval - app.autohost.capabilities.shell.AdbProtocol$AdbProtocolException: adbd closed during handshake: null
- [capabilities/shell] LogcatOutputTest.missing pid parses as null and banners are skipped - java.lang.AssertionError: expected:<1> but was:<0>
- [capabilities/shell] LogcatOutputTest.epoch stamps parse to millis - java.lang.AssertionError: expected:<3> but was:<2>
- [capabilities/shell] LogcatOutputTest.filters apply level pid and text - java.lang.AssertionError: expected:<2> but was:<0>
- [capabilities/shell] ShellUiAndScreenshotProviderTest.initializationError - org.junit.runners.model.InvalidTestClassError: Invalid test class 'app.autohost.capabilities.shell.ShellUiAndScreenshotProviderTest': 1. Method screencap writes a png into the evidence dir with IHDR dimensions() should be void
- [capabilities/shell] Spake2P256Test.two parties derive identical keys and confirm each other - java.lang.IllegalArgumentException: Empty key
- [capabilities/shell] Spake2P256Test.a wrong password fails confirmation - java.lang.IllegalArgumentException: Empty key
- [capabilities/shell] UiAutomationProviderTest.secret text is allowed on this provider (ADR-068) - java.lang.NullPointerException
- [capabilities/shell] UiAutomationProviderTest.click verifies through the runner and the expect recheck - java.lang.NullPointerException
- [capabilities/shell] UiAutomationProviderTest.out-of-scope act is refused before any runner traffic - java.lang.AssertionError: expected:<SCOPE_VIOLATION> but was:<NOT_FOUND>
- [capabilities/shell] UiAutomationSessionClientTest.runner errors surface as typed RpcError - java.lang.AssertionError: expected RpcError
- [capabilities/shell] UiautomatorDumpTest.escapes round-trip and nested structure keeps depth - java.lang.AssertionError: expected:<0> but was:<1>
- [core/llm] BaseUrlValidatorTest.given the full vector table when validated then every verdict and reason holds - java.lang.AssertionError: vector failures: vector 34 expected ACCEPT: https://模型.example/v1
- [core/llm] ChatCodecTest.given a bad tool name when encoded then the codec refuses it - java.lang.Exception: Unexpected exception, expected<java.lang.IllegalArgumentException> but was<java.lang.IllegalStateException>
- [core/llm] HttpUrlConnectionEngineTest.given a hung server when the call is cancelled then it returns within about a second - kotlinx.coroutines.TimeoutCancellationException: Timed out waiting for 2000 ms
- [core/llm] HttpUrlConnectionEngineTest.given a 429 with Retry-After when posted then the header surfaces for the retry policy - java.lang.AssertionError: expected:<7> but was:<null>
- [core/llm] LlmGatewayTest.given a 429 then a success when called then the retry lands and the usage is audited - java.lang.AssertionError
- [runtime/agent] AgentEventLogTest.agentSection carries goal, budgets, usage counters and the drop counter — never a key - java.lang.AssertionError: expected: java.lang.Boolean<false> but was: java.lang.String<false>
- [runtime/agent] AgentLoopTest.given four turns when the last request is built then the stale screens are compacted to notes - java.lang.IndexOutOfBoundsException: Index 3 out of bounds for length 3
- [runtime/agent] AgentLoopTest.given a turn without tool calls when run then the nudge is injected and the second empty turn counts as a failure - org.junit.ComparisonFailure: expected:<AGENT_[FAILURES]> but was:<AGENT_[STUCK]>
- [runtime/agent] AgentLoopTest.given two tool calls in one message when run then the extra gets ONE_ACTION_PER_TURN and is never executed - java.lang.AssertionError
- [runtime/agent] AgentRuntimeProviderTest.given a finishing loop when the run completes then SUCCEEDED with a finished event and heartbeats - java.lang.AssertionError
- [runtime/agent] AgentRuntimeProviderTest.given a pause before the first turn when resumed then the run completes (pause is cooperative) - java.lang.AssertionError: a paused run starts no new turn expected null, but was:<{"type":"finished","data":{"status":"SUCCEEDED","message":"done"}}>
- [runtime/agent] AgentRuntimeProviderTest.given a failing loop when the run completes then FAILED with the loop reason - java.lang.AssertionError
- [runtime/agent] AgentSecuritySuiteTest.given a cancel while the model call is in flight when the job is cancelled then cancellation propagates uncaught - java.lang.AssertionError: a cancelled run must end CANCELLED, not with an Outcome (the exception was swallowed)
- [runtime/agent] AgentSecuritySuiteTest.given a fully compromised model obeying the screen injection when run then EVERY attempt fails closed - java.lang.AssertionError
- [runtime/agent] AgentViewTest.given a node id from the previous generation when resolved then STALE_OBSERVATION - java.lang.AssertionError
- [runtime/agent] AgentViewTest.given only the focus moved when fingerprinted then the fingerprint is the same (focused excluded, the RM-A4 rule) - org.junit.ComparisonFailure: expected:<....widget.EditText|a||[clickable|40,812,1040,940 android.widget.EditText|b||]|40,812,1040,940> but was:<....widget.EditText|a||[|40,812,1040,940 android.widget.EditText|b||clickable]|40,812,1040,940>
- [runtime/agent] CompletionVerifierTest.given an unknown answer shape when verified then Unverified (fail closed, never confirmed) - java.lang.AssertionError: expected:<Unverified> but was:<Rejected>
- [runtime/agent] CompletionVerifierTest.given a finish when verified then the final observation is written as ui_snapshot evidence BEFORE the ask - java.lang.AssertionError: expected:<(ui.prompt, confirm)> but was:<(ui.prompt, ask)>
- [runtime/agent] RunBudgetTest.given the token budget when the reserve does not fit then BUDGET_TOKENS - org.junit.ComparisonFailure: expected:<BUDGET_[TOKENS]> but was:<BUDGET_[CONTEXT]>
- [runtime/agent] StuckDetectorTest.given three identical screens when observed then a factual warning, not a stop (ADR-091 12) - java.lang.AssertionError
- [runtime/agent] StuckDetectorTest.given near-identical screens above the threshold when observed then they count as the same screen - java.lang.AssertionError: 40 shared + 1 varying ≈ 0.952 must count as the same screen
- [runtime/agent] ToolCatalogTest.given the host tools when inspected then they declare no capability - java.lang.AssertionError: expected null, but was:<null>
- [runtime/agent] ToolPipelineTest.given host tools when executed then ok with host=true and no budget or broker - java.lang.NullPointerException: null cannot be cast to non-null type kotlinx.serialization.json.JsonPrimitive
- [runtime/agent] ToolPipelineTest.given the approved target gone when revalidated then STALE_NODE - java.lang.AssertionError: expected:<STALE_NODE> but was:<null>

## Gradle 'What went wrong' blocks
### 1
    Execution failed for task ':core:llm:test'.
    > There were failing tests. See the report at: file:///home/runner/work/AutoHost/AutoHost/core/llm/build/reports/tests/test/index.html
### 2
    Execution failed for task ':runtime:agent:test'.
    > There were failing tests. See the report at: file:///home/runner/work/AutoHost/AutoHost/runtime/agent/build/reports/tests/test/index.html
### 3
    Execution failed for task ':capabilities:shell:testDebugUnitTest'.
    > There were failing tests. See the report at: file:///home/runner/work/AutoHost/AutoHost/capabilities/shell/build/reports/tests/testDebugUnitTest/index.html
### 4
    Execution failed for task ':app:testCorePyDebugUnitTest'.
    > There were failing tests. See the report at: file:///home/runner/work/AutoHost/AutoHost/app/build/reports/tests/testCorePyDebugUnitTest/index.html
### 5
    Execution failed for task ':app:testCoreLiteDebugUnitTest'.
    > There were failing tests. See the report at: file:///home/runner/work/AutoHost/AutoHost/app/build/reports/tests/testCoreLiteDebugUnitTest/index.html
