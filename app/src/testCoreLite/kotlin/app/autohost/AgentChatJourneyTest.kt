package app.autohost

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.autohost.core.engine.orchestrator.RunRecord
import app.autohost.core.llm.ProviderProfile
import app.autohost.core.model.BrokerEvent
import app.autohost.core.model.RunStatus
import app.autohost.ui2.ReasonCopy
import app.autohost.ui2.chat.ChatEntry
import app.autohost.ui2.chat.ChatTranscript
import app.autohost.ui2.chat.ChatTranscriptStore
import app.autohost.ui2.chat.REFUSAL_FIX_TAG
import app.autohost.ui2.chat.REFUSAL_TAG
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Composes the real Agent tab (the chat) the way a person meets it, in small tests so a failure names the part that
 * broke. Runs are seeded straight into the run store and the saved conversation, so no agent runs: this checks that the
 * chat draws a conversation, never shows a tool name, a code or an exception, and reacts to its buttons. Whether the agent
 * itself does the job is not tested here (and has never been seen on a phone). Same window and conventions as
 * [MacroEditorJourneyTest]: 3600 dp tall so nothing needs scrolling, and every wait and click names itself when it fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3600dp")
class AgentChatJourneyTest {

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

    private fun clickText(text: String) = at("click [$text]") {
        compose.onAllNodes(hasText(text) and hasClickAction()).onFirst().performClick()
    }

    private fun waitForTabBar() = at("wait for the tab bar") {
        compose.waitUntil(timeoutMillis = 40_000) {
            compose.onAllNodes(hasText("Build") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun sendIsOn(): Boolean =
        compose.onAllNodes(hasText("Send") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()

    private fun textOfTag(tag: String): String = at("read the text tagged [$tag]") {
        compose.onNode(hasTestTag(tag)).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    }

    // ---- seeding: the run store and the saved conversation, before the activity starts ----------------------------------

    private fun event(kind: String, vararg fields: Pair<String, Any>) = BrokerEvent(
        type = "log",
        data = buildJsonObject {
            put("kind", kind)
            for ((key, value) in fields) {
                when (value) {
                    is Boolean -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        },
    )

    private fun thought(text: String) = event("agent.thought", "text" to text)
    private fun action(tool: String) = event("agent.action", "tool" to tool, "args" to "package=com.example.secret.shop")
    private fun result(tool: String) = event("agent.result", "tool" to tool, "ok" to true)

    private fun seedRun(
        runId: String,
        status: RunStatus,
        events: List<BrokerEvent> = emptyList(),
        reason: String? = null,
        message: String? = null,
        stage: String? = null,
    ) {
        val store = app.graph.runStore
        store.create(
            RunRecord(
                runId = runId, taskId = "builtin.agent.adhoc", taskName = "Ask", profileId = "p1",
                status = status, startedAtMs = 1_000L, stage = stage, reason = reason, message = message,
            ),
        )
        for (e in events) store.appendEvent(runId, e)
    }

    private fun saveChat(transcript: ChatTranscript) {
        app.getSharedPreferences(ChatTranscriptStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(ChatTranscriptStore.KEY, transcript.toJson()).commit()
    }

    private fun savedChat(): ChatTranscript =
        ChatTranscriptStore(app.getSharedPreferences(ChatTranscriptStore.PREFS_NAME, Context.MODE_PRIVATE)).load()

    private fun oneGoal(runId: String, goal: String) = ChatTranscript(entries = listOf(ChatEntry(runId, goal, 1_000L)))

    private val profile = ProviderProfile(id = "p1", label = "P1", baseUrl = "https://api.example.com/v1", model = "m1")

    private fun markOnboarded() {
        runBlocking { app.graph.setOnboarded(true) }
    }

    /** Launches the app as a returning person, opens the Agent tab, and runs [body] with the chat showing. */
    private fun withChat(seed: () -> Unit = {}, body: () -> Unit) {
        markOnboarded()
        seed()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForTabBar()
            clickText("Agent")
            waitForText("Ask the agent")
            body()
            scenario.onActivity { assertFalse("the chat must not close the app", it.isFinishing) }
        }
    }

    // ---- the empty chat and what stands in the way of the first message ---------------------------------------------------

    @Test fun `the Agent tab opens as a chat with examples and a message box`() = withChat {
        waitForText("Tell the agent what to do on your phone.")
        waitForText("Try one of these:")
        waitForText("Tell the agent what to do") // the message box label
        waitForText("Send")
        assertFalse("no package names for ordinary people", shown("package", substring = true))
    }

    @Test fun `an example fills the message box`() = withChat {
        val example = "Open Settings and turn on dark mode"
        clickText(example)
        at("the example reaches the message box") {
            compose.waitUntil(timeoutMillis = 40_000) {
                compose.onAllNodes(hasText(example)).fetchSemanticsNodes().size >= 2 // the button and the box
            }
        }
    }

    @Test fun `without an AI model the chat asks for one first and Send stays off`() = withChat {
        waitForText("Add an AI model first.", substring = true)
        waitForText("Set up an AI model")
        at("Send is off") { compose.onAllNodes(hasText("Send")).onFirst().assertIsNotEnabled() }
    }

    @Test fun `with a model but AI switched off the chat points to the AI settings`() = withChat(
        seed = { app.graph.aiProfileStore.save(listOf(profile)) },
    ) {
        waitForText("AI is switched off.", substring = true)
        waitForText("Open AI settings")
    }

    // ---- a conversation drawn from saved runs ------------------------------------------------------------------------------

    @Test fun `a finished run shows the goal, plain steps and its outcome`() = withChat(
        seed = {
            seedRun(
                "run-ok", RunStatus.SUCCEEDED,
                listOf(
                    thought("I will open the shop app first"),
                    action("app_launch"), result("app_launch"),
                    action("ui_click"), result("ui_click"),
                ),
                message = "Opened the shop app and tapped Buy",
            )
            saveChat(oneGoal("run-ok", "Open the shop app and tap Buy"))
        },
    ) {
        waitForText("Open the shop app and tap Buy")
        waitForText("I will open the shop app first")
        waitForText("Opening an app")
        waitForText("Tapping something")
        waitForText("Done.")
        waitForText("Opened the shop app and tapped Buy")
        waitForText("Open details")
        assertFalse("no tool names", shown("app_launch", substring = true) || shown("ui_click", substring = true))
        assertFalse("no package names", shown("com.example", substring = true) || shown("package", substring = true))
    }

    @Test fun `a failed run explains itself in the catalog's words and never shows a code or an exception`() = withChat(
        seed = {
            seedRun("run-bad", RunStatus.FAILED, reason = "BUDGET_TURNS", message = "java.lang.IllegalStateException: boom")
            saveChat(oneGoal("run-bad", "Do something long"))
        },
    ) {
        waitForText("The agent could not finish.")
        waitForText(ReasonCopy.text("BUDGET_TURNS")!!)
        assertFalse("no exception text", shown("boom", substring = true) || shown("IllegalState", substring = true))
        assertFalse("no reason code", shown("BUDGET_TURNS", substring = true))
        assertFalse(
            "the catalog names no fix for this reason, so no fix button",
            shown("Open AI settings") || shown("Open accessibility settings") || shown("Review permissions"),
        )
    }

    @Test fun `a run that failed because the AI refused the key offers Open AI settings and the button opens them`() = withChat(
        seed = {
            seedRun("run-key", RunStatus.FAILED, reason = "MODEL_AUTH")
            saveChat(oneGoal("run-key", "Do something"))
        },
    ) {
        waitForText("The agent could not finish.")
        waitForText(ReasonCopy.text("MODEL_AUTH")!!)
        waitForText("Open AI settings")
        clickText("Open AI settings")
        waitForText("AI features")
    }

    @Test fun `a run in progress says what it is doing and offers Stop instead of Send`() = withChat(
        seed = {
            seedRun("run-live", RunStatus.RUNNING, listOf(action("ui_snapshot")), stage = "think")
            saveChat(oneGoal("run-live", "Check the weather"))
        },
    ) {
        waitForText("Check the weather")
        waitForText("Reading the screen")
        waitForText("Thinking…")
        waitForText("Stop")
        assertFalse("Send is replaced by Stop while a run is going", shown("Send"))
    }

    @Test fun `a run the app no longer remembers says so`() = withChat(
        seed = { saveChat(oneGoal("run-lost", "A goal from yesterday")) },
    ) {
        waitForText("A goal from yesterday")
        waitForText("The details of this run were not kept after the app was closed.")
    }

    @Test fun `Clear chat empties the conversation and the saved copy`() = withChat(
        seed = {
            seedRun("run-old", RunStatus.SUCCEEDED, listOf(thought("done long ago")))
            saveChat(oneGoal("run-old", "An old goal"))
        },
    ) {
        waitForText("An old goal")
        clickText("Clear chat")
        waitUntilGone("An old goal")
        waitForText("Try one of these:")
        assertTrue("the saved copy is empty too", savedChat().entries.isEmpty())
    }

    // ---- a person who has a model, with AI switched on ---------------------------------------------------------------------

    @Test fun `with a model and an app the person can type and Send never shows a code`() = withChat(
        seed = {
            app.graph.aiProfileStore.save(listOf(profile))
            runBlocking { app.graph.refreshAiFacts(enabled = true, profiles = listOf(profile), cleartextLoopback = false) }
            // this app is the one app a test phone lists, so it is the one remembered app that survives the "still installed" check
            saveChat(ChatTranscript(packages = listOf(app.packageName), profileId = "p1"))
        },
    ) {
        waitForText("Model: P1", substring = true)
        waitForText("Change")
        at("type a goal") { compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Open the shop app") }
        at("Send turns on") { compose.waitUntil(timeoutMillis = 40_000) { sendIsOn() } }
        clickText("Send")
        // the start either refuses (no accessibility, no key: the usual answer here) or begins a run; both draw something plain
        at("the start answers") {
            compose.waitUntil(timeoutMillis = 40_000) {
                compose.onAllNodes(hasTestTag(REFUSAL_TAG)).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodes(hasText("Open the shop app") and hasClickAction().not()).fetchSemanticsNodes().size >= 1 &&
                    !sendIsOn()
            }
        }
        if (compose.onAllNodes(hasTestTag(REFUSAL_TAG)).fetchSemanticsNodes().isNotEmpty()) {
            val said = textOfTag(REFUSAL_TAG)
            assertTrue("the refusal has words", said.isNotBlank())
            assertFalse("no reason code: $said", Regex("[A-Z][A-Z0-9]+_[A-Z0-9_]+").containsMatchIn(said))
            assertFalse("no exception text: $said", said.contains("Exception") || said.contains("kotlin") || said.contains("java."))
        }
    }

    @Test fun `a refusal that names a fix draws its button with the catalog's words`() = withChat(
        seed = {
            app.graph.aiProfileStore.save(listOf(profile))
            runBlocking { app.graph.refreshAiFacts(enabled = true, profiles = listOf(profile), cleartextLoopback = false) }
            saveChat(ChatTranscript(packages = listOf(app.packageName), profileId = "p1"))
        },
    ) {
        waitForText("Model: P1", substring = true)
        at("type a goal") { compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Open the shop app") }
        at("Send turns on") { compose.waitUntil(timeoutMillis = 40_000) { sendIsOn() } }
        clickText("Send")
        // no key is saved for the profile and no accessibility service runs here, so the start refuses; either reason has a fix
        at("the refusal and its button appear") {
            compose.waitUntil(timeoutMillis = 40_000) {
                compose.onAllNodes(hasTestTag(REFUSAL_FIX_TAG)).fetchSemanticsNodes().isNotEmpty()
            }
        }
        val label = textOfTag(REFUSAL_FIX_TAG)
        assertTrue("the button has the catalog's words: $label", label in setOf("Open AI settings", "Open accessibility settings", "Review permissions"))
        assertFalse("the button shows no code: $label", Regex("[A-Z][A-Z0-9]+_[A-Z0-9_]+").containsMatchIn(label))
        assertTrue("the refusal above it has words", textOfTag(REFUSAL_TAG).isNotBlank())
        if (label == "Open AI settings") {
            clickText(label)
            waitForText("AI features")
        }
    }

    @Test fun `Change opens the setup panel and Done closes it`() = withChat(
        seed = {
            app.graph.aiProfileStore.save(listOf(profile))
            runBlocking { app.graph.refreshAiFacts(enabled = true, profiles = listOf(profile), cleartextLoopback = false) }
            saveChat(ChatTranscript(packages = listOf(app.packageName), profileId = "p1"))
        },
    ) {
        clickText("Change")
        waitForText("Apps the agent may use")
        waitForText("+ Add an app")
        assertFalse("no package names for ordinary people", shown("package", substring = true))
        assertFalse("a person with no saved tasks sees no empty tasks section", shown("Saved tasks the agent may start"))
        clickText("Done")
        waitUntilGone("Apps the agent may use")
    }

    // ---- INT-E2: the saved tasks the agent may start ---------------------------------------------------------------------

    @Test fun `a saved task can be allowed for the agent from the setup panel and taken away again`() = withChat(
        seed = {
            app.graph.aiProfileStore.save(listOf(profile))
            runBlocking { app.graph.refreshAiFacts(enabled = true, profiles = listOf(profile), cleartextLoopback = false) }
            saveChat(ChatTranscript(packages = listOf(app.packageName), profileId = "p1"))
            runBlocking {
                app.graph.data.tasks.installVersion(
                    taskId = "user.daily-clip", manifestJson = "{}", version = "1.0.0", bundlePath = "", sha256 = "",
                    importReportJson = "{}", source = "template", name = "Daily clip",
                )
            }
        },
    ) {
        waitForText("Model: P1", substring = true)
        assertFalse("nothing is allowed until the person says so", shown("Tasks: Daily clip", substring = true))
        clickText("Change")
        waitForText("Saved tasks the agent may start")
        clickText("+ Add a task")
        clickText("Daily clip")
        waitForText("Daily clip  ✕")
        waitForText("Tasks: Daily clip", substring = true)
        assertFalse("no id for ordinary people", shown("user.daily-clip", substring = true))
        clickText("Daily clip  ✕")
        waitUntilGone("Daily clip  ✕")
        at("the summary stops naming the task") {
            compose.waitUntil(timeoutMillis = 40_000) { !shown("Tasks: Daily clip", substring = true) }
        }
        waitForText("+ Add a task")
    }
}
