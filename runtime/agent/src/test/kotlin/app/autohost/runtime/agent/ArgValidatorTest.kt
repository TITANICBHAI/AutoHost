package app.autohost.runtime.agent

import app.autohost.core.common.AhResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** INT-E1: where a tool argument may come from — the run's target apps or the saved tasks the person allowed. */
class ArgValidatorTest {

    private val catalog = ToolCatalog.load()

    private fun raw(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun reasonOf(result: AhResult<JsonObject>): String? = result.errorOrNull()?.reason

    @Test
    fun `given a task on the run's list when run_task is validated then it passes and is kept`() {
        val tool = catalog.find("run_task")!!
        val result = ArgValidator.validate(tool, raw("""{"task":"daily.upload"}"""), emptySet(), setOf("daily.upload"))
        assertTrue(result.isOk)
        assertEquals("daily.upload", (result.getOrNull()!!["task"] as JsonPrimitive).content)
    }

    @Test
    fun `given a task that is not on the list when run_task is validated then it is refused as not granted`() {
        val tool = catalog.find("run_task")!!
        val result = ArgValidator.validate(tool, raw("""{"task":"other.task"}"""), emptySet(), setOf("daily.upload"))
        assertFalse(result.isOk)
        assertEquals("CAPABILITY_NOT_GRANTED", reasonOf(result))
    }

    @Test
    fun `given no list at all when run_task is validated then every task is refused`() {
        val tool = catalog.find("run_task")!!
        val result = ArgValidator.validate(tool, raw("""{"task":"daily.upload"}"""), setOf("daily.upload"))
        assertFalse(result.isOk)
    }

    @Test
    fun `given an app package where a task is wanted when run_task is validated then the package list does not count`() {
        val tool = catalog.find("run_task")!!
        val result = ArgValidator.validate(tool, raw("""{"task":"com.normal.test"}"""), setOf("com.normal.test"), emptySet())
        assertFalse(result.isOk)
    }

    @Test
    fun `given a task id where an app is wanted when app_launch is validated then the task list does not count`() {
        val tool = catalog.find("app_launch")!!
        val result = ArgValidator.validate(tool, raw("""{"package":"daily.upload"}"""), setOf("com.normal.test"), setOf("daily.upload"))
        assertFalse(result.isOk)
        assertEquals("CAPABILITY_NOT_GRANTED", reasonOf(result))
    }

    @Test
    fun `given an app on the run's list when app_launch is validated then it still passes (the old rule is unchanged)`() {
        val tool = catalog.find("app_launch")!!
        val result = ArgValidator.validate(tool, raw("""{"package":"com.normal.test"}"""), setOf("com.normal.test"))
        assertTrue(result.isOk)
    }

    @Test
    fun `given an enum_of list this validator does not know when validated then it fails closed`() {
        val tool = ToolCatalog.Tool(
            name = "made_up",
            description = "",
            capability = "x.y",
            method = "z",
            permission = "x.y",
            args = buildJsonObject { put("value", buildJsonObject { put("enum_of", "run_something_else") }) },
            specRisk = null,
        )
        val result = ArgValidator.validate(tool, raw("""{"value":"a"}"""), setOf("a"), setOf("a"))
        assertFalse(result.isOk)
    }
}
