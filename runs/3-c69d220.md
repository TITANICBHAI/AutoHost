# CI digest - run 3 - branch work/s02-green - commit c69d220
tests step outcome: cancelled

## Verdict: RED - 862 tests ran, 0 failed, 13 Kotlin compile errors, 1 failed Gradle tasks, 10 modules with no results

## Kotlin compile errors (first 40 of 13)
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

## Failed Gradle tasks
- :core:llm:compileTestKotlin

## Modules that produced no test results (compile error, timeout, or no tests)
- core/llm
- runtime/api
- runtime/agent
- runtime/python
- plugin-sdk
- capabilities/notifications
- capabilities/shell
- capabilities/vision
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
- core/broker: 42 / 0 / 0
- core/common: 21 / 0 / 0
- core/data: 5 / 0 / 0
- core/designsystem: 6 / 0 / 0
- core/devapi: 26 / 0 / 2
- core/engine: 296 / 0 / 2
- core/model: 47 / 0 / 0
- runtime/flow: 93 / 0 / 24
- runtime/js: 41 / 0 / 0

## Style baseline (report only):  ktlint/detekt findings
