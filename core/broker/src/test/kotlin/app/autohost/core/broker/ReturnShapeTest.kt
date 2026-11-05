package app.autohost.core.broker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P12-01: the `returns` grammar and the validate() semantics the conformance harness leans on. */
class ReturnShapeTest {

    @Test
    fun `given the click returns string when parsed then the enum set matches the spec`() {
        val shape = ReturnShape.parse("{method:'NODE_ACTION|ANCESTOR_ACTION|COORDINATE', verified:bool}")
        assertEquals(2, shape.size)
        assertEquals(ReturnShape.Kind.ENUM, shape["method"]!!.kind)
        assertEquals(setOf("NODE_ACTION", "ANCESTOR_ACTION", "COORDINATE"), shape["method"]!!.enumValues)
        assertEquals(ReturnShape.Kind.BOOL, shape["verified"]!!.kind)
    }

    @Test
    fun `given optional and named types when parsed then optionality and ANY are recorded`() {
        val shape = ReturnShape.parse("{node:NodeSummary?, settled:bool, text:string}")
        assertTrue(shape["node"]!!.optional)
        assertEquals(ReturnShape.Kind.ANY, shape["node"]!!.kind)
        assertFalse(shape["settled"]!!.optional)
    }

    @Test
    fun `given key-trailing optionality when parsed then the key is optional and clean`() {
        // the spec writes effect?:'...' (ACTION_HARDENING §2) — the ? rides the KEY
        val shape = ReturnShape.parse("{effect?:'changed|unchanged|unverifiable', warnings?:[string]}")
        assertTrue(shape["effect"]!!.optional)
        assertEquals(setOf("changed", "unchanged", "unverifiable"), shape["effect"]!!.enumValues)
        assertTrue(shape["warnings"]!!.optional)
        assertEquals(ReturnShape.Kind.ARRAY, shape["warnings"]!!.kind)
        assertFalse(shape.keys.contains("effect?"))
        assertFalse(shape.keys.contains("warnings?"))
    }

    @Test
    fun `given an array element type when parsed then the field is an array`() {
        val shape = ReturnShape.parse("{nodes:[NodeSummary]}")
        assertEquals(ReturnShape.Kind.ARRAY, shape["nodes"]!!.kind)
    }

    @Test
    fun `given a conforming result when validated then no violations`() {
        val shape = ReturnShape.parse("{method:'NODE_ACTION|ANCESTOR_ACTION|COORDINATE', verified:bool}")
        val result = Json.parseToJsonElement("""{"method":"NODE_ACTION","verified":true}""").jsonObject
        assertTrue(ReturnShape.validate(result, shape).isEmpty())
    }

    @Test
    fun `given a drift value when validated then the violation names the key and the allowed set`() {
        val shape = ReturnShape.parse("{method:'NODE_ACTION|ANCESTOR_ACTION|COORDINATE', verified:bool}")
        val result = Json.parseToJsonElement("""{"method":"NODE","verified":true}""").jsonObject
        val violations = ReturnShape.validate(result, shape)
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("\"method\"") && violations.single().contains("NODE"))
    }

    @Test
    fun `given an unexpected key when validated then it is a violation unless additive`() {
        val shape = ReturnShape.parse("{ok:bool}")
        val result = Json.parseToJsonElement("""{"ok":true,"extra":1}""").jsonObject
        assertTrue(ReturnShape.validate(result, shape).isNotEmpty())
        assertTrue(ReturnShape.validate(result, shape, additiveAllowed = setOf("extra")).isEmpty())
    }

    @Test
    fun `given a missing required key when validated then it is a violation`() {
        val shape = ReturnShape.parse("{method:string, verified:bool}")
        val result = Json.parseToJsonElement("""{"method":"NODE_ACTION"}""").jsonObject
        assertTrue(ReturnShape.validate(result, shape).single().contains("verified"))
    }

    @Test
    fun `given the bundled broker spec resource when parsed then every device ui returns parses`() {
        val text = javaClass.classLoader!!
            .getResourceAsStream("autohost/capability-specs-w1.json")!!
            .readBytes().decodeToString()
        val root = Json.parseToJsonElement(text).jsonObject
        val deviceUi = root["capabilities"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["id"]!!.jsonPrimitive.content == "device.ui" }
        val methods = deviceUi["methods"]!!.jsonArray.map { it.jsonObject }
        assertTrue("device.ui should declare its methods", methods.size >= 14)
        for (m in methods) {
            val returns = m["returns"]?.jsonPrimitive?.content ?: continue
            val shape = ReturnShape.parse(returns) // must not throw
            // bare named types (UiSnapshot) parse to an empty (wire-opaque) shape by design
            if (returns.startsWith("{")) {
                assertTrue("no fields parsed from $returns", shape.isNotEmpty())
            }
        }
    }
}
