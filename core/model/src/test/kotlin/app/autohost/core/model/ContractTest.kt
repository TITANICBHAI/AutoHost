package app.autohost.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Test fun `error codes and retryability match schemas errors json`() {
        val spec = Json.parseToJsonElement(repoFile("schemas/errors.json").readText()).jsonObject
        val fromSchema = spec.getValue("brokerErrors").jsonArray.associate {
            val o = it.jsonObject
            o.getValue("code").jsonPrimitive.content to o.getValue("retryable").jsonPrimitive.boolean
        }
        val fromEnum = ErrorCode.values().associate { it.name to it.retryable }
        assertEquals(fromSchema, fromEnum)
    }

    @Test fun `ok envelope has no error and err envelope has no result`() {
        val ok = json.encodeToString(Envelope.serializer(), Envelope.ok())
        assertTrue(ok.contains("\"ok\":true"))
        assertFalse(ok.contains("\"error\""))
        val err = json.encodeToString(Envelope.serializer(), Envelope.err(ErrorCode.STALE_NODE, "gone"))
        assertTrue(err.contains("\"ok\":false"))
        assertTrue(err.contains("\"code\":\"STALE_NODE\""))
        assertTrue(err.contains("\"retryable\":true"))
        assertFalse(err.contains("\"result\""))
    }

    @Test fun `envelope round trips`() {
        val e = Envelope.err(ErrorCode.SCOPE_VIOLATION, "no", reason = "R", fixAction = "F")
        assertEquals(e, json.decodeFromString(Envelope.serializer(), json.encodeToString(Envelope.serializer(), e)))
    }

    @Test fun `selector json from the python sdk decodes`() {
        val wire = """{"op":"and","terms":[{"text":{"labels":["Continue"],"mode":"contains","case_sensitive":false}}],"nth":0}"""
        val s = json.decodeFromString(Selector.serializer(), wire)
        assertEquals("Continue", s.terms.single().text!!.labels.single())
        assertEquals(0, s.nth)
    }

    @Test fun `run statuses match schemas errors json`() {
        val spec = Json.parseToJsonElement(repoFile("schemas/errors.json").readText()).jsonObject
        val names = spec.getValue("runStatuses").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(names, RunStatus.values().map { it.name })
        val terminal = spec.getValue("terminalStatuses").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(terminal, RunStatus.values().filter { it.terminal }.map { it.name }.toSet())
    }

    @Test fun `press-button manifest parses into TaskManifest`() {
        val text = repoFile("samples/press-button/task.json").readText()
        val m = json.decodeFromString(TaskManifest.serializer(), text)
        assertEquals("python", m.runtime.type)
        assertEquals("device.ui.act", m.needs.capabilities.last().id)
        assertEquals("\${config.target_package}", m.needs.apps.single().packageName)
        assertEquals(5, m.execution.timeoutMinutes)
    }
}
