package app.autohost.agent

import app.autohost.DevTask
import app.autohost.core.common.AhJson
import app.autohost.core.model.RuntimeSpec
import app.autohost.core.model.TaskManifest
import app.autohost.ui2.macro.Macro
import app.autohost.ui2.macro.MacroCompiler
import app.autohost.ui2.macro.MacroResult
import app.autohost.ui2.macro.MacroStep
import app.autohost.ui2.macro.NamedValue
import app.autohost.ui2.pickers.TaskChoice
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** INT-E4b: the value names a saved task takes are read from the task's own files, and nothing else is ever offered. */
class TaskValueNamesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A task in its own folder. [file] null leaves the folder empty, so the entry the manifest names does not exist. */
    private fun task(id: String, file: String?, text: String, type: String = "flow"): DevTask {
        val dir = tmp.newFolder(id)
        if (file != null) File(dir, file).writeText(text)
        val manifest = TaskManifest(id = id, name = "Task $id", runtime = RuntimeSpec(type, file ?: "flow.json"))
        return DevTask(id, manifest.name, manifest, dir, JsonObject(emptyMap()))
    }

    private fun flow(env: String) = """{"schema":1,"name":"Upload","env":$env,"steps":[{"log":{"message":"hi"}}]}"""

    @Test fun `a flow takes the names in its own env block, in order, and nothing else`() {
        val t = task("daily.upload", "flow.json", flow("""{"title":"","description":"A short one","count":3}"""))
        assertEquals(listOf("title", "description", "count"), TaskValueNames.declared(t))
    }

    @Test fun `a flow with no env block takes no values`() {
        val t = task("plain.one", "flow.json", """{"schema":1,"name":"Plain","steps":[{"log":{"message":"hi"}}]}""")
        assertTrue(TaskValueNames.declared(t).isEmpty())
    }

    @Test fun `only a flow task can take values, even with a flow file lying beside it`() {
        val script = task("script.one", "flow.json", flow("""{"title":""}"""), type = "python")
        assertTrue(TaskValueNames.declared(script).isEmpty())
        assertTrue(TaskValueNames.declared(null).isEmpty())
    }

    @Test fun `a flow that is missing or cannot be read takes no values and does not throw`() {
        assertTrue(TaskValueNames.declared(task("gone.one", null, "")).isEmpty())
        assertTrue(TaskValueNames.declared(task("broken.one", "flow.json", "{ not json")).isEmpty())
        assertTrue(TaskValueNames.declared(task("empty.one", "flow.json", """{"schema":1,"name":"x","steps":[]}""")).isEmpty())
    }

    @Test fun `a name the agent could not write is left out`() {
        val tooLong = "n".repeat(41)
        val t = task("odd.names", "flow.json", flow("""{"a=b":"1"," padded":"2","line\nbreak":"3","$tooLong":"4","ok":"5"}"""))
        assertEquals(listOf("ok"), TaskValueNames.declared(t))
    }

    @Test fun `a name of exactly the longest length is kept`() {
        val longest = "n".repeat(40)
        val t = task("longest.one", "flow.json", flow("""{"$longest":"1"}"""))
        assertEquals(listOf(longest), TaskValueNames.declared(t))
    }

    @Test fun `at most eight names are listed`() {
        val env = (1..12).joinToString(",", "{", "}") { "\"v$it\":\"\"" }
        val t = task("many.one", "flow.json", flow(env))
        assertEquals((1..8).map { "v$it" }, TaskValueNames.declared(t))
    }

    @Test fun `a macro's own input names are the values the agent may hand over`() {
        val macro = Macro(
            "Daily upload", "charger_on", emptyMap(), listOf(MacroStep.Log("hi")),
            listOf(NamedValue("title"), NamedValue("description", "A short one")),
        )
        val compiled = (MacroCompiler.compile(macro, 1L) as MacroResult.Ok).compiled
        val manifest = AhJson.json.decodeFromString(TaskManifest.serializer(), compiled.taskJson)
        val dir = tmp.newFolder("macro-folder")
        File(dir, manifest.runtime.entry).writeText(compiled.flowJson)
        val dev = DevTask(manifest.id, manifest.name, manifest, dir, JsonObject(emptyMap()))
        assertEquals(listOf("title", "description"), TaskValueNames.declared(dev))
    }

    @Test fun `a macro with no inputs takes no values`() {
        val macro = Macro("Just log", "charger_on", emptyMap(), listOf(MacroStep.Log("hi")))
        val compiled = (MacroCompiler.compile(macro, 2L) as MacroResult.Ok).compiled
        val manifest = AhJson.json.decodeFromString(TaskManifest.serializer(), compiled.taskJson)
        val dir = tmp.newFolder("macro-plain")
        File(dir, manifest.runtime.entry).writeText(compiled.flowJson)
        val dev = DevTask(manifest.id, manifest.name, manifest, dir, JsonObject(emptyMap()))
        assertTrue(TaskValueNames.declared(dev).isEmpty())
    }

    @Test fun `allowed tasks carry their names, and a task outside the dev folder carries none`() {
        val dev = task("daily.upload", "flow.json", flow("""{"title":"","description":""}"""))
        val chosen = listOf(
            TaskChoice("daily.upload", "Daily upload", "Macro"),
            TaskChoice("app.autohost.installed", "Installed one", null),
        )
        assertEquals(
            listOf(
                AgentRunStarter.AllowedTask("daily.upload", "Daily upload", listOf("title", "description")),
                AgentRunStarter.AllowedTask("app.autohost.installed", "Installed one"),
            ),
            TaskValueNames.allowed(chosen, listOf(dev)),
        )
    }

    @Test fun `no ticked task gives an empty list`() {
        assertTrue(TaskValueNames.allowed(emptyList(), emptyList()).isEmpty())
    }
}
