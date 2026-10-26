package app.autohost.runtime.flow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** P6-03: flow-form selector -> broker wire form + label resolution (mirrors test_ref_flows.SelectorTests). */
class SelectorResolverTest {
    private fun wire(json: String) = FlowParser.toWireSelector(Json.parseToJsonElement(json).jsonObject)

    @Test fun `string label becomes eq match`() {
        val wire = wire("""{"text": "Continue"}""")
        assertEquals(
            """{"op":"and","terms":[{"text":{"labels":["Continue"],"mode":"eq","case_sensitive":false}}]}""",
            wire.toString(),
        )
    }

    @Test fun `list labels become one match`() {
        val wire = wire("""{"id": ["a", "b"]}""")
        assertEquals(
            """{"op":"and","terms":[{"id":{"labels":["a","b"],"mode":"eq","case_sensitive":false}}]}""",
            wire.toString(),
        )
    }

    @Test fun `object form overrides mode`() {
        val wire = wire("""{"text": {"value": "^Done", "mode": "regex"}}""")
        assertEquals(
            """{"op":"and","terms":[{"text":{"labels":["^Done"],"mode":"regex","case_sensitive":false}}]}""",
            wire.toString(),
        )
    }

    @Test fun `package key maps to pkg term`() {
        val wire = wire("""{"package": "com.example"}""")
        assertEquals("pkg", (wire["terms"]!! as kotlinx.serialization.json.JsonArray)[0].jsonObject.keys.first())
    }

    @Test fun `state keys fold into single state term`() {
        val wire = wire("""{"clickable": true, "enabled": false}""")
        assertEquals(
            """{"op":"and","terms":[{"state":{"clickable":true,"enabled":false}}]}""",
            wire.toString(),
        )
    }

    @Test fun `relations map to wire kinds`() {
        val wire = wire("""{"text": "Save", "below": {"id": "title"}, "containsDescendants": [{"text": "x"}]}""")
        val terms = wire["terms"]!! as kotlinx.serialization.json.JsonArray
        assertEquals(listOf("text", "relation", "relation"), terms.map { it.jsonObject.keys.first() })
        assertEquals(
            "below",
            (terms[1].jsonObject.getValue("relation").jsonObject)["kind"].jsonPrimitive.content,
        )
        assertEquals(
            "contains_descendants",
            (terms[2].jsonObject.getValue("relation").jsonObject)["kind"].jsonPrimitive.content,
        )
    }

    @Test fun `index folds remaining criteria into one relation term`() {
        val wire = wire("""{"class": "android.widget.Button", "index": 1}""")
        val terms = wire["terms"]!! as kotlinx.serialization.json.JsonArray
        assertEquals(1, terms.size)
        val relation = terms[0].jsonObject.getValue("relation").jsonObject
        assertEquals("index", relation["kind"].jsonPrimitive.content)
        assertEquals(1, relation["n"].jsonPrimitive.int)
        assertEquals(
            """{"op":"and","terms":[{"class":{"labels":["android.widget.Button"],"mode":"eq","case_sensitive":false}}]}""",
            relation.getValue("selector").jsonObject.toString(),
        )
    }

    @Test fun `unknown selector key fails`() {
        assertThrows(FlowParseException::class.java) { wire("""{"xpath": "//foo"}""") }
    }
}
