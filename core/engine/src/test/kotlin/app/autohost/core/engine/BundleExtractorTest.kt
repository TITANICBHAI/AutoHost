package app.autohost.core.engine

import app.autohost.core.engine.tasks.BundleExtractor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class BundleExtractorTest {

    private lateinit var staging: File
    private lateinit var tasksRoot: File
    private lateinit var extractor: BundleExtractor

    private val manifest = """
        {
          "schema": 1,
          "id": "com.example.extractor",
          "name": "Extractor",
          "version": "1.0.0",
          "runtime": {"type": "python", "entry": "main.py"},
          "ui": {"mode": "auto"}
        }
    """.trimIndent()

    @Before
    fun setUp() {
        staging = Files.createTempDirectory("ah-staging").toFile()
        tasksRoot = Files.createTempDirectory("ah-tasks").toFile()
        extractor = BundleExtractor(staging, tasksRoot)
    }

    @After
    fun tearDown() {
        staging.deleteRecursively()
        tasksRoot.deleteRecursively()
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                if (content.isNotEmpty()) zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun bundle(mainPy: String = "print('hi')\n", extra: Array<Pair<String, ByteArray>> = emptyArray()): ByteArray {
        val entries = mutableListOf(
            "task.json" to manifest.encodeToByteArray(),
            "main.py" to mainPy.encodeToByteArray(),
            "lib/util.py" to "x = 1\n".encodeToByteArray(),
        )
        entries.addAll(extra)
        return zipOf(*entries.toTypedArray())
    }

    @Test
    fun `stages a good bundle with a sha and returns manifest fields`() {
        val staged = extractor.stage(ByteArrayInputStream(bundle()), "com.example.extractor-1.0.0.aatask")
        assertEquals("com.example.extractor", staged.taskId)
        assertEquals("1.0.0", staged.version)
        assertEquals("main.py", staged.entryPath)
        assertEquals(64, staged.sha256.length)
        assertTrue(staged.manifest.contains("com.example.extractor"))
        // same bytes → same hash (integrity hash is deterministic)
        val again = extractor.stage(ByteArrayInputStream(bundle()), "x.aatask")
        assertEquals(staged.sha256, again.sha256)
    }

    @Test
    fun `install moves the bundle read-only under tasks root and refuses a re-install`() {
        val staged = extractor.stage(ByteArrayInputStream(bundle()), "b.aatask")
        val dir = extractor.install(staged)
        assertEquals(File(File(tasksRoot, "com.example.extractor"), "1.0.0").canonicalPath, dir.canonicalPath)
        assertTrue(File(dir, "main.py").isFile)
        assertFalse(File(dir, "main.py").canWrite())
        assertFalse(File(dir, "task.json").canWrite())
        try {
            extractor.install(staged)
            fail("re-install of the same version must be refused")
        } catch (e: BundleExtractor.BundleException) {
            assertEquals("VERSION_EXISTS", e.rule)
        }
    }

    @Test
    fun `bundle without task json is rejected`() {
        val zip = zipOf("main.py" to "print(1)\n".encodeToByteArray())
        try {
            extractor.stage(ByteArrayInputStream(zip), "no-manifest.aatask")
            fail("missing manifest must be rejected")
        } catch (e: BundleExtractor.BundleException) {
            assertEquals("NO_MANIFEST", e.rule)
        }
    }

    @Test
    fun `zip slip entries are rejected`() {
        val evil = zipOf(
            "task.json" to manifest.encodeToByteArray(),
            "main.py" to "print(1)\n".encodeToByteArray(),
            "../escaped.txt" to "x".encodeToByteArray(),
        )
        try {
            extractor.stage(ByteArrayInputStream(evil), "evil.aatask")
            fail("zip slip must be rejected")
        } catch (e: BundleExtractor.BundleException) {
            assertEquals("ZIP_SLIP", e.rule)
        }
        // absolute entry name
        val absolute = zipOf(
            "task.json" to manifest.encodeToByteArray(),
            "/etc/escaped.txt" to "x".encodeToByteArray(),
        )
        try {
            extractor.stage(ByteArrayInputStream(absolute), "evil2.aatask")
            fail("absolute entry must be rejected")
        } catch (e: BundleExtractor.BundleException) {
            assertEquals("UNSAFE_PATH", e.rule)
        }
    }

    @Test
    fun `missing runtime entry or ui file is rejected`() {
        val noEntry = zipOf(
            "task.json" to manifest.encodeToByteArray(),
            "lib/other.py" to "x=1\n".encodeToByteArray(),
        )
        try {
            extractor.stage(ByteArrayInputStream(noEntry), "no-entry.aatask")
            fail("missing runtime entry must be rejected")
        } catch (e: BundleExtractor.BundleException) {
            assertEquals("ENTRY_MISSING", e.rule)
        }
        val withUiFile = manifest.replace("\"ui\": {\"mode\": \"auto\"}", "\"ui\": {\"mode\": \"declarative\", \"file\": \"ui/main.json\"}")
        val zip = zipOf(
            "task.json" to withUiFile.encodeToByteArray(),
            "main.py" to "print(1)\n".encodeToByteArray(),
        )
        try {
            extractor.stage(ByteArrayInputStream(zip), "no-ui.aatask")
            fail("missing ui file must be rejected")
        } catch (e: BundleExtractor.BundleException) {
            assertEquals("UI_FILE_MISSING", e.rule)
        }
    }

    @Test
    fun `invalid manifest inside the bundle is rejected with MANIFEST_INVALID`() {
        val broken = manifest.replace("\"id\": \"com.example.extractor\"", "\"id\": \"BAD\"")
        val zip = zipOf(
            "task.json" to broken.encodeToByteArray(),
            "main.py" to "print(1)\n".encodeToByteArray(),
        )
        try {
            extractor.stage(ByteArrayInputStream(zip), "broken.aatask")
            fail("invalid manifest must be rejected")
        } catch (e: BundleExtractor.BundleException) {
            assertEquals("MANIFEST_INVALID", e.rule)
        }
    }

    @Test
    fun `oversized entries are rejected`() {
        val big = ByteArray((BundleExtractor.MAX_ENTRY_BYTES + 1).toInt()) { 0 }
        val zip = zipOf(
            "task.json" to manifest.encodeToByteArray(),
            "main.py" to big,
        )
        try {
            extractor.stage(ByteArrayInputStream(zip), "big.aatask")
            fail("oversized entry must be rejected")
        } catch (e: BundleExtractor.BundleException) {
            assertEquals("ENTRY_TOO_LARGE", e.rule)
        }
    }

    @Test
    fun `sha256 helper matches a known digest`() {
        val tmp = File.createTempFile("sha", ".txt")
        tmp.writeText("hello\n")
        val sha = BundleExtractor.sha256Of(tmp)
        assertEquals("5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03", sha)
        tmp.delete()
    }
}
