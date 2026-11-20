# CI digest - run 47 - branch work/s02-green - commit 39cef7a
tests step outcome: failure

## Verdict: RED - 1419 tests ran, 0 failed, 0 Kotlin compile errors, 1 failed Gradle tasks, 4 modules with no results

## Failed Gradle tasks
- :app:testCoreLiteDebugUnitTest

## A task TIMED OUT - the last tests that started (the last one is the hung test)
- SettingsWalkJourneyTest > Health check opens and Back returns to Settings STARTED
- SettingsWalkJourneyTest > AI settings opened from the Agent tab go back to the chat STARTED
- SettingsWalkJourneyTest > Ask the agent opens and Back returns to Settings STARTED
- SettingsWalkJourneyTest > Quick macro opens and Back returns to Settings STARTED
- SettingsWalkJourneyTest > on the first screen the phone's Back button does leave the app STARTED
- SettingsWalkJourneyTest > Tiles, shortcuts and bubble opens and Back returns to Settings STARTED
- SettingsWalkJourneyTest > Schedules and triggers opens and Back returns to Settings STARTED
- SettingsWalkJourneyTest > Runs and history opens and Back returns to Settings STARTED
- SettingsWalkJourneyTest > AI model opens and Back returns to Settings STARTED
- SettingsWalkJourneyTest > Security, backup and developer opens and Back returns to Settings STARTED

## Modules that produced no test results (compile error, timeout, or no tests)
- runtime/api
- runtime/python
- plugin-sdk
- testing/fixture-app

## Per module (tests / failed / skipped)
- app: 231 / 0 / 0
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
- core/engine: 299 / 0 / 2
- core/llm: 36 / 0 / 0
- core/model: 47 / 0 / 0
- runtime/agent: 175 / 0 / 0
- runtime/flow: 117 / 0 / 24
- runtime/js: 41 / 0 / 0

## Style baseline (report only): 21652 ktlint/detekt findings
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
