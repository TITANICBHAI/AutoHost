package app.autohost

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first real device run crashed in a LazyColumn that two lists had given the same key ("Key NOTIFICATIONS was already
 * used"): the activity-only smoke test never lays a screen out, so CI was green. These journeys compose the real
 * MainActivity, walk the onboarding, visit every tab and the access screen and SCROLL, so a layout-time crash fails here.
 * SDK 35 for the same reason as [LaunchSmokeTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiJourneyTest {

    @get:Rule val compose = createEmptyComposeRule()

    private fun shown(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 40_000) { shown(text, substring) }
    }

    private fun clickText(text: String) {
        compose.onAllNodes(hasText(text) and hasClickAction()).onFirst().performClick()
    }

    /** Swipes the first scrollable list up until [text] is on screen (a lazy list only composes what is visible). */
    private fun scrollUntilShown(text: String) {
        var guard = 0
        while (!shown(text) && guard++ < 20) {
            compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp() }
        }
        waitForText(text)
    }

    private fun waitForTabBar() {
        compose.waitUntil(timeoutMillis = 40_000) {
            compose.onAllNodes(hasText("Build") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun markOnboarded() {
        val app = ApplicationProvider.getApplicationContext<AutoHostApp>()
        runBlocking { app.graph.setOnboarded(true) }
    }

    @Test fun `first launch shows the welcome, the steps walk to the end, and then all five tabs draw`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("Welcome to AutoHost")
            clickText("Set up my phone")
            waitForText("Step 1 of", substring = true)
            var guard = 0
            while (!shown("Almost there") && guard++ < 12) clickText("Skip for now")
            waitForText("Almost there")
            clickText("Open AutoHost")
            waitForTabBar()

            waitForText("AutoHost") // Tasks tab header
            clickText("Agent"); waitForText("Ask the agent")
            clickText("Build"); waitForText("Quick macro")
            clickText("Activity"); waitForText("Nothing has run yet")
            clickText("Settings"); waitForText("AI AND AGENT")
            clickText("Tasks"); waitForText("AutoHost")
        }
    }

    @Test fun `a returning person goes straight to the tabs and never sees the welcome`() {
        markOnboarded()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForTabBar()
            assertFalse("welcome must not flash for a returning person", shown("Welcome to AutoHost"))
        }
    }

    @Test fun `the access screen draws and survives a long scroll, which is where the first crash happened`() {
        markOnboarded()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForTabBar()
            clickText("Settings"); waitForText("AI AND AGENT")
            scrollUntilShown("What this phone allows")
            clickText("What this phone allows")
            waitForText("abilities work now", substring = true)
            repeat(12) {
                compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp() }
            }
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }

    @Test fun `the welcome can be replayed from settings`() {
        markOnboarded()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForTabBar()
            clickText("Settings"); waitForText("AI AND AGENT")
            scrollUntilShown("Replay the welcome steps") // the row sits at the bottom of the hub
            clickText("Replay the welcome steps")
            waitForText("Welcome to AutoHost")
            assertTrue(shown("Set up my phone"))
        }
    }
}
