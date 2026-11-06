package app.autohost

import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * X-22 (CRASH_FIX_PLAN Appendix A2; owner decision D-23): the FileProvider scope test. The
 * current `file_paths.xml` carries `<files-path name="files" path="." />` — which covers
 * **everything** in `files/`, including the ADB private key directory (`files/adb/`). The owner's
 * D-23 decision narrows the paths; THIS test is pre-written so the narrowing is verified the
 * moment it lands: un-ignore it then, and it fails while the broad path remains.
 *
 * Written 2026-11-06 (rework 2 W2) — `@Ignore`d because the narrowing has not happened yet (D-23);
 * the test is the failing-test-first marker, not a dead check.
 */
class FileProviderScopeTest {

    @Ignore("X-22 RED — un-ignore when the D-23 decision narrows file_paths.xml; it fails while " +
        "<files-path path=\".\" /> still covers files/adb/")
    @Test
    fun `the FileProvider paths never cover the ADB private key directory`() {
        val xml = File("src/main/res/xml/file_paths.xml").readText()
        for (m in Regex("<(files|external-files|cache|external-cache|external)-path[^>]*path=\"([^\"]+)\"")
            .findAll(xml)
        ) {
            val covered = m.groupValues[2].replace("\\", "/").trimEnd('/')
            assertTrue(
                "FileProvider path '${m.groupValues[2]}' covers files/adb/ — narrow it (X-22, D-23)",
                !(covered == "." || covered.isEmpty() || "adb".startsWith(covered.substringAfterLast('/'))),
            )
        }
    }
}
