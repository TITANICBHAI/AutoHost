package app.autohost.runtime.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** INT-A1: the model is sent a real object schema for every tool, not the catalog's own whitelist form. */
class ToolSchemaTest {

    private val catalog = ToolCatalog.load()

    private fun spec(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `given every catalog tool when its parameters are built then each is an object schema in plain JSON Schema words`() {
        val knownKeywords = setOf("type", "enum", "description", "minimum", "maximum", "items")
        for (tool in catalog.specs()) {
            val parameters = tool.parameters
            assertEquals("${tool.name}: type", "object", parameters["type"]!!.jsonPrimitive.content)
            assertFalse("${tool.name}: Gemini refuses additionalProperties", parameters.containsKey("additionalProperties"))
            for ((name, property) in parameters["properties"]!!.jsonObject) {
                assertFalse("${tool.name}.$name: a '?' belongs to the whitelist, not to a name", name.contains('?'))
                val keys = property.jsonObject.keys
                assertTrue("${tool.name}.$name uses words JSON Schema does not know: $keys", knownKeywords.containsAll(keys))
                assertTrue("${tool.name}.$name has no type", keys.contains("type"))
            }
        }
    }

    @Test
    fun `given the real catalog when read then the validator and the model are told the same argument names`() {
        for (tool in catalog.specs()) {
            val entry = catalog.find(tool.name)!!
            val named = entry.args.keys.map { it.removeSuffix("?") }.toSet()
            val optional = entry.args.keys.filter { it.endsWith("?") }.map { it.removeSuffix("?") }.toSet()
            assertEquals("${tool.name}: names", named, tool.parameters["properties"]!!.jsonObject.keys)
            val required = tool.parameters["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
            assertEquals("${tool.name}: required", named - optional, required)
        }
    }

    @Test
    fun `given a tool with a required and an optional argument when built then only the required one is listed as required`() {
        val find = catalog.specs().first { it.name == "ui_find" }.parameters
        assertEquals(listOf("selector"), find["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(setOf("selector", "limit"), find["properties"]!!.jsonObject.keys)
    }

    @Test
    fun `given a tool without arguments when built then it has no properties and no required list`() {
        val snapshot = catalog.specs().first { it.name == "ui_snapshot" }.parameters
        assertEquals("object", snapshot["type"]!!.jsonPrimitive.content)
        assertTrue(snapshot["properties"]!!.jsonObject.isEmpty())
        assertFalse(snapshot.containsKey("required"))
    }

    @Test
    fun `given the whitelist words when converted then each becomes the matching JSON Schema keyword`() {
        val built = ToolSchema.parameters(
            spec("""{"mode":{"enum":["a","b"]},"limit?":{"max":7},"pkg":{"enum_of":"run_packages"},"task":{"enum_of":"run_tasks"},"free":{}}"""),
        )
        val properties = built["properties"]!!.jsonObject
        assertEquals(setOf("mode", "limit", "pkg", "task", "free"), properties.keys)
        assertEquals(listOf("a", "b"), properties["mode"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("integer", properties["limit"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("0", properties["limit"]!!.jsonObject["minimum"]!!.jsonPrimitive.content)
        assertEquals("7", properties["limit"]!!.jsonObject["maximum"]!!.jsonPrimitive.content)
        assertTrue(properties["pkg"]!!.jsonObject["description"]!!.jsonPrimitive.content.contains("apps"))
        assertTrue(properties["task"]!!.jsonObject["description"]!!.jsonPrimitive.content.contains("saved tasks"))
        assertEquals("string", properties["free"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("mode", "pkg", "task", "free"), built["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `given a list of name=value texts when converted then the model is told it is an array of strings`() {
        val built = ToolSchema.parameters(spec("""{"inputs?":{"pairs":8}}"""))
        val inputs = built["properties"]!!.jsonObject["inputs"]!!.jsonObject
        assertEquals("array", inputs["type"]!!.jsonPrimitive.content)
        assertEquals("string", inputs["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(inputs["description"]!!.jsonPrimitive.content.contains("name=value"))
        assertFalse(built.containsKey("required"))
    }

    @Test
    fun `given a list of short texts when converted then the model is told it is an optional array of strings (INT-A2)`() {
        val built = ToolSchema.parameters(
            spec("""{"message":{},"kind":{"enum":["text","choice","confirm"]},"choices?":{"list":4}}"""),
        )
        val choices = built["properties"]!!.jsonObject["choices"]!!.jsonObject
        assertEquals("array", choices["type"]!!.jsonPrimitive.content)
        assertEquals("string", choices["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(choices["description"]!!.jsonPrimitive.content.contains("Short texts"))
        assertEquals(listOf("message", "kind"), built["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
