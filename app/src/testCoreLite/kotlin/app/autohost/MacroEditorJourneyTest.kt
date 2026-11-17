package app.autohost

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.autohost.ui2.macro.MacroSteps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Composes the real quick-macro editor (opened from the Build tab) the way a person would use it: pick a "when" that
 * needs an app, add a value, add every kind of step, open the app picker and the task picker, press Save on an
 * unfinished macro. A layout-time crash in any of the new fields or dialogs fails here, not on a phone (UiJourneyTest
 * explains why). The window is made 3600 dp tall so the whole editor and the 11-item step menu fit without scrolling:
 * this checks that the screens draw and react, not that they fit a small phone (still unverified).
 * SDK 35 for the same reason as [LaunchSmokeTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3600dp")
class MacroEditorJourneyTest {

    @get:Rule val compose = createEmptyComposeRule()

    private fun shown(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 40_000) { shown(text, substring) }
    }

    private fun waitUntilGone(text: String) {
        compose.waitUntil(timeoutMillis = 40_000) { !shown(text) }
    }

    private fun clickText(text: String) {
        compose.onAllNodes(hasText(text) and hasClickAction()).onFirst().performClick()
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

    /** Opens a picker dialog by the text on its button, checks it drew its search box, and closes it without choosing. */
    private fun openAndCloseDialog(buttonText: String) {
        clickText(buttonText)
        waitForText("Search")
        clickText("Close")
        waitUntilGone("Search")
    }

    @Test fun `the macro editor draws every kind of step, opens the pickers and refuses an unfinished macro in plain words`() {
        markOnboarded()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForTabBar()
            clickText("Build")
            waitForText("Quick macro")
            clickText("Quick macro")
            waitForText("+ Add step")

            // A "when" that needs an app shows an app button, not a text box asking for a package name.
            clickText("Every day at a time")
            waitForText("An app is opened")
            clickText("An app is opened")
            waitForText("App: choose an app")
            openAndCloseDialog("App: choose an app")

            // A "when" with an optional app starts on "any app" and its list has a first row that clears the choice.
            clickText("An app is opened")
            waitForText("A notification arrives")
            clickText("A notification arrives")
            waitForText("From app (optional): Any app")
            clickText("From app (optional): Any app")
            waitForText("Search")
            waitForText("Any app")
            clickText("Any app")
            waitUntilGone("Search")

            clickText("+ Add a value")
            waitForText("Default value")

            for (label in MacroSteps.menu().map { it.label }) {
                clickText("+ Add step")
                waitForText(label)
                clickText(label)
            }
            waitForText("Text to wait for")
            waitForText("Button text")
            waitForText("Field label (blank: the field you just tapped)")
            waitForText("Direction: Down")
            waitForText("Key: Back")
            waitForText("Values to hand over")
            waitForText("Wait up to (seconds, 1-600)")
            waitForText("Keep its answer as (optional name)")
            waitForText("Result word, for example DONE")
            waitForText("Values to return")

            openAndCloseDialog("App: choose an app")
            openAndCloseDialog("Task to run: choose a task")

            clickText("Save")
            waitForText("Give the macro a name.")
            waitForText("needs an app: choose one from the list.", substring = true)
            assertFalse("no package names for ordinary people", shown("package", substring = true))
            assertFalse("no package names for ordinary people", shown("com.android", substring = true))

            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }
}
