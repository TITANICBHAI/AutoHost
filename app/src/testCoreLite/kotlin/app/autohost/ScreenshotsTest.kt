package app.autohost

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * INT-S1: a screenshot rig. Nobody on this project had ever SEEN the app: the UI was built blind and checked only by tests
 * that look for words. This walks the real app (the welcome, every onboarding step, the five tabs, "What this phone
 * allows") on a phone-sized and on a small (320 dp wide) screen with Robolectric's native graphics and writes each screen
 * as a PNG file in `app/build/screenshots`, plus a short `_report-<size>.txt`. The workflow publishes that folder to the
 * `ci-screens` branch (one forced commit, no history), so a screen can be looked at without a phone.
 *
 * It is a diagnostic for hierarchy, fit and colour, NOT a pixel-exact picture of a real phone (fonts, spacing and the
 * system bars differ). It fails only when no screen drew at all (every image blank or no image written), and a shot that
 * cannot be taken is recorded in the report instead of stopping the walk. SDK 35 for the same reason as [LaunchSmokeTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotsTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val outDir = File("build/screenshots").apply { mkdirs() }
    private val report = mutableListOf<String>()
    private var counter = 0
    private var drewSomething = false

    private fun shown(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 40_000) { shown(text, substring) }
    }

    private fun clickText(text: String) {
        compose.onAllNodes(hasText(text) and hasClickAction()).onFirst().performClick()
    }

    private fun scrollUntilShown(text: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
        waitForText(text)
    }

    private fun waitForTabBar() {
        compose.waitUntil(timeoutMillis = 40_000) {
            compose.onAllNodes(hasText("Build") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Distinct colours on a coarse grid: a blank (one-colour) image means the graphics did not draw. */
    private fun colourCount(bitmap: Bitmap): Int {
        val seen = HashSet<Int>()
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                seen.add(bitmap.getPixel(x, y))
                x += 6
            }
            y += 6
        }
        return seen.size
    }

    private fun shot(prefix: String, name: String) {
        counter++
        val label = "%s-%02d-%s".format(prefix, counter, name)
        try {
            compose.waitForIdle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val file = File(outDir, "$label.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val colours = colourCount(bitmap)
            if (colours > 3) drewSomething = true
            report.add("ok   $label ${bitmap.width}x${bitmap.height} px, ${file.length()} bytes, $colours colours")
        } catch (e: Throwable) {
            report.add("FAIL $label: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()?.take(300)}")
        }
    }

    private fun walk(prefix: String) {
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitForText("Welcome to AutoHost")
                shot(prefix, "welcome")
                clickText("Set up my phone")
                waitForText("Step 1 of", substring = true)
                var step = 1
                shot(prefix, "step-$step")
                var guard = 0
                while (!shown("Almost there") && guard++ < 12) {
                    clickText("Skip for now")
                    step++
                    compose.waitForIdle()
                    if (!shown("Almost there")) shot(prefix, "step-$step")
                }
                waitForText("Almost there")
                shot(prefix, "almost-there")
                clickText("Open AutoHost")
                waitForTabBar()
                waitForText("AutoHost")
                shot(prefix, "tab-tasks")
                clickText("Agent"); waitForText("Ask the agent"); shot(prefix, "tab-agent")
                clickText("Build"); waitForText("Quick macro"); shot(prefix, "tab-build")
                clickText("Activity"); waitForText("Nothing has run yet"); shot(prefix, "tab-activity")
                clickText("Settings"); waitForText("AI AND AGENT"); shot(prefix, "tab-settings")
                scrollUntilShown("What this phone allows")
                clickText("What this phone allows")
                waitForText("abilities work now", substring = true)
                shot(prefix, "phone-allows")
            }
        } catch (e: Throwable) {
            report.add("STOPPED: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()?.take(300)}")
        } finally {
            File(outDir, "_report-$prefix.txt").writeText(report.joinToString("\n") + "\n")
        }
        assertTrue("no screen drew, see the report: $report", drewSomething)
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-hdpi")
    fun `the app walked on a phone sized screen is photographed`() {
        walk("phone")
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-hdpi")
    fun `the app walked on a small screen is photographed`() {
        walk("small")
    }
}
