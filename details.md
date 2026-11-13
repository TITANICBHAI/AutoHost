# CI details - 0 distinct Kotlin compile errors, 4 failing tests

## All Kotlin compile errors (sorted, de-duplicated)

## All failing tests
- [app] AccessPlanTest.routes without a computer come first - java.lang.AssertionError: sdk 29: [RouteInfo(route=ROOT, needsComputer=false, implemented=true, note=Only if the phone is rooted.), RouteInfo(route=SHIZUKU, needsComputer=true, implemented=true, note=On Android 10 and lower Shizuku needs a computer to start it after every reboot.), RouteInfo(route=WIRELESS_DEBUGGING, needsComputer=false, implemented=false, note=Needs Android 11 or newer.), RouteI…
- [app] FeatureCatalogTest.every capability in the contract has exactly one entry in the catalog and vice versa - java.lang.AssertionError: in the contract but not the catalog expected:<[]> but was:<[device.intent]>
- [app] AccessPlanTest.routes without a computer come first - java.lang.AssertionError: sdk 29: [RouteInfo(route=ROOT, needsComputer=false, implemented=true, note=Only if the phone is rooted.), RouteInfo(route=SHIZUKU, needsComputer=true, implemented=true, note=On Android 10 and lower Shizuku needs a computer to start it after every reboot.), RouteInfo(route=WIRELESS_DEBUGGING, needsComputer=false, implemented=false, note=Needs Android 11 or newer.), RouteI…
- [app] FeatureCatalogTest.every capability in the contract has exactly one entry in the catalog and vice versa - java.lang.AssertionError: in the contract but not the catalog expected:<[]> but was:<[device.intent]>

## Gradle 'What went wrong' blocks
### 1
    Execution failed for task ':app:testCorePyDebugUnitTest'.
    > There were failing tests. See the report at: file:///home/runner/work/AutoHost/AutoHost/app/build/reports/tests/testCorePyDebugUnitTest/index.html
### 2
    Execution failed for task ':app:testCoreLiteDebugUnitTest'.
    > There were failing tests. See the report at: file:///home/runner/work/AutoHost/AutoHost/app/build/reports/tests/testCoreLiteDebugUnitTest/index.html
