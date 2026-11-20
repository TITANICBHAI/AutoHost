package app.autohost

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
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
 * Opens every screen the Settings tab leads to, as a returning person would, and comes back with the phone's Back button
 * (and with the screen's own back button where it has one). Two guards in one walk:
 *  - a crash guard: a screen that throws while it draws (like the earlier `Key "NOTIFICATIONS" was already used`) fails
 *    here, naming the screen, instead of on a phone;
 *  - a Back guard: on "What this phone allows" and "Schedules and triggers" the phone's Back button used to close the whole
 *    app, and "back" on the AI model and Tiles screens went to the developer screen instead of the Settings list.
 * It checks that each screen draws and that Back lands on the Settings list; what the screens do is tested elsewhere.
 * The control test proves the Back check can fail: on the first screen Back does leave the app. Same window and
 * conventions as [AgentChatJourneyTest]: 3600 dp tall so nothing needs scrolling, every wait and click names itself
 * when it fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3600dp")
class SettingsWalkJourneyTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val app: AutoHostApp get() = ApplicationProvider.getApplicationContext<AutoHostApp>()

    private fun <T> at(what: String, block: () -> T): T =
        try {
            block()
        } catch (t: Throwable) {
            throw AssertionError("$what -> ${t.javaClass.simpleName}: ${(t.message ?: "").take(110)}", t)
        }

    private fun shown(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String, substring: Boolean = false) = at("wait for [$text]") {
        compose.waitUntil(timeoutMillis = 40_000) { shown(text, substring) }
    }

    private fun waitUntilGone(text: String) = at("wait until gone [$text]") {
        compose.waitUntil(timeoutMillis = 40_000) { !shown(text) }
    }

    private fun clickText(text: String, substring: Boolean = false) = at("click [$text]") {
        compose.onAllNodes(hasText(text, substring = substring) and hasClickAction()).onFirst().performClick()
    }

    private fun waitForTabBar() = at("wait for the tab bar") {
        compose.waitUntil(timeoutMillis = 40_000) {
            compose.onAllNodes(hasText("Build") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The phone's Back button, through the activity's own dispatcher; fails if it closed the app. */
    private fun phoneBack(scenario: ActivityScenario<MainActivity>, where: String) =
        at("the phone's Back button on [$where]") {
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                assertFalse("the phone's Back button closed the whole app", activity.isFinishing)
            }
        }

    private fun asReturningPerson(body: (ActivityScenario<MainActivity>) -> Unit) {
        runBlocking { app.graph.setOnboarded(true) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForTabBar()
            body(scenario)
        }
    }

    private fun onSettingsList(body: (ActivityScenario<MainActivity>) -> Unit) = asReturningPerson { scenario ->
        clickText("Settings")
        waitForText(HUB)
        body(scenario)
    }

    /**
     * Opens [row] from the Settings list, waits for the screen's [opens] text, returns with the phone's Back button,
     * and, when the screen has its own back button ([ownBack]), does it again with that button.
     */
    private fun walk(row: String, opens: String, ownBack: String? = null, opensSubstring: Boolean = false) =
        onSettingsList { scenario ->
            clickText(row)
            waitUntilGone(HUB)
            waitForText(opens, opensSubstring)
            phoneBack(scenario, row)
            waitForText(HUB)
            if (ownBack != null) {
                clickText(row)
                waitUntilGone(HUB)
                waitForText(opens, opensSubstring)
                clickText(ownBack)
                waitForText(HUB)
            }
        }

    // ---- the control: the check that Back "did not close the app" can fail -------------------------------------------

    @Test fun `on the first screen the phone's Back button does leave the app`() = asReturningPerson { scenario ->
        at("the phone's Back button on the first screen") {
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                assertTrue("on the first screen Back is meant to leave the app", activity.isFinishing)
            }
        }
    }

    // ---- one screen each ---------------------------------------------------------------------------------------------------

    @Test fun `AI model opens and Back returns to Settings`() =
        walk("AI model (your own key)", opens = "AI features", ownBack = "back")

    @Test fun `Ask the agent opens and Back returns to Settings`() =
        walk("Ask the agent", opens = "Target apps", opensSubstring = true)

    @Test fun `Runs and history opens and Back returns to Settings`() =
        walk("Runs and history", opens = "Runs", ownBack = "Back")

    @Test fun `Quick macro opens and Back returns to Settings`() =
        walk("Quick macro", opens = "VALUES IT CAN BE GIVEN")

    @Test fun `Schedules and triggers opens and Back returns to Settings`() =
        walk("Schedules and triggers", opens = "Schedules", ownBack = "← Tasks")

    @Test fun `Tiles, shortcuts and bubble opens and Back returns to Settings`() =
        walk("Tiles, shortcuts and bubble", opens = "Surfaces", ownBack = "‹ Back")

    @Test fun `What this phone allows opens and Back returns to Settings`() =
        walk("What this phone allows", opens = "What this phone allows", ownBack = "← Back")

    @Test fun `Health check opens and Back returns to Settings`() =
        walk("Health check", opens = "Setup & Health")

    @Test fun `Security, backup and developer opens and Back returns to Settings`() =
        walk("Security, backup and developer", opens = "SCHEDULER")

    // ---- a screen reached from somewhere other than the Settings list goes back to where the person was ----------------

    @Test fun `AI settings opened from the Agent tab go back to the chat`() = asReturningPerson { scenario ->
        clickText("Agent")
        waitForText("Set up an AI model")
        clickText("Set up an AI model")
        waitForText("AI features")
        clickText("back")
        waitForText("Tell the agent what to do on your phone.")
        clickText("Set up an AI model")
        waitForText("AI features")
        phoneBack(scenario, "AI settings opened from the chat")
        waitForText("Tell the agent what to do on your phone.")
    }

    private companion object {
        /** The first section header of the Settings list; headers are drawn in capitals. */
        const val HUB = "AI AND AGENT"
    }
}
