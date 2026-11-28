# CI details - 11 distinct Kotlin compile errors, 0 failing tests

## All Kotlin compile errors (sorted, de-duplicated)
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

## All failing tests

## Gradle 'What went wrong' blocks
### 1
    Execution failed for task ':app:compileCorePyDebugUnitTestKotlin'.
    > A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
       > Compilation error. See log for more details
### 2
    Execution failed for task ':app:compileCoreLiteDebugUnitTestKotlin'.
    > A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
       > Compilation error. See log for more details
