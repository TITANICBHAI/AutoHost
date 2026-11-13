package app.autohost.ui2.macro

import app.autohost.core.common.AhJson
import app.autohost.core.model.TaskManifest
import app.autohost.runtime.flow.FlowParser
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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

    private fun repoRoot(): java.io.File {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) { if (java.io.File(dir, "schemas/trigger-rule.schema.json").exists()) return dir; dir = dir.parentFile }
        error("repo root not found")
    }
}
