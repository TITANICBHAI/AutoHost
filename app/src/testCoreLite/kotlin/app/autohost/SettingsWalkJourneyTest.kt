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
 *
 * One app launch walks all the rows (INT-T3): the nine single-row tests each launched the whole app and, with the
 * other journey tests, took the app's test step past its 8 minute limit. A failure still names the row and the step.
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

    /** One Settings row: what the row says, what the screen it opens shows, and that screen's own back button if any. */
    private data class Row(val row: String, val opens: String, val ownBack: String? = null, val opensSubstring: Boolean = false)

    private val rows = listOf(
        Row("AI model (your own key)", opens = "AI features", ownBack = "back"),
        Row("Ask the agent", opens = "Target apps", opensSubstring = true),
        Row("Runs and history", opens = "Runs", ownBack = "Back"),
        Row("Quick macro", opens = "VALUES IT CAN BE GIVEN"),
        Row("Schedules and triggers", opens = "Schedules", ownBack = "← Tasks"),
        Row("Tiles, shortcuts and bubble", opens = "Surfaces", ownBack = "‹ Back"),
        Row("What this phone allows", opens = "What this phone allows", ownBack = "← Back"),
        Row("Health check", opens = "Setup & Health"),
        Row("Security, backup and developer", opens = "SCHEDULER"),
    )

    // ---- the control: the check that Back "did not close the app" can fail -------------------------------------------

    @Test fun `on the first screen the phone's Back button does leave the app`() = asReturningPerson { scenario ->
        at("the phone's Back button on the first screen") {
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                assertTrue("on the first screen Back is meant to leave the app", activity.isFinishing)
            }
        }
    }

    // ---- every screen the Settings list leads to ------------------------------------------------------------------------

    @Test fun `every Settings screen opens and Back returns to the Settings list`() = onSettingsList { scenario ->
        for (r in rows) {
            at("row [${r.row}]") {
                clickText(r.row)
                waitUntilGone(HUB)
                waitForText(r.opens, r.opensSubstring)
                phoneBack(scenario, r.row)
                waitForText(HUB)
                r.ownBack?.let { ownBack ->
                    clickText(r.row)
                    waitUntilGone(HUB)
                    waitForText(r.opens, r.opensSubstring)
                    clickText(ownBack)
                    waitForText(HUB)
                }
            }
        }
    }

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
