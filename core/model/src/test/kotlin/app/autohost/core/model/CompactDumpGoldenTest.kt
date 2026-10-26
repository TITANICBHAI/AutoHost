package app.autohost.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Python reference (sdk-python/autohost_ref/dump.py) produced testing/golden/compact_dump.json; Kotlin must match it. */
class CompactDumpGoldenTest {
    private fun toNodes(arr: kotlinx.serialization.json.JsonArray): List<UiNode> = arr.mapIndexed { i, el ->
        val o = el.jsonObject
        val b = o.getValue("bounds").jsonArray.map { it.jsonPrimitive.int }
        val flags = o.getValue("flags").jsonArray.map { it.jsonPrimitive.content }.toMutableSet()
        if (o["password"]?.jsonPrimitive?.boolean == true) flags += "password"
        UiNode(i, -1, o.getValue("depth").jsonPrimitive.int, "", o.getValue("cls").jsonPrimitive.content,
            o.getValue("id").jsonPrimitive.content, o.getValue("text").jsonPrimitive.content,
            o.getValue("desc").jsonPrimitive.content, Bounds(b[0], b[1], b[2], b[3]), flags)
    }

    @Test fun `every golden case matches the reference output`() {
        val doc = Json.parseToJsonElement(repoFile("testing/golden/compact_dump.json").readText()).jsonObject
        val cases = doc.getValue("cases").jsonArray
        assertTrue("no golden cases found", cases.isNotEmpty())
        for (c in cases) {
            val case: JsonObject = c.jsonObject
            val input = case.getValue("input").jsonObject
            fun str(k: String) = input[k]?.jsonPrimitive?.content ?: ""
            val filter = if (case.getValue("filter") is JsonNull) null else DumpFilter()
            val actual = CompactDump.render(
                toNodes(input.getValue("nodes").jsonArray),
                label = str("label"), pkg = str("package"), activity = str("activity"),
                keyboardVisible = str("keyboard") == "visible", filter = filter,
            )
            assertEquals("case ${case.getValue("name").jsonPrimitive.content}", case.getValue("expected").jsonPrimitive.content, actual)
        }
    }
}
