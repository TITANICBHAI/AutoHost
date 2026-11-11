# CI details - 0 distinct Kotlin compile errors, 14 failing tests

## All Kotlin compile errors (sorted, de-duplicated)

## All failing tests
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

## Gradle 'What went wrong' blocks
### 1
    Execution failed for task ':runtime:agent:test'.
    > There were failing tests. See the report at: file:///home/runner/work/AutoHost/AutoHost/runtime/agent/build/reports/tests/test/index.html
### 2
    Execution failed for task ':core:llm:test'.
    > There were failing tests. See the report at: file:///home/runner/work/AutoHost/AutoHost/core/llm/build/reports/tests/test/index.html
### 3
    Execution failed for task ':capabilities:shell:testDebugUnitTest'.
    > There were failing tests. See the report at: file:///home/runner/work/AutoHost/AutoHost/capabilities/shell/build/reports/tests/testDebugUnitTest/index.html
