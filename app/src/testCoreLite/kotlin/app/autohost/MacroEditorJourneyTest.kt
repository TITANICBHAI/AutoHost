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
 * Composes the real quick-macro editor (opened from the Build tab) the way a person would use it, in small tests so a
 * failure names the part that broke: the editor itself, the two drop-down menus, the app picker, the task picker, every
 * step kind, and Save on an unfinished macro. A layout-time crash in any of the new fields or dialogs fails here, not on
 * a phone (UiJourneyTest explains why). The window is 3600 dp tall so the whole editor and the 11-item step menu fit
 * without scrolling: this checks that the screens draw and react, not that they fit a small phone (still unverified).
 * Every wait and click names itself in its failure message, because the first CI run of this file failed with "Compose
 * did not get idle" and nothing said where.
 * SDK 35 for the same reason as [LaunchSmokeTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3600dp")
class MacroEditorJourneyTest {

    @get:Rule val compose = createEmptyComposeRule()

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

    private fun clickText(text: String) = at("click [$text]") {
        compose.onAllNodes(hasText(text) and hasClickAction()).onFirst().performClick()
    }

    private fun waitForTabBar() = at("wait for the tab bar") {
        compose.waitUntil(timeoutMillis = 40_000) {
            compose.onAllNodes(hasText("Build") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun markOnboarded() {
        val app = ApplicationProvider.getApplicationContext<AutoHostApp>()
        runBlocking { app.graph.setOnboarded(true) }
    }

    /** Launches the app as a returning person, goes Build, Quick macro, and runs [body] with the editor open. */
    private fun withEditor(body: (ActivityScenario<MainActivity>) -> Unit) {
        markOnboarded()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForTabBar()
            clickText("Build")
            waitForText("Quick macro")
            clickText("Quick macro")
            waitForText("+ Add step")
            body(scenario)
            scenario.onActivity { assertFalse("the editor must not close by itself", it.isFinishing) }
        }
    }

    private fun chooseWhen(current: String, wanted: String) {
        clickText(current)
        waitForText(wanted)
        clickText(wanted)
    }

    private fun addStep(label: String) {
        clickText("+ Add step")
        waitForText(label)
        clickText(label)
    }

    /** Opens a picker dialog by the text on its button, checks it drew its search box, and closes it without choosing. */
    private fun openAndCloseDialog(buttonText: String) {
        clickText(buttonText)
        waitForText("Search")
        clickText("Close")
        waitUntilGone("Search")
    }

    @Test fun `the editor opens from the Build tab and shows its sections`() = withEditor {
        waitForText("Every day at a time")
        waitForText("VALUES IT CAN BE GIVEN") // SectionHeader shows its text in capitals
        waitForText("Save")
    }

    @Test fun `the when menu opens and a when that needs an app shows an app button`() = withEditor {
        chooseWhen("Every day at a time", "An app is opened")
        waitForText("App: choose an app")
        assertFalse("no package names for ordinary people", shown("package", substring = true))
    }

    @Test fun `the step menu adds a step that needs no app`() = withEditor {
        addStep("Write to the run log")
        waitForText("Message")
    }

    @Test fun `the app picker opens with a search box and closes`() = withEditor {
        chooseWhen("Every day at a time", "An app is opened")
        waitForText("App: choose an app")
        openAndCloseDialog("App: choose an app")
    }

    @Test fun `the task picker opens with a search box and closes`() = withEditor {
        addStep("Run another task")
        waitForText("Task to run: choose a task")
        openAndCloseDialog("Task to run: choose a task")
    }

    @Test fun `an optional app starts on any app and its list can clear the choice`() = withEditor {
        chooseWhen("Every day at a time", "A notification arrives")
        waitForText("From app (optional): Any app")
        clickText("From app (optional): Any app")
        waitForText("Search")
        waitForText("Any app")
        clickText("Any app")
        waitUntilGone("Search")
    }

    @Test fun `every kind of step can be added and draws its fields`() = withEditor {
        clickText("+ Add a value")
        waitForText("Default value")
        for (label in MacroSteps.menu().map { it.label }) addStep(label)
        waitForText("App: choose an app")
        waitForText("Text to wait for")
        waitForText("Button text")
        waitForText("Field label (blank: the field you just tapped)")
        waitForText("Direction: Down")
        waitForText("Key: Back")
        waitForText("Task to run: choose a task")
        waitForText("Values to hand over")
        waitForText("Wait up to (seconds, 1-600)")
        waitForText("Keep its answer as (optional name)")
        waitForText("Result word, for example DONE")
        waitForText("Values to return")
    }

    @Test fun `saving an unfinished macro says what is missing in plain words`() = withEditor {
        addStep("Open app")
        clickText("Save")
        waitForText("Give the macro a name.")
        waitForText("needs an app: choose one from the list.", substring = true)
        assertFalse("no package names for ordinary people", shown("package", substring = true))
        assertFalse("no package names for ordinary people", shown("com.android", substring = true))
    }
}
