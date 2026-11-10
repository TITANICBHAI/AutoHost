package app.autohost.core.engine.schedule

import app.autohost.core.common.FakeClock
import app.autohost.core.model.MisfirePolicy
import app.autohost.core.model.ScheduleRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `testing/golden/schedule_form_vectors.json` (made by the Python reference scheduler) is the executable spec of the
 * schedule form: for every valid case [ScheduleForm.toRule] must produce an equal rule that the engine schedules the same
 * way, and for every invalid case [ScheduleForm.validate] must return exactly the listed error keys.
 */
class ScheduleFormGoldenTest {

    private val engine = ScheduleEngine(FakeClock())

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("repo file not found from ${System.getProperty("user.dir")}: $relative")
    }

    private fun cases() = Json.parseToJsonElement(repoFile("testing/golden/schedule_form_vectors.json").readText())
        .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    private fun formOf(o: JsonObject): ScheduleForm.Form {
        val defaults = ScheduleForm.Form()
        return ScheduleForm.Form(
            task = o["task"]?.jsonPrimitive?.content ?: defaults.task,
            zone = o["zone"]?.jsonPrimitive?.content ?: defaults.zone,
            repeat = o["repeat"]?.jsonPrimitive?.content ?: defaults.repeat,
            every = o["every"]?.jsonPrimitive?.int ?: defaults.every,
            unit = o["unit"]?.jsonPrimitive?.content ?: defaults.unit,
            times = o["times"]?.jsonArray?.map { it.jsonPrimitive.content } ?: defaults.times,
            days = o["days"]?.jsonArray?.map { it.jsonPrimitive.int } ?: defaults.days,
            date = o["date"]?.jsonPrimitive?.content ?: defaults.date,
            time = o["time"]?.jsonPrimitive?.content ?: defaults.time,
            from = o["from"]?.jsonPrimitive?.content ?: defaults.from,
            to = o["to"]?.jsonPrimitive?.content ?: defaults.to,
            misfire = misfireOf(o["misfire"]),
            profileIds = o["profile_ids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: defaults.profileIds,
            enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: defaults.enabled,
        )
    }

    /**
     * Not [MisfirePolicy.fromJsonElement]: that rejects `catch_up: 0`, and the `catch_up_zero` vector must reach
     * [ScheduleForm.validate] to be reported as `bad_misfire`.
     */
    private fun misfireOf(element: JsonElement?): MisfirePolicy = when {
        element == null || element is JsonNull -> MisfirePolicy.Skip
        element is JsonPrimitive && element.content == "skip" -> MisfirePolicy.Skip
        element is JsonPrimitive && element.content == "run_once" -> MisfirePolicy.RunOnce
        element is JsonObject -> MisfirePolicy.CatchUp(element.getValue("catch_up").jsonPrimitive.int)
        else -> error("unknown misfire in vectors: $element")
    }

    @Test fun `the vectors file has cases`() {
        assertTrue("no golden cases found", cases().isNotEmpty())
    }

    @Test fun `valid forms become the reference rule and schedule the same way`() {
        var checked = 0
        for (case in cases()) {
            if ("rule" !in case) continue
            val name = case.getValue("name").jsonPrimitive.content
            val form = formOf(case.getValue("form").jsonObject)
            val nowMs = case.getValue("now_ms").jsonPrimitive.long
            val ruleId = case.getValue("rule_id").jsonPrimitive.content
            assertEquals("case $name: validate", emptyList<String>(), ScheduleForm.validate(form, nowMs))
            val expected = ScheduleRule.fromJson(case.getValue("rule").jsonObject.toString())
            assertNotNull("case $name: the reference rule must parse", expected)
            val rule = ScheduleForm.toRule(form, ruleId, nowMs)
            assertEquals("case $name: rule", expected, rule)
            val after = case.getValue("after_ms").jsonPrimitive.long
            assertEquals(
                "case $name: next 5 fires",
                case.getValue("expected_next5").jsonArray.map { it.jsonPrimitive.long },
                engine.nextFires(rule, after, ScheduleState(), 5),
            )
            checked++
        }
        assertTrue("no valid cases were checked", checked > 0)
    }

    @Test fun `invalid forms return exactly the listed error keys`() {
        var checked = 0
        for (case in cases()) {
            if ("errors" !in case) continue
            val name = case.getValue("name").jsonPrimitive.content
            val form = formOf(case.getValue("form").jsonObject)
            val nowMs = case.getValue("now_ms").jsonPrimitive.long
            assertEquals(
                "case $name: errors",
                case.getValue("errors").jsonArray.map { it.jsonPrimitive.content },
                ScheduleForm.validate(form, nowMs),
            )
            checked++
        }
        assertTrue("no invalid cases were checked", checked > 0)
    }

    @Test fun `showing a rule on the form and saving it again gives the same rule`() {
        for (case in cases()) {
            if ("rule" !in case) continue
            val name = case.getValue("name").jsonPrimitive.content
            val nowMs = case.getValue("now_ms").jsonPrimitive.long
            val rule = ScheduleForm.toRule(formOf(case.getValue("form").jsonObject), "sch_test", nowMs)
            val shown = ScheduleForm.fromRule(rule)
            assertNotNull("case $name: the form must be able to show its own rule", shown)
            assertEquals("case $name: round trip", rule, ScheduleForm.toRule(shown!!, "sch_test", nowMs))
        }
    }
}
