package app.autohost.ui2.chat

import app.autohost.core.engine.orchestrator.RunRecord
import app.autohost.core.llm.ProviderProfile
import app.autohost.core.model.BrokerEvent
import app.autohost.core.model.RunStatus
import app.autohost.ui2.ReasonCopy
import app.autohost.ui2.pickers.TaskChoice
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatTest {

    private fun log(kind: String, vararg fields: Pair<String, Any>, level: String? = null) = BrokerEvent(
        type = "log",
        level = level,
        data = buildJsonObject {
            put("kind", kind)
            for ((key, value) in fields) {
                when (value) {
                    is Boolean -> put(key, value)
                    is Number -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        },
    )

    private fun action(tool: String, args: String = "") = log("agent.action", "tool" to tool, "args" to args)
    private fun result(tool: String, ok: Boolean) = log("agent.result", "tool" to tool, "ok" to ok)
    private fun thought(text: String) = log("agent.thought", "text" to text)

    private fun record(status: RunStatus, reason: String? = null, message: String? = null, stage: String? = null) = RunRecord(
        runId = "run-1", taskId = "builtin.agent.adhoc", taskName = "Ask", profileId = "p1",
        status = status, startedAtMs = 0L, stage = stage, reason = reason, message = message,
    )

    private fun profile(id: String) = ProviderProfile(id = id, label = id, baseUrl = "https://api.example.com/v1", model = "m1")

    private fun texts(lines: List<ChatLine>): List<String> = lines.flatMap {
        when (it) {
            is ChatLine.Thought -> listOf(it.text)
            is ChatLine.Step -> listOf(it.phrase)
            is ChatLine.Notice -> listOf(it.text)
            is ChatLine.Working -> listOf(it.text)
            is ChatLine.Outcome -> listOfNotNull(it.headline, it.detail)
        }
    }

    // ---- what a run looks like ------------------------------------------------------------------------------------

    @Test fun `a tool becomes a plain phrase and its result marks it done or failed`() {
        val lines = AgentChat.lines(
            listOf(action("app_launch"), result("app_launch", true), action("ui_set_text"), result("ui_set_text", false)),
            record(RunStatus.RUNNING),
        )
        val steps = lines.filterIsInstance<ChatLine.Step>()
        assertEquals(listOf("Opening an app", "Typing text"), steps.map { it.phrase })
        assertEquals(listOf(true, false), steps.map { it.ok })
    }

    @Test fun `a step still waiting for its result has no verdict yet`() {
        val lines = AgentChat.lines(listOf(action("ui_click")), record(RunStatus.RUNNING))
        assertNull(lines.filterIsInstance<ChatLine.Step>().single().ok)
    }

    @Test fun `a result is paired with the first waiting step of the same tool`() {
        val lines = AgentChat.lines(
            listOf(action("ui_click"), result("ui_click", true), action("ui_click"), action("ui_find"), result("ui_find", true)),
            record(RunStatus.RUNNING),
        )
        assertEquals(listOf(true, null, true), lines.filterIsInstance<ChatLine.Step>().map { it.ok })
    }

    @Test fun `a tool the table does not know reads as a generic step`() {
        val step = AgentChat.lines(listOf(action("brand_new_tool")), null).filterIsInstance<ChatLine.Step>().single()
        assertEquals(ToolPhrases.GENERIC, step.phrase)
    }

    @Test fun `every tool the agent can call has its own plain phrase`() {
        val root = Json.parseToJsonElement(repoFile("runtime/agent/src/main/resources/autohost/tool_catalog.json").readText()).jsonObject
        val tools = root.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertTrue("the agent has tools", tools.size >= 18)
        for (tool in tools) assertTrue("no phrase for $tool", tool in ToolPhrases.known)
        assertEquals("the table has no tool the agent lacks", tools.toSet(), ToolPhrases.known)
        for (tool in tools) {
            val phrase = ToolPhrases.phrase(tool)
            assertFalse(phrase, phrase.contains('_'))
            assertFalse(phrase, phrase == ToolPhrases.GENERIC)
        }
    }

    @Test fun `no tool name, argument or package name reaches a line`() {
        val lines = AgentChat.lines(
            listOf(
                action("ui_set_text", args = "text=hunter2 target=com.secret.bank.app"),
                result("ui_set_text", true),
                thought("I will open com.secret.bank.app and read the balance"),
            ),
            record(RunStatus.RUNNING),
        )
        val shown = texts(lines).joinToString(" | ")
        assertFalse(shown, shown.contains("hunter2"))
        assertFalse(shown, shown.contains("com.secret"))
        assertFalse(shown, shown.contains("ui_set_text"))
        assertTrue(shown, shown.contains("an app"))
    }

    @Test fun `a package name in the agents words becomes the apps name`() {
        val names = mapOf("com.google.android.youtube" to "YouTube", "com.google.android.youtube.music" to "YT Music")
        val line = AgentChat.lines(
            listOf(thought("Opening com.google.android.youtube.music, not com.google.android.youtube.")), null, names,
        ).filterIsInstance<ChatLine.Thought>().single()
        assertEquals("Opening YT Music, not YouTube.", line.text)
    }

    @Test fun `a thought keeps its words but loses line breaks and is cut when long`() {
        val line = AgentChat.lines(listOf(thought("First\n\n  second")), null).filterIsInstance<ChatLine.Thought>().single()
        assertEquals("First second", line.text)
        val long = AgentChat.lines(listOf(thought("word ".repeat(300))), null).filterIsInstance<ChatLine.Thought>().single()
        assertEquals(AgentChat.MAX_THOUGHT_CHARS, long.text.length)
        assertTrue(long.text.endsWith("…"))
        assertTrue(AgentChat.lines(listOf(thought("   ")), null).none { it is ChatLine.Thought })
    }

    @Test fun `the 80 percent crossing is a heads-up and the stop itself is left to the outcome`() {
        val crossing = log("agent.budget", "budget" to "turns", "fraction" to 0.8, "used" to 24, "limit" to 30, level = "warn")
        val stop = log("agent.budget", "budget" to "turns", level = "warn")
        val lines = AgentChat.lines(listOf(crossing, stop), record(RunStatus.RUNNING))
        val notices = lines.filterIsInstance<ChatLine.Notice>()
        assertEquals(1, notices.size)
        assertEquals("Heads up: the agent has used about 80% of its step limit.", notices.single().text)
    }

    @Test fun `dropped events are mentioned once`() {
        val lines = AgentChat.lines(listOf(log("agent.dropped", "dropped" to 3), log("agent.dropped", "dropped" to 9)), null)
        assertEquals(1, lines.count { it is ChatLine.Notice && it.text.contains("earlier steps") })
    }

    @Test fun `events that are not chat material are ignored`() {
        val noise = listOf(
            BrokerEvent(type = "heartbeat"),
            BrokerEvent(type = "progress"),
            BrokerEvent(type = "stage_begin", stage = "think"),
            log("agent.usage", "prompt_tokens" to 10, "completion_tokens" to 5),
            BrokerEvent(type = "log"),
        )
        assertTrue(AgentChat.lines(noise, record(RunStatus.RUNNING)).none { it !is ChatLine.Working })
    }

    // ---- how a run ends -------------------------------------------------------------------------------------------

    @Test fun `a running run says what it is doing from its stage`() {
        fun working(stage: String?) = AgentChat.lines(emptyList(), record(RunStatus.RUNNING, stage = stage)).last() as ChatLine.Working
        assertEquals("Looking at the screen…", working("observe").text)
        assertEquals("Thinking…", working("think").text)
        assertEquals("Doing the next step…", working("act").text)
        assertEquals("Working…", working(null).text)
        assertFalse(working("act").waitingForYou)
    }

    @Test fun `a run waiting for the person says so`() {
        val last = AgentChat.lines(emptyList(), record(RunStatus.WAITING_USER)).last() as ChatLine.Working
        assertTrue(last.waitingForYou)
        assertEquals("The agent is waiting for you.", last.text)
    }

    @Test fun `a finished run shows the agents own summary scrubbed of package names`() {
        val names = mapOf("com.example.notes" to "Notes")
        val last = AgentChat.lines(
            emptyList(), record(RunStatus.SUCCEEDED, message = "Saved the note in com.example.notes"), names,
        ).last() as ChatLine.Outcome
        assertEquals(ChatLine.Tone.Good, last.tone)
        assertEquals("Done.", last.headline)
        assertEquals("Saved the note in Notes", last.detail)
        assertNull((AgentChat.lines(emptyList(), record(RunStatus.SUCCEEDED)).last() as ChatLine.Outcome).detail)
    }

    @Test fun `a failure shows the catalog text for its reason and nothing else`() {
        val last = AgentChat.lines(
            emptyList(), record(RunStatus.FAILED, reason = "BUDGET_TURNS", message = "java.lang.IllegalStateException: boom"),
        ).last() as ChatLine.Outcome
        assertEquals(ChatLine.Tone.Bad, last.tone)
        assertEquals(ReasonCopy.text("BUDGET_TURNS"), last.detail)
        assertFalse(texts(listOf(last)).joinToString().contains("boom"))
    }

    @Test fun `a failure with a reason the catalog lacks never shows the exception message`() {
        val last = AgentChat.lines(
            emptyList(), record(RunStatus.FAILED, reason = "START_FAILED", message = "NullPointerException at Foo.kt:12"),
        ).last() as ChatLine.Outcome
        assertEquals("AutoHost does not have a plain explanation for why this stopped.", last.detail)
        val none = AgentChat.lines(emptyList(), record(RunStatus.FAILED, message = "secret detail")).last() as ChatLine.Outcome
        assertEquals("AutoHost does not have a plain explanation for why this stopped.", none.detail)
    }

    // ---- the button under a refusal or a failure ---------------------------------------------------------------------

    @Test fun `every reason the agent start can refuse with a fix gets a button the chat can carry out`() {
        assertEquals(
            ChatFix("Open accessibility settings", FixTarget.SystemScreen("OPEN_ACCESSIBILITY_SETTINGS")),
            AgentChat.fixFor("A11Y_SERVICE_DISABLED"),
        )
        for (reason in listOf("AI_DISABLED", "AI_NOT_CONFIGURED")) {
            assertEquals(reason, ChatFix("Open AI settings", FixTarget.AiSettings), AgentChat.fixFor(reason))
        }
        assertEquals(ChatFix("Review permissions", FixTarget.ReviewPermissions), AgentChat.fixFor("AI_GRANTS_TOO_BROAD"))
    }

    @Test fun `a reason with no fix, or a fix the chat cannot carry out, has no button`() {
        for (reason in listOf(null, "", "START_FAILED", "SCREEN_LOCKED", "UI_RUN_ACTIVE", "USER_CANCELLED", "BUDGET_TURNS")) {
            assertNull(reason, AgentChat.fixFor(reason))
        }
        // the catalog names a fix for these, but the chat has no way to perform it, so it draws no button
        assertNull(AgentChat.fixFor("NOTIFICATION_PERMISSION_DENIED"))
        assertNull(AgentChat.fixFor("BATTERY_OPTIMIZED"))
    }

    @Test fun `every fix the chat carries out is one the catalog gives words to and some reason reaches`() {
        val actions = ReasonCopy.fixTable.values.map { it.action }.toSet()
        for (action in AgentChat.supportedFixes) {
            assertTrue("$action is in the catalog", action in actions)
            val reached = ReasonCopy.fixTable.keys.any { ReasonCopy.fix(it)?.action == action && AgentChat.fixFor(it) != null }
            assertTrue("$action is reached by some reason", reached)
        }
    }

    @Test fun `a failed run offers the fix its reason names and no other outcome does`() {
        val auth = AgentChat.lines(emptyList(), record(RunStatus.FAILED, reason = "MODEL_AUTH")).last() as ChatLine.Outcome
        assertEquals("Open AI settings", auth.fix?.label)
        val lost = AgentChat.lines(emptyList(), record(RunStatus.FAILED, reason = "A11Y_LOST")).last() as ChatLine.Outcome
        assertEquals(FixTarget.SystemScreen("OPEN_ACCESSIBILITY_SETTINGS"), lost.fix?.target)
        val budget = AgentChat.lines(emptyList(), record(RunStatus.FAILED, reason = "BUDGET_TURNS")).last() as ChatLine.Outcome
        assertNull(budget.fix)
        for (status in listOf(RunStatus.SUCCEEDED, RunStatus.CANCELLED, RunStatus.INTERRUPTED, RunStatus.SKIPPED)) {
            val last = AgentChat.lines(emptyList(), record(status, reason = "MODEL_AUTH")).last() as ChatLine.Outcome
            assertNull(status.name, last.fix)
        }
    }

    @Test fun `no button shows a code, an id or a package name`() {
        for ((reason, fix) in ReasonCopy.fixTable) {
            val chat = AgentChat.fixFor(reason) ?: continue
            assertFalse(reason, Regex("[A-Z][A-Z0-9]+_[A-Z0-9_]+").containsMatchIn(chat.label))
            assertFalse(reason, chat.label.contains("package", ignoreCase = true))
            assertEquals(reason, fix.label, chat.label)
        }
    }

    @Test fun `stopping by the person is neutral and a stop by the host says why`() {
        val byPerson = AgentChat.lines(emptyList(), record(RunStatus.CANCELLED, reason = "USER_CANCELLED")).last() as ChatLine.Outcome
        assertEquals("Stopped.", byPerson.headline)
        assertEquals(ChatLine.Tone.Neutral, byPerson.tone)
        assertNull(byPerson.detail)
        assertNull((AgentChat.lines(emptyList(), record(RunStatus.CANCELLED)).last() as ChatLine.Outcome).detail)
        val byHost = AgentChat.lines(emptyList(), record(RunStatus.CANCELLED, reason = "IDLE_TIMEOUT_REASON")).last() as ChatLine.Outcome
        assertEquals(ReasonCopy.text("IDLE_TIMEOUT_REASON"), byHost.detail)
    }

    @Test fun `every run status ends the lines with something the person can read`() {
        for (status in RunStatus.values()) {
            val last = AgentChat.lines(emptyList(), record(status, reason = "BUDGET_TIME")).last()
            assertTrue(status.name, last is ChatLine.Working || last is ChatLine.Outcome)
            assertEquals(status.name, status.terminal, last is ChatLine.Outcome)
            val shown = texts(listOf(last)).joinToString(" ")
            assertTrue(status.name, shown.isNotBlank())
            assertFalse(status.name, shown.contains("BUDGET_TIME"))
        }
    }

    @Test fun `a run the store no longer holds says so once and only when nothing else is known`() {
        val gone = AgentChat.lines(emptyList(), null)
        assertEquals(1, gone.size)
        assertTrue((gone.single() as ChatLine.Notice).text.contains("not kept"))
        val kept = AgentChat.lines(listOf(thought("hello")), null)
        assertTrue(kept.none { it is ChatLine.Notice })
    }

    // ---- the whole conversation -----------------------------------------------------------------------------------

    @Test fun `each goal is followed by its own lines and every key is unique across runs`() {
        val entries = listOf(ChatEntry("run-a", "first goal", 1L), ChatEntry("run-b", "second goal", 2L))
        val same = listOf(thought("same words"), action("ui_click"), result("ui_click", true))
        val rows = AgentChat.rows(
            entries,
            recordOf = { id -> record(RunStatus.SUCCEEDED).copy(runId = id) },
            eventsOf = { same },
        )
        assertEquals(listOf("first goal", "second goal"), rows.filterIsInstance<ChatRow.Goal>().map { it.text })
        assertTrue(rows.first() is ChatRow.Goal)
        assertEquals("keys must be unique for the lazy list", rows.size, rows.map { it.key }.toSet().size)
        val second = rows.dropWhile { !(it is ChatRow.Goal && it.runId == "run-b") }
        assertTrue(second.drop(1).all { it.runId == "run-b" })
    }

    @Test fun `a row knows whether its run has ended`() {
        val entries = listOf(ChatEntry("done", "g", 1L), ChatEntry("live", "g", 2L), ChatEntry("lost", "g", 3L))
        val rows = AgentChat.rows(
            entries,
            recordOf = { id ->
                when (id) {
                    "done" -> record(RunStatus.SUCCEEDED).copy(runId = id)
                    "live" -> record(RunStatus.RUNNING).copy(runId = id)
                    else -> null
                }
            },
            eventsOf = { listOf(action("ui_click")) },
        )
        fun ended(run: String) = rows.filterIsInstance<ChatRow.Line>().filter { it.runId == run }.map { it.runFinished }.toSet()
        assertEquals(setOf(true), ended("done"))
        assertEquals(setOf(false), ended("live"))
        assertEquals(setOf(true), ended("lost"))
    }

    @Test fun `no goals means no rows`() {
        assertTrue(AgentChat.rows(emptyList(), { null }, { emptyList() }).isEmpty())
    }

    // ---- may a message be sent yet --------------------------------------------------------------------------------

    @Test fun `the gate names the first thing to fix, in order`() {
        val p = listOf(profile("p1"))
        assertEquals(AgentChat.Gate.NeedsModel, AgentChat.gate("do it", listOf("a"), true, emptyList()))
        assertEquals(AgentChat.Gate.NeedsModel, AgentChat.gate("", emptyList(), false, emptyList()))
        assertEquals(AgentChat.Gate.AiOff, AgentChat.gate("do it", listOf("a"), false, p))
        assertEquals(AgentChat.Gate.NeedsApps, AgentChat.gate("do it", emptyList(), true, p))
        assertEquals(AgentChat.Gate.NeedsGoal, AgentChat.gate("  ", listOf("a"), true, p))
        assertEquals(AgentChat.Gate.Ready, AgentChat.gate("do it", listOf("a"), true, p))
    }

    @Test fun `only the blocking gates have words and none of them shows a setting path or code`() {
        assertNull(AgentChat.hint(AgentChat.Gate.Ready))
        assertNull(AgentChat.hint(AgentChat.Gate.NeedsGoal))
        for (gate in listOf(AgentChat.Gate.NeedsModel, AgentChat.Gate.AiOff, AgentChat.Gate.NeedsApps)) {
            val hint = AgentChat.hint(gate)!!
            assertTrue(gate.name, hint.isNotBlank())
            assertFalse(hint, hint.contains("package"))
            assertFalse(hint, hint.contains("→"))
        }
    }

    @Test fun `the remembered model is used while it exists and otherwise the first one`() {
        val p = listOf(profile("p1"), profile("p2"))
        assertEquals("p2", AgentChat.profileFor("p2", p))
        assertEquals("p1", AgentChat.profileFor("gone", p))
        assertEquals("p1", AgentChat.profileFor(null, p))
        assertNull(AgentChat.profileFor("p1", emptyList()))
    }

    @Test fun `remembered apps that were uninstalled are dropped once the list has loaded`() {
        assertEquals(listOf("a", "c"), AgentChat.stillInstalled(listOf("a", "b", "c"), setOf("a", "c", "d")))
        assertEquals(listOf("a", "b"), AgentChat.stillInstalled(listOf("a", "b"), emptySet()))
    }

    // ---- INT-E2: the saved tasks the agent may start ---------------------------------------------------------------------

    private fun choice(id: String) = TaskChoice(id, "Task $id", "Macro")

    @Test fun `remembered saved tasks that were deleted are dropped once the list has loaded`() {
        assertEquals(listOf("a", "c"), AgentChat.stillSaved(listOf("a", "b", "c"), setOf("a", "c", "d")))
        assertEquals(listOf("a", "b"), AgentChat.stillSaved(listOf("a", "b"), emptySet()))
    }

    @Test fun `only ticked tasks that exist are handed to the agent, in the order they were ticked`() {
        val existing = listOf(choice("a"), choice("b"), choice("c"))
        assertEquals(listOf("c", "a"), AgentChat.allowedTasks(listOf("c", "gone", "a", "c"), existing).map { it.taskId })
        assertEquals("Task c", AgentChat.allowedTasks(listOf("c"), existing).single().name)
        assertTrue(AgentChat.allowedTasks(emptyList(), existing).isEmpty())
    }

    @Test fun `with no list of tasks yet nothing is handed to the agent`() {
        // the screen keeps a ticked task while the list loads, but a message never starts with one it cannot see
        assertTrue(AgentChat.allowedTasks(listOf("a", "b"), emptyList()).isEmpty())
    }

    @Test fun `no more than the limit of tasks are handed over`() {
        val existing = (1..30).map { choice("t$it") }
        val handed = AgentChat.allowedTasks(existing.map { it.taskId }, existing)
        assertEquals(AgentChat.MAX_TASKS, handed.size)
        assertEquals("t1", handed.first().taskId)
    }

    @Test fun `the setup line names apps, tasks and the model and leaves out what is not there`() {
        assertEquals("No apps picked yet", AgentChat.summaryLine(emptyList(), emptyList(), null))
        assertEquals("Apps: YouTube, Gemini · Model: Work key", AgentChat.summaryLine(listOf("YouTube", "Gemini"), emptyList(), "Work key"))
        assertEquals(
            "Apps: Gemini · Tasks: Daily clip, Upload · Model: P1",
            AgentChat.summaryLine(listOf("Gemini"), listOf("Daily clip", "Upload"), "P1"),
        )
        assertEquals("Apps: Gemini · Tasks: Daily clip", AgentChat.summaryLine(listOf("Gemini"), listOf("Daily clip"), "  "))
    }

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("repo file not found from ${System.getProperty("user.dir")}: $relative")
    }
}
