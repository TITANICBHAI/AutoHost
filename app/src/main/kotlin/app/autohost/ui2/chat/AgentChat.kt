package app.autohost.ui2.chat

import app.autohost.core.engine.orchestrator.RunRecord
import app.autohost.core.llm.ProviderProfile
import app.autohost.core.model.BrokerEvent
import app.autohost.core.model.RunStatus
import app.autohost.ui2.ReasonCopy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One thing the chat draws under a goal. [key] is unique within one run's lines. */
sealed interface ChatLine {
    val key: String

    /** What the agent said it was thinking, in its own words (redacted where it is written; package names replaced here). */
    data class Thought(override val key: String, val text: String) : ChatLine

    /** One thing the agent did, in plain words. [ok] stays null until its result arrives. */
    data class Step(override val key: String, val tool: String, val phrase: String, val ok: Boolean?) : ChatLine

    /** A heads-up that is not the end of the run, such as the agent being close to a limit. */
    data class Notice(override val key: String, val text: String) : ChatLine

    /** The run is still going, or waiting for the person. */
    data class Working(override val key: String, val text: String, val waitingForYou: Boolean) : ChatLine

    /** The run is over. [fix] is a button when the catalog names a fix for the reason that the chat can carry out. */
    data class Outcome(
        override val key: String,
        val tone: Tone,
        val headline: String,
        val detail: String?,
        val fix: ChatFix? = null,
    ) : ChatLine

    enum class Tone { Good, Bad, Neutral }
}

/** One row of the whole conversation: the person's goal, or a line the agent produced for it. Keys are unique across runs. */
sealed interface ChatRow {
    val key: String
    val runId: String

    data class Goal(override val runId: String, val text: String) : ChatRow {
        override val key: String get() = "$runId:goal"
    }

    /** [runFinished] is true once the run has ended, so a step still without a result is drawn as "unknown", not "in progress". */
    data class Line(override val runId: String, val line: ChatLine, val runFinished: Boolean) : ChatRow {
        override val key: String get() = "$runId:${line.key}"
    }
}

/** What a button under a refusal or a failed run does. */
sealed interface FixTarget {
    /** The AI settings screen (a model, a key, AI switched on). */
    object AiSettings : FixTarget

    /** The screen that reviews what tasks and the agent may do. */
    object ReviewPermissions : FixTarget

    /** A system screen, handed to the app's fix launcher by the catalog's action name. */
    data class SystemScreen(val action: String) : FixTarget
}

/** A button the catalog says would fix a stop: its words (from the catalog) and what it does. */
data class ChatFix(val label: String, val target: FixTarget)

/**
 * The agent chat as pure logic (INT-F, docs/INTEGRATION_V3.md §5): what a run's events and record look like as a
 * conversation, and whether the person may send a message yet. Nothing here touches Android, so it is tested on the JVM.
 *
 * Rules the tests hold: the person never reads a tool name, an argument, a package name, an id, or an exception message.
 * A tool becomes a plain phrase; a package name in the agent's own words becomes the app's name; a failure shows the
 * catalog text for its reason code ([ReasonCopy]) and nothing else.
 */
object AgentChat {
    const val MAX_THOUGHT_CHARS = 400
    const val MAX_SUMMARY_CHARS = 600

    /** Why a message cannot be sent yet. [Ready] means it can. The order is the order the person should fix things in. */
    enum class Gate { NeedsModel, AiOff, NeedsApps, NeedsGoal, Ready }

    fun gate(goal: String, packages: List<String>, aiEnabled: Boolean, profiles: List<ProviderProfile>): Gate = when {
        profiles.isEmpty() -> Gate.NeedsModel
        !aiEnabled -> Gate.AiOff
        packages.isEmpty() -> Gate.NeedsApps
        goal.isBlank() -> Gate.NeedsGoal
        else -> Gate.Ready
    }

    /** The words under the composer for a gate that blocks sending; null when nothing needs saying. */
    fun hint(gate: Gate): String? = when (gate) {
        Gate.NeedsModel -> "Add an AI model first. You bring your own key; nothing is sent until you turn it on."
        Gate.AiOff -> "AI is switched off. Turn it on in the AI settings."
        Gate.NeedsApps -> "Pick the apps the agent may use. It cannot leave them."
        Gate.NeedsGoal, Gate.Ready -> null
    }

    /** The provider a message uses: the remembered one while it still exists, else the first. Null only with no providers. */
    fun profileFor(profileId: String?, profiles: List<ProviderProfile>): String? =
        profiles.firstOrNull { it.id == profileId }?.id ?: profiles.firstOrNull()?.id

    /** The remembered apps that are still installed. With no list yet (it is still loading) nothing is dropped. */
    fun stillInstalled(packages: List<String>, installed: Set<String>): List<String> =
        if (installed.isEmpty()) packages else packages.filter { it in installed }

    /** The catalog fix actions the chat can carry out. A fix not in this set draws no button (no dead buttons). */
    val supportedFixes: Set<String> = setOf("OPEN_AI_SETTINGS", "OPEN_PERMISSION_REVIEW", "OPEN_ACCESSIBILITY_SETTINGS")

    /**
     * The button for the reason a start was refused or a run failed: the catalog's words, and what it does. Null when the
     * catalog names no fix for the reason, the fix needs a value, or the chat cannot carry it out.
     */
    fun fixFor(reason: String?): ChatFix? {
        val fix = ReasonCopy.fix(reason) ?: return null
        val target: FixTarget = when (fix.action) {
            "OPEN_AI_SETTINGS" -> FixTarget.AiSettings
            "OPEN_PERMISSION_REVIEW" -> FixTarget.ReviewPermissions
            "OPEN_ACCESSIBILITY_SETTINGS" -> FixTarget.SystemScreen(fix.action)
            else -> return null
        }
        return ChatFix(fix.label, target)
    }

    /**
     * The conversation for the stored goals, oldest first: each goal, then what the agent did for it. [recordOf] and
     * [eventsOf] read the run store; [appNames] maps a package to the app's name for scrubbing the agent's words.
     */
    fun rows(
        entries: List<ChatEntry>,
        recordOf: (String) -> RunRecord?,
        eventsOf: (String) -> List<BrokerEvent>,
        appNames: Map<String, String> = emptyMap(),
    ): List<ChatRow> {
        val out = ArrayList<ChatRow>()
        for (entry in entries) {
            out += ChatRow.Goal(entry.runId, entry.goal)
            val record = recordOf(entry.runId)
            val finished = record?.status?.terminal ?: true
            for (line in lines(eventsOf(entry.runId), record, appNames)) {
                out += ChatRow.Line(entry.runId, line, finished)
            }
        }
        return out
    }

    /** The lines for one run. [record] is null when the run store no longer holds it (the app was closed since). */
    fun lines(events: List<BrokerEvent>, record: RunRecord?, appNames: Map<String, String> = emptyMap()): List<ChatLine> {
        val out = ArrayList<ChatLine>()
        var droppedNoted = false
        events.forEachIndexed { index, event ->
            if (event.type != "log") return@forEachIndexed
            val data = event.data ?: return@forEachIndexed
            when (data.str("kind")) {
                "agent.thought" -> {
                    val text = PlainText.clean(data.str("text").orEmpty(), appNames)
                    if (text.isNotEmpty()) out += ChatLine.Thought("t$index", PlainText.cut(text, MAX_THOUGHT_CHARS))
                }
                "agent.action" -> {
                    val tool = data.str("tool").orEmpty()
                    out += ChatLine.Step("a$index", tool, ToolPhrases.phrase(tool), ok = null)
                }
                "agent.result" -> {
                    val tool = data.str("tool").orEmpty()
                    val ok = data.str("ok")?.toBooleanStrictOrNull() ?: false
                    // one action per turn and results arrive in order: the first step of this tool still waiting
                    val at = out.indexOfFirst { it is ChatLine.Step && it.ok == null && it.tool == tool }
                    if (at >= 0) out[at] = (out[at] as ChatLine.Step).copy(ok = ok)
                }
                "agent.budget" -> if (data["fraction"] != null) {
                    // only the 80% crossing; the stop itself is told by the outcome line
                    out += ChatLine.Notice(
                        "b$index",
                        "Heads up: the agent has used about 80% of its ${BudgetWords.of(data.str("budget"))} limit.",
                    )
                }
                "agent.dropped" -> if (!droppedNoted) {
                    droppedNoted = true
                    out += ChatLine.Notice("d$index", "Some of the earlier steps are not shown.")
                }
            }
        }
        if (record == null) {
            if (events.isEmpty()) out += ChatLine.Notice("gone", "The details of this run were not kept after the app was closed.")
        } else {
            out += tail(record, appNames)
        }
        return out
    }

    private fun tail(record: RunRecord, appNames: Map<String, String>): ChatLine = when (record.status) {
        RunStatus.QUEUED, RunStatus.STARTING -> ChatLine.Working("w", "Starting…", waitingForYou = false)
        RunStatus.RUNNING -> ChatLine.Working("w", stagePhrase(record.stage), waitingForYou = false)
        RunStatus.PAUSED -> ChatLine.Working("w", "Paused.", waitingForYou = false)
        RunStatus.WAITING_USER -> ChatLine.Working("w", "The agent is waiting for you.", waitingForYou = true)
        RunStatus.SUCCEEDED -> ChatLine.Outcome("o", ChatLine.Tone.Good, "Done.", summary(record, appNames))
        RunStatus.FAILED -> ChatLine.Outcome(
            "o", ChatLine.Tone.Bad, "The agent could not finish.", explain(record.reason), fixFor(record.reason),
        )
        RunStatus.CANCELLED -> ChatLine.Outcome(
            "o", ChatLine.Tone.Neutral, "Stopped.",
            if (record.reason == null || record.reason == "USER_CANCELLED") null else explain(record.reason),
        )
        RunStatus.INTERRUPTED -> ChatLine.Outcome(
            "o", ChatLine.Tone.Neutral, "Interrupted.",
            "AutoHost was closed, or the phone restarted, while this was running.",
        )
        RunStatus.SKIPPED -> ChatLine.Outcome("o", ChatLine.Tone.Neutral, "Skipped.", record.reason?.let { explain(it) })
    }

    /** The catalog's words for a reason code; one honest sentence when there are none (never an exception message). */
    private fun explain(reason: String?): String =
        ReasonCopy.text(reason) ?: "AutoHost does not have a plain explanation for why this stopped."

    private fun summary(record: RunRecord, appNames: Map<String, String>): String? =
        record.message?.let { PlainText.clean(it, appNames) }?.takeIf { it.isNotEmpty() }?.let { PlainText.cut(it, MAX_SUMMARY_CHARS) }

    private fun stagePhrase(stage: String?): String = when (stage) {
        "observe" -> "Looking at the screen…"
        "think" -> "Thinking…"
        "act" -> "Doing the next step…"
        else -> "Working…"
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
}

/** The plain phrase for each tool the agent can call. A tool this table does not know reads as a generic step. */
object ToolPhrases {
    private val phrases: Map<String, String> = mapOf(
        "app_foreground" to "Checking which app is open",
        "app_launch" to "Opening an app",
        "app_query" to "Looking up an app",
        "app_wait_foreground" to "Waiting for an app to open",
        "ask_user" to "Asking you a question",
        "finish" to "Finishing up",
        "run_task" to "Starting one of your saved tasks",
        "todo_write" to "Planning the next steps",
        "ui_click" to "Tapping something",
        "ui_find" to "Looking for something on the screen",
        "ui_hide_keyboard" to "Hiding the keyboard",
        "ui_long_click" to "Pressing and holding something",
        "ui_press" to "Pressing Back",
        "ui_scroll_until" to "Scrolling to find something",
        "ui_set_text" to "Typing text",
        "ui_snapshot" to "Reading the screen",
        "ui_wait_for" to "Waiting for something to appear",
        "ui_wait_for_settle" to "Waiting for the screen to settle",
        "wait" to "Waiting a moment",
    )

    const val GENERIC = "Working on the next step"

    /** The tools the table knows, for the test that compares it with the agent's tool catalog. */
    val known: Set<String> get() = phrases.keys

    fun phrase(tool: String?): String = phrases[tool] ?: GENERIC
}

/** Which limit a budget name means, in the words the heads-up uses. */
internal object BudgetWords {
    fun of(budget: String?): String = when (budget) {
        "turns" -> "step"
        "actions" -> "phone action"
        "tokens" -> "AI usage"
        "model_calls" -> "AI request"
        "wall_clock", "time" -> "time"
        else -> "allowance"
    }
}

/** Keeps package names out of the agent's own words. */
internal object PlainText {
    /** com.something.app-like names the agent might mention; anything the maps below do not know becomes "an app". */
    private val packageLike = Regex("""\b(?:com|org|net|io|app|android|dev|me|co)\.[a-z0-9_]+(?:\.[a-z0-9_]+)+\b""")
    private val spaces = Regex("""\s+""")

    fun clean(text: String, appNames: Map<String, String>): String {
        var out = text
        for ((pkg, label) in appNames.entries.sortedByDescending { it.key.length }) {
            if (pkg.isNotBlank() && label.isNotBlank()) out = out.replace(pkg, label)
        }
        return packageLike.replace(out, "an app").replace(spaces, " ").trim()
    }

    fun cut(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"
}
