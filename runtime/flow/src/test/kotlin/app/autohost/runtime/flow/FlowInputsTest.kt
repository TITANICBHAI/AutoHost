package app.autohost.runtime.flow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** INT-B: what a caller (a script, later an agent) may hand to a flow, and what it may not. */
class FlowInputsTest {

    private val declared = buildJsonObject {
        put("title", "default title")
        put("count", 1)
        put("loud", false)
    }

    @Test
    fun `a caller sets only the names the flow declares`() {
        val args = buildJsonObject { put("title", "Hello"); put("other", "x") }
        val taken = FlowInputs.accepted(declared, args)
        assertEquals(setOf("title"), taken.keys)
        assertEquals(JsonPrimitive("Hello"), taken["title"])
    }

    @Test
    fun `a caller cannot reach config or profile by naming them`() {
        // Bare names resolve env first, then config, then profile: an undeclared name must never get into env.
        val args = buildJsonObject { put("label", "hijacked"); put("profile", "x") }
        assertTrue(FlowInputs.accepted(declared, args).isEmpty())
    }

    @Test
    fun `nothing sent means nothing taken and nothing ignored`() {
        assertTrue(FlowInputs.accepted(declared, null).isEmpty())
        assertTrue(FlowInputs.ignored(declared, null).isEmpty())
    }

    @Test
    fun `numbers and booleans pass when the default is not text`() {
        val args = buildJsonObject { put("count", 5); put("loud", true) }
        val taken = FlowInputs.accepted(declared, args)
        assertEquals(JsonPrimitive(5), taken["count"])
        assertEquals(JsonPrimitive(true), taken["loud"])
    }

    @Test
    fun `a number or flag sent for a text variable becomes text so templates stay strings`() {
        assertEquals(JsonPrimitive("42"), FlowInputs.accepted(declared, buildJsonObject { put("title", 42) })["title"])
        assertEquals(JsonPrimitive("true"), FlowInputs.accepted(declared, buildJsonObject { put("title", true) })["title"])
    }

    @Test
    fun `an object, a list or null is not a plain value and is left out`() {
        val args = buildJsonObject {
            put("title", buildJsonObject { put("a", 1) })
            put("count", JsonNull)
            put("loud", JsonArray(listOf(JsonPrimitive(1))))
        }
        assertTrue(FlowInputs.accepted(declared, args).isEmpty())
        assertEquals(listOf("count", "loud", "title"), FlowInputs.ignored(declared, args))
    }

    @Test
    fun `ignored lists undeclared names sorted and leaves the taken ones out`() {
        val args = buildJsonObject { put("zeta", "1"); put("title", "ok"); put("alpha", "2") }
        assertEquals(listOf("alpha", "zeta"), FlowInputs.ignored(declared, args))
    }

    @Test
    fun `the run object carries the attempt, the trigger and the args as sent`() {
        val args = buildJsonObject { put("title", "Hello"); put("other", buildJsonObject { put("a", 1) }) }
        val run = FlowInputs.runObject(2, "chain", args)
        assertEquals(JsonPrimitive(2), run["attempt"])
        assertEquals(JsonPrimitive("chain"), run["trigger"])
        assertEquals(args, run["args"])
        assertEquals(JsonObject(emptyMap()), FlowInputs.runObject(1, "manual", null)["args"])
    }
}
