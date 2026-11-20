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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pop-up experiment. The first picker used an `AlertDialog` with a text box and a lazy list; the three tests that
 * opened it failed with "Compose did not get idle" (runs 39 and 40), so the pickers became inline. "Add a task" is the
 * first thing a new person does and it is a pop-up with three plain buttons and no text box. If these tests pass, the
 * hang was about the text box or the list inside a dialog, not dialogs in general; if they fail the same way, no pop-up
 * in the app can be driven by a JVM test and this file says so. Same window and conventions as [SettingsWalkJourneyTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3600dp")
class AddTaskDialogJourneyTest {

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

    private fun onSettingsList(body: (ActivityScenario<MainActivity>) -> Unit) {
        runBlocking { app.graph.setOnboarded(true) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForTabBar()
            clickText("Settings")
            waitForText("AI AND AGENT")
            body(scenario)
        }
    }

    @Test fun `Add a task opens a pop-up with three ways to add one and Close shuts it`() = onSettingsList {
        clickText("Add a task")
        waitForText("None of these need a computer.")
        waitForText("From a template")
        waitForText("Quick macro (when", substring = true)
        clickText("Close")
        waitUntilGone("None of these need a computer.")
        waitForText("AI AND AGENT")
    }

    @Test fun `Quick macro from the pop-up opens the editor and Back returns to Settings`() = onSettingsList { scenario ->
        clickText("Add a task")
        waitForText("None of these need a computer.")
        clickText("Quick macro (when", substring = true)
        waitUntilGone("None of these need a computer.")
        waitForText("VALUES IT CAN BE GIVEN")
        at("the phone's Back button on the quick macro editor") {
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                assertFalse("the phone's Back button closed the whole app", activity.isFinishing)
            }
        }
        waitForText("AI AND AGENT")
    }
}
