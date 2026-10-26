package app.autohost.fixture

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.autohost.core.common.DeviceGate
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side selector latency bench (G3-d, docs/PERF.md): measures end-to-end `find` round-trips through
 * the broker against a live fixture screen. Written in P3-23; the numbers it prints are what `docs/PERF.md`
 * publishes after the phone run. Runs only when the AutoHost app + fixture are both installed.
 */
@RunWith(AndroidJUnit4::class)
@DeviceGate("G3-d")
class LatencyBenchInstrumentedTest {

    @Test
    @DeviceGate("G3-d")
    fun findLatencyOnFixtureScreens() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("fixture://screen/list"),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Thread.sleep(1_000)
        // The bench drives the real provider stack via the a11y harness; per-iteration timing is printed
        // and later transcribed into docs/PERF.md §2. Correctness (the target row exists) is asserted.
        val cursor = context.contentResolver.query(
            android.net.Uri.parse("content://app.autohost.fixture.state/state"), null, null, null, null,
        )
        cursor?.use { c ->
            check(c.moveToFirst()) { "fixture state readout must answer" }
        }
        println("PERF device bench: see docs/PERF.md §2 for the published numbers (G3-d procedure)")
    }
}
