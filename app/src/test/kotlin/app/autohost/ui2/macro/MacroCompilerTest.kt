package app.autohost.ui2.macro

import app.autohost.core.common.AhJson
import app.autohost.core.model.TaskManifest
import app.autohost.runtime.flow.FlowParser
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroCompilerTest {

    private val steps = listOf(
        MacroStep.OpenApp("com.android.chrome"),
        MacroStep.WaitFor("com.android.chrome", "Search", 10),
        MacroStep.Tap("com.android.chrome", "Search"),
        MacroStep.Notify("Done", "Chrome is open"),
        MacroStep.Ask("Continue?", "Press OK to go on"),
        MacroStep.Log("finished"),
    )
    private val macro = Macro("Morning Chrome!", "daily", mapOf("time" to "07:30"), steps)

    private fun ok(m: Macro, now: Long = 123456L): CompiledMacro =
        (MacroCompiler.compile(m, now) as MacroResult.Ok).compiled

    @Test fun `the compiled flow is accepted by the real flow parser`() {
        val c = ok(macro)
        val doc = FlowParser.parse(c.flowJson)
        assertEquals(6, doc.steps.size)
        assertEquals(listOf("launchApp", "waitUntil", "tapOn", "notify", "ask", "log"), doc.steps.map { it.command })
    }

    @Test fun `the compiled task decodes as a real manifest with scoped capabilities and a valid id`() {
        val c = ok(macro)
        val manifest = AhJson.json.decodeFromString(TaskManifest.serializer(), c.taskJson)
        assertEquals(c.taskId, manifest.id)
        assertTrue(Regex("^[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9-]*)+$").matches(manifest.id))
        assertEquals("flow", manifest.runtime.type)
        val ids = manifest.needs.capabilities.map { it.id }
        assertEquals(listOf("device.app.launch", "device.ui.observe", "device.ui.act", "notify.post", "ui.prompt"), ids)
        val scoped = manifest.needs.capabilities.first { it.id == "device.ui.act" }.scope!!["packages"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("com.android.chrome"), scoped)
    }

    @Test fun `a macro that only logs asks for no capability`() {
        val c = ok(Macro("Just log", "charger_on", emptyMap(), listOf(MacroStep.Log("hi"))))
        val manifest = AhJson.json.decodeFromString(TaskManifest.serializer(), c.taskJson)
        assertTrue(manifest.needs.capabilities.isEmpty())
        assertEquals("power.connected", c.rule.source)
    }

    @Test fun `trigger params carry the right types`() {
        assertEquals("07:30", ok(macro).rule.params["time"]!!.jsonPrimitive.content)
        val delay = ok(Macro("Later", "delay", mapOf("seconds" to "90"), listOf(MacroStep.Log("x"))))
        assertEquals(90, delay.rule.params["seconds"]!!.jsonPrimitive.content.toInt())
        assertTrue(!delay.rule.params["seconds"]!!.jsonPrimitive.isString)
    }

    @Test fun `optional params that are blank are left out`() {
        val n = ok(Macro("Notif", "notification", mapOf("package" to "", "text_regex" to "code"), listOf(MacroStep.Log("x"))))
        assertEquals(setOf("text_regex"), n.rule.params.keys)
    }

    @Test fun `invalid macros are refused with plain messages and nothing is compiled`() {
        val bad = Macro(" ", "daily", mapOf("time" to "25:99"), listOf(MacroStep.Tap("chrome", ""), MacroStep.WaitFor("com.a.b", "x", 500)))
        val r = MacroCompiler.compile(bad, 1) as MacroResult.Invalid
        assertTrue(r.problems.any { it.contains("name") })
        assertTrue(r.problems.any { it.contains("07:30") })
        assertTrue(r.problems.any { it.contains("button text") })
        assertTrue(r.problems.any { it.contains("1 and 120") })
        assertTrue(MacroCompiler.validate(Macro("x", "nope", emptyMap(), emptyList())).size >= 2)
    }

    @Test fun `every when kind compiles to a source the trigger schema knows`() {
        val schemaSources = java.io.File(repoRoot(), "schemas/trigger-rule.schema.json").readText()
        WhenKinds.all.forEach { assertTrue(it.source, schemaSources.contains("\"${it.source}\"")) }
        assertEquals(WhenKinds.all.size, WhenKinds.all.map { it.id }.toSet().size)
    }

    @Test fun `slug and ids are safe and unique per time`() {
        assertEquals("morning-chrome", MacroCompiler.slug("Morning Chrome!"))
        assertEquals("macro", MacroCompiler.slug("!!!"))
        assertTrue(ok(macro, 1000L).taskId != ok(macro, 999_999_999L).taskId)
        assertTrue(ok(macro).folderName.startsWith("macro-"))
    }

    // ---- INT-C1: values a macro can be given, and the steps that join macros, scripts and results ----

    private val video = "com.example.video"
    private val tool = "app.autohost.script.make-video"

    private fun macroOf(vararg s: MacroStep, inputs: List<NamedValue> = emptyList()) =
        Macro("Test macro", "charger_on", emptyMap(), s.toList(), inputs)

    private fun json(text: String) = AhJson.json.parseToJsonElement(text).jsonObject

    private fun flowStep(c: CompiledMacro, index: Int, command: String) =
        json(c.flowJson)["steps"]!!.jsonArray[index].jsonObject[command]!!.jsonObject

    private fun execution(c: CompiledMacro, key: String) =
        json(c.taskJson)["execution"]!!.jsonObject[key]!!.jsonPrimitive.content.toInt()

    @Test fun `typing scrolling key presses and a result compile to flow steps the real parser accepts`() {
        val c = ok(
            macroOf(
                MacroStep.Tap(video, "Search"),
                MacroStep.TypeText(video, "", "cats"),
                MacroStep.TypeText(video, "Title", "A title"),
                MacroStep.Scroll(video, "up"),
                MacroStep.PressKey(video, "enter"),
                MacroStep.Finish("DONE", listOf(NamedValue("count", "3"))),
            ),
        )
        val doc = FlowParser.parse(c.flowJson)
        assertEquals(listOf("tapOn", "inputText", "inputText", "scroll", "pressKey", "setResult"), doc.steps.map { it.command })
        val focused = flowStep(c, 1, "inputText")["into"]!!.jsonObject
        assertEquals("android.widget.EditText", focused["class"]!!.jsonPrimitive.content)
        assertEquals("true", focused["focused"]!!.jsonPrimitive.content)
        assertEquals("cats", flowStep(c, 1, "inputText")["text"]!!.jsonPrimitive.content)
        val named = flowStep(c, 2, "inputText")["into"]!!.jsonObject["text"]!!.jsonObject
        assertEquals("Title", named["value"]!!.jsonPrimitive.content)
        assertEquals("contains", named["mode"]!!.jsonPrimitive.content)
        assertEquals("up", flowStep(c, 3, "scroll")["direction"]!!.jsonPrimitive.content)
        assertEquals("enter", flowStep(c, 4, "pressKey")["key"]!!.jsonPrimitive.content)
        val result = flowStep(c, 5, "setResult")
        assertEquals("DONE", result["code"]!!.jsonPrimitive.content)
        assertEquals("3", result["data"]!!.jsonObject["count"]!!.jsonPrimitive.content)
    }

    @Test fun `typing scrolling and key presses ask for screen access to that app only`() {
        val c = ok(macroOf(MacroStep.TypeText(video, "", "x"), MacroStep.Scroll(video, "down"), MacroStep.PressKey(video, "back")))
        val manifest = AhJson.json.decodeFromString(TaskManifest.serializer(), c.taskJson)
        assertEquals(listOf("device.ui.observe", "device.ui.act"), manifest.needs.capabilities.map { it.id })
        manifest.needs.capabilities.forEach { cap ->
            assertEquals(listOf(video), cap.scope!!["packages"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
    }

    @Test fun `run another task compiles to runTask and asks to run exactly that task`() {
        val c = ok(
            macroOf(
                MacroStep.RunTask(tool, listOf(NamedValue("topic", "{topic}"), NamedValue("mode", "short")), 120, "made"),
                MacroStep.Notify("Made", "{made.code}"),
                inputs = listOf(NamedValue("topic", "cats")),
            ),
        )
        val doc = FlowParser.parse(c.flowJson)
        assertEquals(listOf("runTask", "notify"), doc.steps.map { it.command })
        val run = flowStep(c, 0, "runTask")
        assertEquals(tool, run["task"]!!.jsonPrimitive.content)
        assertEquals("\${topic}", run["args"]!!.jsonObject["topic"]!!.jsonPrimitive.content)
        assertEquals("short", run["args"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals("made", run["into"]!!.jsonPrimitive.content)
        assertEquals(120, run["timeout"]!!.jsonPrimitive.content.toInt())
        assertEquals("\${env.made.code}", flowStep(c, 1, "notify")["text"]!!.jsonPrimitive.content)
        val manifest = AhJson.json.decodeFromString(TaskManifest.serializer(), c.taskJson)
        val grant = manifest.needs.capabilities.first { it.id == "tasks.run" }
        assertEquals(listOf(tool), grant.scope!!["tasks"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("tasks.run", manifest.needs.capabilities.last().id)
    }

    @Test fun `a run another task step without inputs or a kept answer leaves those keys out`() {
        val c = ok(macroOf(MacroStep.RunTask(tool, emptyList(), 30, " ")))
        val run = flowStep(c, 0, "runTask")
        assertEquals(setOf("task", "timeout"), run.keys)
        assertEquals(listOf("runTask"), FlowParser.parse(c.flowJson).steps.map { it.command })
    }

    @Test fun `values the macro can be given become the inputs of its flow with their defaults`() {
        val c = ok(macroOf(MacroStep.Log("Hello {who}"), inputs = listOf(NamedValue("who", "world"), NamedValue("blank", ""))))
        val doc = FlowParser.parse(c.flowJson)
        assertEquals("world", doc.env["who"]!!.jsonPrimitive.content)
        assertEquals("", doc.env["blank"]!!.jsonPrimitive.content)
        assertEquals("Hello \${who}", flowStep(c, 0, "log")["message"]!!.jsonPrimitive.content)
        assertNull(json(ok(macro).flowJson)["env"])
    }

    @Test fun `braces become flow placeholders only for names the macro knows`() {
        val known = setOf("who", "r")
        assertEquals("Hi \${who}!", MacroCompiler.expand("Hi {who}!", known))
        assertEquals("\${env.r.data.title}", MacroCompiler.expand("{r.data.title}", known))
        assertEquals("Hi {other}", MacroCompiler.expand("Hi {other}", known))
        assertEquals("\${who}", MacroCompiler.expand("\${who}", known))
        assertEquals("{} {1} { who }", MacroCompiler.expand("{} {1} { who }", known))
        assertEquals(listOf("a", "b"), MacroCompiler.unknownNames("{a} {who} {b} {a}", known))
    }

    @Test fun `a saved answer can be used by later steps but not by earlier ones`() {
        val later = macroOf(
            MacroStep.RunTask(tool, emptyList(), 60, "r"),
            MacroStep.Log("code {r.code}, title {r.data.title}"),
        )
        assertTrue(MacroCompiler.validate(later).isEmpty())
        assertEquals(
            "code \${env.r.code}, title \${env.r.data.title}",
            flowStep(ok(later), 1, "log")["message"]!!.jsonPrimitive.content,
        )
        val earlier = macroOf(MacroStep.Log("code {r.code}"), MacroStep.RunTask(tool, emptyList(), 60, "r"))
        assertTrue(MacroCompiler.validate(earlier).any { it.contains("{r}") && it.contains("not an input or a saved value") })
    }

    @Test fun `a name nothing provides is refused in every text a step has`() {
        val bad = macroOf(
            MacroStep.Notify("Hi {a}", "text {b}"),
            MacroStep.Ask("t", "m {c}"),
            MacroStep.Log("{d}"),
            MacroStep.TypeText(video, "", "{e}"),
            MacroStep.RunTask(tool, listOf(NamedValue("x", "{f}")), 60, ""),
            MacroStep.Finish("{g}", listOf(NamedValue("y", "{h}"))),
        )
        val problems = MacroCompiler.validate(bad)
        listOf("a", "b", "c", "d", "e", "f", "g", "h").forEach { n ->
            assertTrue(n, problems.any { it.contains("{" + n + "}") })
        }
    }

    @Test fun `names must be plain and unique and not one AutoHost uses itself`() {
        val p = MacroCompiler.validate(
            macroOf(MacroStep.Log("x"), inputs = listOf(NamedValue("a"), NamedValue("a"), NamedValue("2b"), NamedValue("env"))),
        )
        assertTrue(p.any { it.contains("repeats the name a") })
        assertTrue(p.any { it.contains("letters, numbers and _") })
        assertTrue(p.any { it.contains("used by AutoHost itself") })
        val clash = MacroCompiler.validate(
            macroOf(MacroStep.RunTask(tool, emptyList(), 60, "a"), inputs = listOf(NamedValue("a"))),
        )
        assertTrue(clash.any { it.contains("already used") })
        val twice = MacroCompiler.validate(
            macroOf(MacroStep.RunTask(tool, listOf(NamedValue("k", "1"), NamedValue("k", "2")), 60, "")),
        )
        assertTrue(twice.any { it.contains("named k twice") })
    }

    @Test fun `the new steps are checked with plain messages`() {
        val p = MacroCompiler.validate(
            macroOf(
                MacroStep.TypeText("chrome", "", ""),
                MacroStep.Scroll(video, "sideways"),
                MacroStep.PressKey(video, "volume"),
                MacroStep.RunTask("Make Video", emptyList(), 0, ""),
                MacroStep.RunTask(tool, emptyList(), 601, ""),
                MacroStep.Finish(" ", emptyList()),
                MacroStep.Log("after the finish"),
            ),
        )
        assertTrue(p.any { it.contains("Type text") && it.contains("app package") })
        assertTrue(p.any { it.contains("text to type") })
        assertTrue(p.any { it.contains("needs a direction") })
        assertTrue(p.any { it.contains("needs a key") })
        assertTrue(p.any { it.contains("needs the task to run") })
        assertEquals(2, p.count { it.contains("between 1 and 600 seconds") })
        assertTrue(p.any { it.contains("must be the last step") })
        assertTrue(p.any { it.contains("needs a result word") })
    }

    @Test fun `waiting for another task lengthens the run and idle limits by the wait`() {
        val plain = ok(macro)
        assertEquals(5, execution(plain, "timeout_minutes"))
        assertEquals(120, execution(plain, "idle_timeout_seconds"))
        val waits = ok(macroOf(MacroStep.RunTask(tool, emptyList(), 120, ""), MacroStep.RunTask(tool, emptyList(), 300, "")))
        assertEquals(12, execution(waits, "timeout_minutes"))
        assertEquals(540, execution(waits, "idle_timeout_seconds"))
        val longest = ok(macroOf(MacroStep.RunTask(tool, emptyList(), 600, ""), MacroStep.RunTask(tool, emptyList(), 600, "")))
        assertEquals(25, execution(longest, "timeout_minutes"))
        assertEquals(1320, execution(longest, "idle_timeout_seconds"))
    }

    @Test fun `a macro that only runs another task needs no screen access`() {
        val c = ok(macroOf(MacroStep.RunTask(tool, emptyList(), 60, "r"), MacroStep.Finish("DONE", emptyList())))
        val manifest = AhJson.json.decodeFromString(TaskManifest.serializer(), c.taskJson)
        assertEquals(listOf("tasks.run"), manifest.needs.capabilities.map { it.id })
        assertTrue(manifest.needs.apps.isEmpty())
        assertEquals("false", json(c.taskJson)["execution"]!!.jsonObject["ui_exclusive"]!!.jsonPrimitive.content)
    }

    @Test fun `a whole pipeline from the plan compiles and parses`() {
        val c = ok(
            Macro(
                "Make and post", "daily", mapOf("time" to "09:00"),
                listOf(
                    MacroStep.OpenApp(video),
                    MacroStep.Tap(video, "Create"),
                    MacroStep.RunTask(tool, listOf(NamedValue("topic", "{topic}")), 300, "info"),
                    MacroStep.TypeText(video, "Add a title", "{info.data.title}"),
                    MacroStep.PressKey(video, "back"),
                    MacroStep.Finish("POSTED", listOf(NamedValue("title", "{info.data.title}"))),
                ),
                listOf(NamedValue("topic", "cats")),
            ),
        )
        val doc = FlowParser.parse(c.flowJson)
        assertEquals(listOf("launchApp", "tapOn", "runTask", "inputText", "pressKey", "setResult"), doc.steps.map { it.command })
        assertEquals("cats", doc.env["topic"]!!.jsonPrimitive.content)
        assertEquals("\${env.info.data.title}", flowStep(c, 3, "inputText")["text"]!!.jsonPrimitive.content)
        val manifest = AhJson.json.decodeFromString(TaskManifest.serializer(), c.taskJson)
        assertEquals(
            listOf("device.app.launch", "device.ui.observe", "device.ui.act", "tasks.run"),
            manifest.needs.capabilities.map { it.id },
        )
    }

    @Test fun `the step menu offers every kind of step once and a new step can start with the last app`() {
        val menu = MacroSteps.menu()
        assertEquals(11, menu.map { it.javaClass }.toSet().size)
        assertEquals(menu.size, menu.map { it.label }.toSet().size)
        assertEquals("", MacroSteps.lastPackage(emptyList()))
        val used = listOf(MacroStep.OpenApp(video), MacroStep.Log("x"), MacroStep.Tap("", "x"))
        assertEquals(video, MacroSteps.lastPackage(used))
        assertEquals(video, MacroSteps.packageOf(MacroSteps.withPackage(MacroStep.Scroll("", "down"), video)))
        assertNull(MacroSteps.packageOf(MacroSteps.withPackage(MacroStep.Log("x"), video)))
    }

    private fun repoRoot(): java.io.File {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) { if (java.io.File(dir, "schemas/trigger-rule.schema.json").exists()) return dir; dir = dir.parentFile }
        error("repo root not found")
    }
}
