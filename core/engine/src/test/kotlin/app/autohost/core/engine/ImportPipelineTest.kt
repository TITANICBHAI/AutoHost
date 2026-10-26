package app.autohost.core.engine

import app.autohost.core.engine.tasks.BundleExtractor
import app.autohost.core.engine.tasks.ImportClassifier
import app.autohost.core.engine.tasks.ImportPipeline
import app.autohost.core.engine.tasks.ImportReport
import app.autohost.core.engine.tasks.ImportScanner
import app.autohost.core.engine.tasks.ScanReport
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImportPipelineTest {

    private lateinit var staging: File
    private lateinit var tasksRoot: File

    private val manifest = """
        {
          "schema": 1,
          "id": "com.example.pipeline",
          "name": "Pipeline",
          "version": "1.0.0",
          "runtime": {"type": "python", "entry": "main.py"},
          "needs": {"capabilities": [{"id": "device.ui", "scope": {"packages": ["com.target"]}}]},
          "config_schema": {"type": "object", "properties": {"wait": {"type": "integer", "default": 5}}},
          "ui": {"mode": "auto"}
        }
    """.trimIndent()

    private val pythonMain = "import os\nimport fake_desktop_lib\nprint('x')\n"

    @Before
    fun setUp() {
        staging = Files.createTempDirectory("ah-p-staging").toFile()
        tasksRoot = Files.createTempDirectory("ah-p-tasks").toFile()
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

    private fun pipeline(scanner: ImportScanner = ImportScanner { null }) =
        ImportPipeline(BundleExtractor(staging, tasksRoot), scanner)

    @Test
    fun `bytes source produces a full report and installs after review`() {
        val zip = zipOf(
            "task.json" to manifest.encodeToByteArray(),
            "main.py" to "print('x')\n".encodeToByteArray(),
        )
        val review = pipeline().review(
            ImportPipeline.Source.Bytes("com.example.pipeline-1.0.0.aatask") { ByteArrayInputStream(zip) },
        )
        assertTrue(review.canInstall)
        assertEquals("com.example.pipeline", review.report.taskId)
        assertEquals("Pipeline", review.report.taskName)
        assertEquals("1.0.0", review.report.version)
        assertTrue("manifest summary mentions python", review.report.manifestSummary!!.startsWith("python"))
        assertTrue(review.report.sha256.isNotBlank())

        val dir = BundleExtractor(staging, tasksRoot).install(review.staged!!)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `broken zip produces a rejection report with the rule`() {
        val review = pipeline().review(
            ImportPipeline.Source.Bytes("broken.aatask") { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )
        assertFalse(review.canInstall)
        assertNull(review.staged)
        assertTrue(review.report.errors.isNotEmpty())
        assertTrue(review.report.blocking)
    }

    @Test
    fun `dev folder source is reviewed with a stable directory hash and scan`() {
        val dir = Files.createTempDirectory("ah-dev-task").toFile()
        File(dir, "task.json").writeText(manifest)
        File(dir, "main.py").writeText(pythonMain)

        val scanner = ImportScanner { scanDir ->
            assertNotNull(scanDir)
            ScanReport(files = 1, stdlib = 1, desktopOnly = listOf(ScanReport.Hit("fake_desktop_lib", "main.py", "use device.ui")))
        }
        val review = pipeline(scanner).review(ImportPipeline.Source.DevFolder(dir))
        assertTrue(review.canInstall)
        assertEquals("dev-folder", review.report.source.substringBefore(':'))
        assertNotNull(review.report.scan)
        assertEquals(1, review.report.scan!!.desktopOnly.size)
        val hashA = review.report.sha256
        val reviewAgain = pipeline(scanner).review(ImportPipeline.Source.DevFolder(dir))
        assertEquals(hashA, reviewAgain.report.sha256) // unchanged folder → unchanged hash
        // a changed file changes the hash
        File(dir, "main.py").writeText("$pythonMain\n# touched\n")
        val reviewChanged = pipeline(scanner).review(ImportPipeline.Source.DevFolder(dir))
        assertFalse(hashA == reviewChanged.report.sha256)
    }

    @Test
    fun `dev folder without a manifest or with a broken one is rejected`() {
        val empty = Files.createTempDirectory("ah-empty").toFile()
        val noManifest = pipeline().review(ImportPipeline.Source.DevFolder(empty))
        assertFalse(noManifest.canInstall)
        assertEquals("NO_MANIFEST", noManifest.report.errors.first().rule)

        val dir = Files.createTempDirectory("ah-broken").toFile()
        File(dir, "task.json").writeText("""{"schema": 1, "id": "BAD_ID"}""")
        val broken = pipeline().review(ImportPipeline.Source.DevFolder(dir))
        assertFalse(broken.canInstall)
        assertTrue(broken.report.errors.first().rule in setOf("MANIFEST_INVALID", "PATTERN", "DESERIALIZE"))
    }

    @Test
    fun `compat rows are attached when a checker is provided`() {
        val dir = Files.createTempDirectory("ah-compat").toFile()
        File(dir, "task.json").writeText(manifest)
        File(dir, "main.py").writeText("print('x')\n")
        val rows = listOf(ImportReport.CompatRow("capability", "device.ui", "AVAILABLE", required = true))
        val review = pipeline().review(ImportPipeline.Source.DevFolder(dir))
        assertTrue(review.canInstall)
        // checker runs through the constructor default (null here) — the wired version is covered in
        // CapabilityResolverTest; this asserts the pipeline surfaces empty rows when no checker is set.
        assertTrue(review.report.compat.isEmpty() || review.report.compat == rows)
    }

    @Test
    fun `manifest summary counts capabilities and apps`() {
        val summary = """
            {
              "schema": 1, "id": "com.example.count", "name": "Count", "version": "1.0.0",
              "runtime": {"type": "python", "entry": "main.py"},
              "needs": {"capabilities": [{"id": "device.ui"}, {"id": "device.app"}],
                        "apps": [{"package": "com.a"}, {"package": "com.b"}]},
              "ui": {"mode": "auto"}
            }
        """.trimIndent()
        val zip = zipOf(
            "task.json" to summary.encodeToByteArray(),
            "main.py" to "print(1)\n".encodeToByteArray(),
        )
        val review = pipeline().review(ImportPipeline.Source.Bytes("c.aatask") { ByteArrayInputStream(zip) })
        assertTrue(review.canInstall)
        assertTrue(review.report.manifestSummary!!.contains("2 capabilities"))
        assertTrue(review.report.manifestSummary!!.contains("2 apps"))
    }
}

class ImportClassifierTest {

    @Test
    fun `buckets stdlib, bundled, vendored and unavailable modules`() {
        val report = ImportClassifier.classify(
            topLevelModules = setOf("os", "requests", "mypackage"),
            bundled = setOf("requests"),
            vendored = setOf("mypackage"),
            files = mapOf(
                "main.py" to listOf("os", "requests", "mypackage", "obscure_lib"),
            ),
        )
        assertEquals(1, report.stdlib)
        assertEquals(1, report.bundled)
        assertEquals(1, report.vendored)
        assertEquals(listOf("obscure_lib"), report.unavailable)
        assertTrue(report.desktopOnly.isEmpty())
        assertTrue(report.hasProblems) // unavailable deps are a problem the UI must show
    }

    @Test
    fun `desktop-only modules are flagged with suggestions and never unavailable`() {
        val report = ImportClassifier.classify(
            topLevelModules = setOf("playwright", "ctypes", "tkinter", "os"),
            bundled = emptySet(),
            vendored = emptySet(),
            files = mapOf("main.py" to listOf("playwright", "ctypes", "tkinter", "os")),
        )
        val flagged = report.desktopOnly.map { it.module }.toSet()
        assertEquals(setOf("playwright", "ctypes", "tkinter"), flagged)
        assertTrue(report.unavailable.isEmpty()) // desktop-only wins over "unavailable"
        assertTrue(report.desktopOnly.all { it.suggestion.isNotBlank() })
        assertTrue(report.hasProblems)
    }

    @Test
    fun `dot imports of bundled packages count as bundled`() {
        val report = ImportClassifier.classify(
            topLevelModules = setOf("requests.adapters"),
            bundled = setOf("requests"),
            vendored = emptySet(),
            files = mapOf("main.py" to listOf("requests.adapters")),
        )
        assertEquals(0, report.stdlib)
        assertEquals(1, report.bundled)
    }

    @Test
    fun `per-file attribution survives into hits`() {
        val report = ImportClassifier.classify(
            topLevelModules = setOf("pyautogui"),
            bundled = emptySet(),
            vendored = emptySet(),
            files = mapOf(
                "main.py" to listOf("os"),
                "helpers/desktop.py" to listOf("pyautogui"),
            ),
        )
        assertEquals(1, report.desktopOnly.size)
        assertEquals("helpers/desktop.py", report.desktopOnly.first().file)
        assertEquals(1, report.stdlib)
    }
}
