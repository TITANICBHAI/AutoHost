package app.autohost.ui.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P14-05 (S31, Gate 5-d): the `.aarun` agent-section viewer — the zip extraction, the shape
 * parsing, the redaction IDENTICAL to the export bundle, and the honest null for bundles
 * without an agent section.
 */
class AgentSectionViewerStateTest {

    /** The same redaction the export would apply. */
    private val redact: (String) -> String = { it.replace("sk-canary-67890", "[redacted]") }

    private fun state() = AgentSectionViewerState(redact)

    private fun sectionJson(keepTranscript: Boolean = true): JsonObject = buildJsonObject {
        put("goal", "type sk-canary-67890 into the field")
        put("packages", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("com.android.settings"))))
        put("provider_profile", "profile-1")
        put("usage", buildJsonObject {
            put("turns", 3); put("model_calls", 3); put("actions", 2); put("tokens", 450)
        })
        put("trace", buildJsonObject { put("kept", 2); put("dropped", 4); put("keep_transcript", keepTranscript) })
        put("stop", buildJsonObject { put("status", "FAILED"); put("reason", "COMPLETION_UNVERIFIED") })
        if (keepTranscript) {
            put("transcript", kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject { put("kind", "agent.action"); put("args", """{"text":"sk-canary-67890"}""") },
                buildJsonObject { put("kind", "agent.thought"); put("text", "typing the secret") },
            )))
        }
    }

    @Test
    fun `given an agent json when loaded then the shape parses for display`() {
        val s = state()
        s.load(sectionJson())
        val section = s.section!!
        assertEquals("type [redacted] into the field", section.goal) // the SAME redaction as the export
        assertEquals(listOf("com.android.settings"), section.packages)
        assertEquals(3, section.usage.turns)
        assertEquals(450L, section.usage.tokens)
        assertEquals(4, section.traceDropped)
        assertEquals("COMPLETION_UNVERIFIED", section.stopReason)
    }

    @Test
    fun `given the transcript when loaded then every line passes the export redactor`() {
        val s = state()
        s.load(sectionJson())
        val lines = s.section!!.transcript
        assertEquals(2, lines.size)
        assertTrue(!lines[0].text.contains("sk-canary-67890"))
        assertEquals("""{"text":"[redacted]"}""", lines[0].text)
    }

    @Test
    fun `given a bundle without a transcript (keep_transcript off) when loaded then no transcript exists to show`() {
        val s = state()
        s.load(sectionJson(keepTranscript = false))
        assertTrue(s.section!!.transcript.isEmpty())
    }

    @Test
    fun `given a real aarun zip when read then agent json extracts (and other entries are ignored)`() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            out.putNextEntry(ZipEntry("events.jsonl"))
            out.write("""{"type":"log"}""".encodeToByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("agent.json"))
            out.write(sectionJson().toString().encodeToByteArray())
            out.closeEntry()
        }
        val s = state()
        assertTrue(s.load(zip.toByteArray()))
        assertEquals(3, s.section!!.usage.turns)
    }

    @Test
    fun `given a bundle without agent json when loaded then honestly null (a non-agent run)`() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            out.putNextEntry(ZipEntry("events.jsonl"))
            out.write("""{"type":"log"}""".encodeToByteArray())
            out.closeEntry()
        }
        val s = state()
        assertFalse(s.load(zip.toByteArray()))
        assertNull(s.section)
    }

    @Test
    fun `given corrupt bytes when loaded then honest null, never a crash`() {
        val s = state()
        assertFalse(s.load("not a zip".encodeToByteArray()))
        assertNull(s.section)
    }
}
