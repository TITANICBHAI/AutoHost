package app.autohost.runtime.flow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** P6-01b: parser contract — the same cases the Python reference pins (test_ref_flows.ParseTests). */
class FlowParserTest {
    private fun parse(text: String) = FlowParser.parse(text)

    @Test fun `minimal flow parses and normalises`() {
        val flow = parse("""{"schema": 1, "name": "F", "tags": ["a"], "env": {"x": 1},
            "steps": [{"log": {"message": "hi"}}]}""")
        assertEquals(1, flow.schema)
        assertEquals("F", flow.name)
        assertEquals(listOf("a"), flow.tags)
        assertEquals(1, flow.steps.size)
        assertEquals("log", flow.steps[0].command)
    }

    @Test fun `missing steps fails`() {
        val e = assertThrows(FlowParseException::class.java) { parse("""{"schema": 1}""") }
        assertTrue(e.message!!.contains("steps"))
    }

    @Test fun `empty steps fails`() {
        val e = assertThrows(FlowParseException::class.java) { parse("""{"schema": 1, "steps": []}""") }
        assertTrue(e.message!!.contains("non-empty"))
    }

    @Test fun `two commands in one step fails`() {
        val e = assertThrows(FlowParseException::class.java) {
            parse("""{"schema": 1, "steps": [{"log": {"message": "a"}, "setResult": {"code": "b"}}]}""")
        }
        assertEquals(
            "flow invalid: steps[0]: a step must have exactly one command key, got ['log', 'setResult']",
            e.message,
        )
    }

    @Test fun `unknown command fails`() {
        val e = assertThrows(FlowParseException::class.java) {
            parse("""{"schema": 1, "steps": [{"frobnicate": {}}]}""")
        }
        assertTrue(e.message!!.contains("unknown command"))
    }

    @Test fun `unknown body key fails`() {
        val e = assertThrows(FlowParseException::class.java) {
            parse("""{"schema": 1, "steps": [{"launchApp": {"package": "p", "nope": 1}}]}""")
        }
        assertTrue(e.message!!.contains("unknown key"))
    }

    @Test fun `selector command without selector fails`() {
        val e = assertThrows(FlowParseException::class.java) {
            parse("""{"schema": 1, "steps": [{"tapOn": {"optional": true}}]}""")
        }
        assertTrue(e.message!!.contains("at least one selector key"))
    }

    @Test fun `waitUntil needs exactly one side`() {
        for (body in listOf(
            """{"visible": {"text": "a"}, "notVisible": {"text": "b"}}""",
            """{"timeout": 1}""",
        )) {
            val e = assertThrows(FlowParseException::class.java) {
                parse("""{"schema": 1, "steps": [{"waitUntil": $body}]}""")
            }
            assertTrue(e.message!!.contains("exactly one of visible / notVisible"))
        }
    }

    @Test fun `unknown press key fails`() {
        val e = assertThrows(FlowParseException::class.java) {
            parse("""{"schema": 1, "steps": [{"pressKey": {"key": "channel_up"}}]}""")
        }
        assertTrue(e.message!!.contains("key must be one of"))
    }

    @Test fun `bad timeout fails`() {
        val e = assertThrows(FlowParseException::class.java) {
            parse("""{"schema": 1, "steps": [{"waitFor": {"text": "a", "timeout": -1}}]}""")
        }
        assertTrue(e.message!!.contains("timeout"))
    }

    @Test fun `repeat needs exactly one of times and while`() {
        for (body in listOf("""{"times": 2, "while": "true", "steps": [{"log": {"message": "x"}}]}""",
            """{"steps": [{"log": {"message": "x"}}]}""")) {
            assertThrows(FlowParseException::class.java) {
                parse("""{"schema": 1, "steps": [{"repeat": $body}]}""")
            }
        }
    }

    @Test fun `nested repeat steps are parsed recursively`() {
        val e = assertThrows(FlowParseException::class.java) {
            parse("""{"schema": 1, "steps": [{"repeat": {"times": 2, "steps": [{"frob": {}}]}}]}""")
        }
        assertTrue(e.message!!.contains("steps[0]: repeat steps[0]: unknown command"))
    }

    @Test fun `timeout modifier converts seconds to milliseconds`() {
        val flow = parse("""{"schema": 1, "steps": [{"waitFor": {"text": "a", "timeout": 2.5}}]}""")
        assertEquals(2500L, flow.steps[0].timeoutMs)
        val defaulted = parse("""{"schema": 1, "steps": [{"waitFor": {"text": "a"}}]}""")
        assertEquals(null, defaulted.steps[0].timeoutMs)
    }

    // --- INT-A: runTask (the exact messages are pinned by the golden parse_error cases) ---

    @Test fun `runTask parses with inputs, a variable for the answer, a wait and optional`() {
        val flow = parse(
            """{"schema": 1, "steps": [{"runTask": {"task": "gemini-send",
                "args": {"prompt": "hi", "n": 2, "loud": true}, "into": "r", "timeout": 30, "optional": true}}]}""",
        )
        val step = flow.steps[0]
        assertEquals("runTask", step.command)
        assertEquals(30_000L, step.timeoutMs)
        assertTrue(step.optional)
        assertEquals("gemini-send", step.body.getValue("task").jsonPrimitive.content)
    }

    @Test fun `runTask refuses a templated id, a missing id, odd inputs and unknown keys`() {
        fun fails(body: String, expect: String) {
            val e = assertThrows(FlowParseException::class.java) {
                parse("""{"schema": 1, "steps": [{"runTask": $body}]}""")
            }
            assertTrue(e.message, e.message!!.contains(expect))
        }
        fails("""{"task": "a${'$'}{b}"}""", "fixed task id")
        fails("""{"into": "r"}""", "task must be a non-empty string")
        fails("""{"task": "t", "args": ["x"]}""", "args must be an object")
        fails("""{"task": "t", "args": {"a": [1]}}""", "args.a must be text, a number or true/false")
        fails("""{"task": "t", "args": {"a": null}}""", "args.a must be text, a number or true/false")
        fails("""{"task": "t", "profile": "p"}""", "unknown key")
    }

    @Test fun `parse error golden cases fail with the recorded message`() {
        val doc = Json.parseToJsonElement(
            java.io.File(System.getProperty("user.dir")).let { dir ->
                var d: java.io.File? = dir.absoluteFile
                while (d != null) {
                    val candidate = java.io.File(d, "testing/golden/flow_vectors.json")
                    if (candidate.exists()) return@let candidate
                    d = d.parentFile
                }
                error("flow_vectors.json not found")
            }.readText(),
        ).jsonObject
        for (case in doc.getValue("cases").jsonArray) {
            val obj = case.jsonObject
            if (obj.getValue("kind").jsonPrimitive.content != "parse_error") continue
            val name = obj.getValue("name").jsonPrimitive.content
            val expected = obj.getValue("expected").jsonObject.getValue("error").jsonPrimitive.content
            val e = assertThrows(FlowParseException::class.java) {
                FlowParser.fromJson(obj.getValue("flow").jsonObject)
            }
            assertEquals("parse_error case $name", expected, e.message)
        }
    }
}
