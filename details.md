# CI details - 0 distinct Kotlin compile errors, 4 failing tests

## All Kotlin compile errors (sorted, de-duplicated)

## All failing tests
- [app] MacroEditorJourneyTest.the task picker opens with a search box and closes - java.lang.AssertionError: wait for [Search] -> AppNotIdleException: Compose did not get idle after 173988 attempts in 60 SECONDS. Please check your measure/layout lambdas, they m
- [app] MacroEditorJourneyTest.an optional app starts on any app and its list can clear the choice - java.lang.AssertionError: wait for [Search] -> AppNotIdleException: Compose did not get idle after 62797 attempts in 60 SECONDS. Please check your measure/layout lambdas, they ma
- [app] MacroEditorJourneyTest.the app picker opens with a search box and closes - java.lang.AssertionError: wait for [Search] -> AppNotIdleException: Compose did not get idle after 5547 attempts in 60 SECONDS. Please check your measure/layout lambdas, they may
- [app] MacroEditorJourneyTest.every kind of step can be added and draws its fields - java.lang.AssertionError: wait for [Write to the run log] -> ComposeTimeoutException: Condition still not satisfied after 40000 ms

## Gradle 'What went wrong' blocks
### 1
    Execution failed for task ':app:testCoreLiteDebugUnitTest'.
    > There were failing tests. See the report at: file:///home/runner/work/AutoHost/AutoHost/app/build/reports/tests/testCoreLiteDebugUnitTest/index.html
