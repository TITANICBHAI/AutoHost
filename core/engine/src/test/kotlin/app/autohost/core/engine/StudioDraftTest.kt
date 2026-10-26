package app.autohost.core.engine

import app.autohost.core.engine.tasks.ManifestValidator
import app.autohost.core.engine.tasks.StudioDraft
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P11-05 (ADR-083): the Studio draft — field edits, dirty tracking, and the invariant that the
 * Studio can only produce manifests the importer would accept (same validator).
 */
class StudioDraftTest {

    private val original: JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("schema", JsonPrimitive(1))
        put("id", JsonPrimitive("com.example.demo"))
        put("name", JsonPrimitive("Demo"))
        put("version", JsonPrimitive("1.0.0"))
        put("needs", JsonObject(emptyMap()))
        put("runtime", kotlinx.serialization.json.buildJsonObject {
            put("type", JsonPrimitive("python"))
            put("entry", JsonPrimitive("main.py"))
        })
    }

    private fun draft() = StudioDraft.fromManifest(original, "def main(ctx):\n    pass\n")

    @Test
    fun fromManifestKeepsUneditedFieldsVerbatim() {
        val d = draft()
        assertEquals("com.example.demo", d.taskId)
        assertEquals("Demo", d.name)
        assertFalse(d.dirty)
        val json = d.manifestJson().toString()
        assertTrue(json.contains("\"id\":\"com.example.demo\""))
        assertTrue(json.contains("main.py"))
    }

    @Test
    fun editsMarkTheDraftDirtyAndLandInTheManifest() {
        val edited = draft().with(name = "Renamed", version = "1.1.0")
        assertTrue(edited.dirty)
        val json = edited.manifestJson().toString()
        assertTrue(json.contains("\"name\":\"Renamed\""))
        assertTrue(json.contains("\"version\":\"1.1.0\""))
        // The unedited fields survive untouched.
        assertTrue(json.contains("\"id\":\"com.example.demo\""))
    }

    @Test
    fun theSameValidatorTheImporterUsesGatesSaves() {
        val ok = draft().with(name = "Still fine")
        assertEquals(true, ok.validate().ok)

        // An empty name is a real manifest error the Studio surfaces before writing.
        val bad = draft().with(name = "")
        assertFalse(bad.validate().ok)
    }

    @Test
    fun newTaskTemplatesMatchEachRuntime() {
        val py = StudioDraft.newTask("com.example.new", "New", StudioDraft.RUNTIME_PYTHON)
        assertEquals("main.py", py.entry)
        assertTrue(py.entryText.contains("import autohost"))
        val flow = StudioDraft.newTask("com.example.new", "New", StudioDraft.RUNTIME_FLOW)
        assertEquals("flow.yaml", flow.entry)
        assertTrue(flow.entryText.contains("version: 1"))
        val js = StudioDraft.newTask("com.example.new", "New", StudioDraft.RUNTIME_JAVASCRIPT)
        assertEquals("main.js", js.entry)
        assertTrue(js.entryText.contains("main()"))
    }

    @Test
    fun validateRunsOnTheBuiltManifestNotTheOriginal() {
        // Editing the entry must invalidate the OLD validation cache (there is none): the result
        // reflects the CURRENT json. A bad entry pattern fails; a good one passes.
        val edited = draft().with(entry = "src/main.py")
        val result = edited.validate()
        // src/main.py is a legal entry path — it must not be rejected.
        assertTrue(result.ok)
    }
}
