package app.autohost.core.engine

import app.autohost.core.engine.export.RunExporter
import app.autohost.core.engine.host.CheckpointStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * P5-01: atomic checkpoints (write-temp-rename) and P5-09: the .aarun export bundle (plan §12.2, §13.1).
 * Gate 5-c/5-d rehearsal: the bundle alone explains the failure and the canary secret never appears.
 */
class ExportAndCheckpointsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- P5-01: checkpoint store ------------------------------------------------

    @Test
    fun `checkpoint save writes atomically and load round-trips`() {
        val dir = tmp.newFolder("run-1")
        val store = CheckpointStore { dir }
        val checkpoint = buildJsonObject {
            put("stage:setup", "done")
            put("counter", 42)
        }
        store.save("run-1", checkpoint)
        assertTrue(dir.resolve("checkpoint.json").isFile)
        assertTrue(dir.resolve("checkpoint.json.tmp").exists().not())
        assertEquals(checkpoint, store.load("run-1"))
    }

    @Test
    fun `checkpoint load falls back to the durable file when the hot copy is gone`() {
        val dir = tmp.newFolder("run-2")
        val store = CheckpointStore { dir }
        store.save("run-2", buildJsonObject { put("k", "v") })
        val fresh = CheckpointStore { dir } // simulates a new process: no hot copy
        assertEquals("""{"k":"v"}""", fresh.load("run-2").toString())
    }

    @Test
    fun `checkpoint drop removes both copies`() {
        val dir = tmp.newFolder("run-3")
        val store = CheckpointStore { dir }
        store.save("run-3", buildJsonObject { put("k", "v") })
        store.drop("run-3")
        assertNull(store.load("run-3"))
        assertTrue(dir.resolve("checkpoint.json").exists().not())
    }

    @Test
    fun `checkpoint with no mapped dir stays hot-only`() {
        val store = CheckpointStore { null }
        store.save("run-4", buildJsonObject { put("k", "v") })
        assertEquals("v", store.load("run-4")!!["k"].toString().trim('"'))
    }

    // ---- P5-09: run export --------------------------------------------------------

    @Test
    fun `bundle contains every section and a readable readme`() {
        val bytes = RunExporter().build(
            RunExporter.Bundle(
                runId = "run-9",
                taskId = "task-1",
                manifestJson = """{"id":"task-1"}""",
                checkpointJson = """{"stage:setup":"done"}""",
                eventsJsonl = """{"type":"stage_end","ok":false}""",
                auditJsonl = """{"capability":"device.ui","ok":false}""",
                incidentsJson = """{"incidents":[]}""",
                environmentJson = """{"model":"sim"}""",
                evidence = listOf(RunExporter.Bundle.EvidenceFile("dump.txt", "tree".encodeToByteArray())),
            ),
        )
        val entries = unzip(bytes)
        assertEquals(
            setOf("bundle.json", "README.txt", "manifest.json", "checkpoint.json",
                  "events.jsonl", "audit.jsonl", "incidents.json", "environment.json", "evidence/dump.txt"),
            entries.keys,
        )
        assertTrue(entries["README.txt"]!!.contains("run: run-9"))
        assertTrue(entries["checkpoint.json"]!!.contains("stage:setup"))
        assertTrue(entries["bundle.json"]!!.contains("\"format\":\"aarun\""))
    }

    @Test
    fun `checkpoint null becomes a literal null in the bundle`() {
        val bytes = RunExporter().build(
            RunExporter.Bundle("r", "t", null, null, "", "", "{}", "{}"),
        )
        assertEquals("null", unzip(bytes)["checkpoint.json"])
    }

    @Test
    fun `the bundle explains a failure end to end (Gate 5-c rehearsal)`() {
        val canary = "s3cr3t-canary-value"
        val redact: (String) -> String = { it.replace(canary, "••••••") }
        val events = listOf(
            """{"type":"stage_begin","stage":"buy","message":"stage buy started","data":{"attempt":1}}""",
            """{"type":"log","level":"error","message":"set_text failed with token $canary"}""",
            """{"type":"stage_end","stage":"buy","data":{"ok":false,"error":"Timeout: input rejected"}}""",
        ).joinToString("\n") { redact(it) }
        val bytes = RunExporter().build(
            RunExporter.Bundle(
                runId = "run-42",
                taskId = "task-7",
                manifestJson = redact("""{"id":"task-7","note":"$canary"}"""),
                checkpointJson = redact("""{"token":"$canary"}"""),
                eventsJsonl = events,
                auditJsonl = "",
                incidentsJson = """{"incidents":[{"errorCode":"TIMEOUT","open":true}]}""",
                environmentJson = "{}",
            ),
        )
        val entries = unzip(bytes)
        assertTrue(entries["events.jsonl"]!!.contains("stage_end"))
        assertTrue(entries["incidents.json"]!!.contains("TIMEOUT"))
        for ((_, content) in entries) {
            assertFalse("canary leaked into the bundle", content.contains(canary))
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    out[entry.name] = zip.readBytes().decodeToString()
                }
                entry = zip.nextEntry
            }
        }
        return out
    }
}
