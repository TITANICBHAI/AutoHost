# S03 — Prove the app starts (milestone M2). Branch `work/s03-launch`. No emulator (D-24): this runs on the JVM.
The app has never been launched. `AutoHostApp` already catches `AppGraph` failures into `startupFailure` and `MainActivity` shows a crash screen — a test can assert that nothing failed.
1. `gradle :app:assembleCoreLiteDebug` has **never been built by CI** (only `corePy`). Add it to a CI step (or run the unit-test task below, which compiles it) and fix what breaks.
   The `lite` flavor has no Chaquopy native code, so it can run on the JVM.
2. Add test dependencies for the newest stable `org.robolectric:robolectric` and `androidx.test:core` (look up versions, record them in `docs/VERSIONS.md`; if Robolectric does not support
   API 36 yet, annotate the test `@Config(sdk = [35])`). `unitTests.isIncludeAndroidResources = true` is already set in `app/build.gradle.kts`.
3. Create **`app/src/testCoreLite/kotlin/app/autohost/LaunchSmokeTest.kt`** (combined-flavor source set: it must not compile into `corePy`):
   ```kotlin
   @RunWith(RobolectricTestRunner::class)
   class LaunchSmokeTest {
       @Test fun `the application builds its graph without a startup failure`() {
           val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<AutoHostApp>()
           assertNull("AppGraph failed: ${app.startupFailure}", app.startupFailure)
       }
       @Test fun `main activity starts and stays open`() {
           val c = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
           assertFalse(c.get().isFinishing)
       }
   }
   ```
4. Append `:app:testCoreLiteDebugUnitTest` to `scripts/unit-test-tasks.txt` (`tests/test_ci_task_list.py` already allows it).
5. Obstacles to expect **[inferred]**: a native library loaded while the graph is built (QuickJS — make that factory lazy, which is an improvement anyway), system-service calls
   (Robolectric shadows most), Room (works). Each obstacle is a real "would crash at launch" finding: 3 attempts, then report with the exact error.
**Gate:** the smoke test is green in CI. If it cannot be made to work, STATUS says exactly why and recommends the phone session earlier (owner decision 1).
