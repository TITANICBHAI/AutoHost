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

    // --- INT-E4: inputs, a list of "name=value" texts the model hands a saved task ---------------

    private fun entries(count: Int) = (1..count).joinToString(",") { "\"n$it=v\"" }

    private fun validateInputs(inputs: String): AhResult<JsonObject> = ArgValidator.validate(
        catalog.find("run_task")!!,
        raw("""{"task":"daily.upload","inputs":$inputs}"""),
        emptySet(),
        setOf("daily.upload"),
    )

    @Test
    fun `given name=value texts when run_task is validated then they come out as an object of name to text`() {
        val result = validateInputs("""["title=My cat video","description=Line one\nLine two"]""")
        assertTrue(result.isOk)
        val clean = result.getOrNull()!!
        val inputs = clean["inputs"] as JsonObject
        assertEquals("My cat video", (inputs["title"] as JsonPrimitive).content)
        assertEquals("Line one\nLine two", (inputs["description"] as JsonPrimitive).content)
        assertEquals(setOf("task", "inputs"), clean.keys)
    }

    @Test
    fun `given a value with an equals sign when validated then only the first one splits name from value`() {
        val result = validateInputs("""["link=https://x.test/a?b=c"," spaced name =  keep my spaces "]""")
        val inputs = result.getOrNull()!!["inputs"] as JsonObject
        assertEquals("https://x.test/a?b=c", (inputs["link"] as JsonPrimitive).content)
        assertEquals("  keep my spaces ", (inputs["spaced name"] as JsonPrimitive).content)
    }

    @Test
    fun `given no inputs or an empty list when validated then the key is simply absent`() {
        assertFalse(validateInputs("[]").getOrNull()!!.containsKey("inputs"))
        val tool = catalog.find("run_task")!!
        val without = ArgValidator.validate(tool, raw("""{"task":"daily.upload"}"""), emptySet(), setOf("daily.upload"))
        assertFalse(without.getOrNull()!!.containsKey("inputs"))
    }

    @Test
    fun `given inputs that are not a list of texts when validated then INVALID_ARGS`() {
        for (bad in listOf("\"title=x\"", "{\"title\":\"x\"}", "[5]", "[true]", "[null]", "[[\"a=b\"]]", "[{\"title\":\"x\"}]")) {
            val result = validateInputs(bad)
            assertFalse(bad, result.isOk)
            assertEquals(bad, "INVALID_ARGS", result.errorOrNull()?.code)
        }
    }

    @Test
    fun `given too many, nameless, repeated or overlong entries when validated then INVALID_ARGS`() {
        assertFalse(validateInputs("[${entries(9)}]").isOk)
        assertTrue(validateInputs("[${entries(8)}]").isOk)
        assertFalse(validateInputs("""["=value"]""").isOk)
        assertFalse(validateInputs("""["noequals"]""").isOk)
        assertFalse(validateInputs("""["   =value"]""").isOk)
        assertFalse(validateInputs("""["a=1","a=2"]""").isOk)
        val longName = "n".repeat(ArgValidator.MAX_PAIR_NAME)
        val longValue = "x".repeat(ArgValidator.MAX_PAIR_VALUE)
        assertTrue(validateInputs("[\"$longName=$longValue\"]").isOk)
        assertFalse(validateInputs("[\"${longName}n=v\"]").isOk)
        assertFalse(validateInputs("[\"t=${longValue}x\"]").isOk)
    }
}
