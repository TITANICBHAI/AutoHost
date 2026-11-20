package app.autohost.ui2

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin copy of the failure texts must be the catalog's texts, word for word (docs/CONVENTIONS.md). */
class ReasonCopyTest {

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("repo file not found from ${System.getProperty("user.dir")}: $relative")
    }

    private data class Entry(val code: String, val text: String, val hasParams: Boolean, val fixAction: String?)

    private fun catalog(): List<Entry> {
        val root = Json.parseToJsonElement(repoFile("schemas/errors.json").readText()).jsonObject
        return root.getValue("reasonCodes").jsonArray.map { element ->
            val item = element.jsonObject
            Entry(
                code = item.getValue("code").jsonPrimitive.content,
                text = item.getValue("userMessage").jsonPrimitive.content,
                hasParams = item["params"]?.jsonArray?.isNotEmpty() == true,
                fixAction = item["fixAction"]?.jsonPrimitive?.content,
            )
        }
    }

    /** The words on each fix action's button, as the catalog gives them. */
    private fun fixLabels(): Map<String, String> {
        val root = Json.parseToJsonElement(repoFile("schemas/errors.json").readText()).jsonObject
        return root.getValue("fixActions").jsonArray.associate { element ->
            val item = element.jsonObject
            item.getValue("id").jsonPrimitive.content to (item["label"]?.jsonPrimitive?.content ?: "")
        }
    }

    private fun isPlain(entry: Entry) = !(entry.hasParams || entry.text.contains('{'))

    /** The button words for a plain reason, or null when the catalog names no fix (NONE) or gives it no words. */
    private fun buttonWords(entry: Entry, labels: Map<String, String>): String? {
        if (!isPlain(entry)) return null
        val action = entry.fixAction ?: return null
        if (action == "NONE") return null
        return labels[action]?.takeIf { it.isNotBlank() }
    }

    @Test fun `every catalog text that needs no value is copied word for word`() {
        val wanted = catalog().filterNot { it.hasParams || it.text.contains('{') }
        assertTrue("the catalog has plain reason codes", wanted.size > 30)
        for (entry in wanted) assertEquals(entry.code, entry.text, ReasonCopy.text(entry.code))
    }

    @Test fun `the table holds exactly those codes and no other`() {
        val wanted = catalog().filterNot { it.hasParams || it.text.contains('{') }.map { it.code }.toSet()
        assertEquals(wanted, ReasonCopy.table.keys)
    }

    @Test fun `a text that needs a package or a number is never shown without it`() {
        for (entry in catalog().filter { it.hasParams || it.text.contains('{') }) {
            assertNull(entry.code, ReasonCopy.text(entry.code))
        }
        assertFalse(ReasonCopy.table.values.any { it.contains('{') || it.contains('}') })
    }

    @Test fun `every plain reason that names a fix gets the catalog's button, word for word`() {
        val labels = fixLabels()
        val withButton = catalog().filter { buttonWords(it, labels) != null }
        assertTrue("the catalog has plain reasons with a button", withButton.size > 10)
        for (entry in withButton) {
            assertEquals(entry.code, ReasonFix(entry.fixAction!!, buttonWords(entry, labels)!!), ReasonCopy.fix(entry.code))
        }
    }

    @Test fun `the button table holds exactly those reasons and no other`() {
        val labels = fixLabels()
        val wanted = catalog().filter { buttonWords(it, labels) != null }.map { it.code }.toSet()
        assertEquals(wanted, ReasonCopy.fixTable.keys)
    }

    @Test fun `a reason with no fix, or whose text needs a value, has no button`() {
        val labels = fixLabels()
        for (entry in catalog().filter { buttonWords(it, labels) == null }) assertNull(entry.code, ReasonCopy.fix(entry.code))
        assertNull(ReasonCopy.fix(null))
        assertNull(ReasonCopy.fix("START_FAILED"))
        assertTrue("every button has words", ReasonCopy.fixTable.values.all { it.label.isNotBlank() && it.action != "NONE" })
    }

    @Test fun `an unknown or missing code has no text`() {
        assertNull(ReasonCopy.text(null))
        assertNull(ReasonCopy.text("START_FAILED"))
        assertNull(ReasonCopy.text(""))
    }

    @Test fun `the reasons an agent run can stop with all have plain text`() {
        val agent = listOf(
            "USER_CANCELLED", "IDLE_TIMEOUT_REASON", "AI_DISABLED", "AI_NOT_CONFIGURED", "OBSERVATION_BLOCKED",
            "MODEL_AUTH", "MODEL_RATE_LIMITED", "MODEL_UNREACHABLE", "MODEL_BAD_RESPONSE", "MODEL_CONTEXT_OVERFLOW",
            "MODEL_FILTERED", "BUDGET_TURNS", "BUDGET_TIME", "BUDGET_MODEL_CALLS", "BUDGET_TOKENS", "BUDGET_ACTIONS",
            "BUDGET_CONTEXT", "AGENT_FAILURES", "AGENT_STUCK", "AGENT_INTERNAL_ERROR", "APPROVAL_DENIED",
            "APPROVAL_TIMEOUT", "COMPLETION_REJECTED", "COMPLETION_UNVERIFIED", "PASSWORD_FIELD", "TOOL_NOT_ALLOWED",
            "UI_RUN_ACTIVE", "A11Y_LOST", "SCREEN_LOCKED", "A11Y_SERVICE_DISABLED",
        )
        for (code in agent) assertTrue(code, !ReasonCopy.text(code).isNullOrBlank())
    }
}
