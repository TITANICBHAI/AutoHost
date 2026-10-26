package app.autohost.runtime.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * P11-01 (written; awaiting Gradle): entry + module resolution — explicit entries must exist,
 * the blank-entry fallback, source caps, and the containment rules of the module loader
 * (`./lib/…` only, traversal refused, `lib/` file-count cap).
 */
class JsSourcesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun taskDir(): File = tmp.newFolder("task")

    @Test
    fun `blank entry resolves to main js when present`() {
        val dir = taskDir()
        File(dir, "main.js").writeText("export function main() {}")
        val file = JsSources.resolveEntryFile(dir, null)
        assertNotNull(file)
        assertEquals("main.js", file!!.name)
    }

    @Test
    fun `explicit entry wins and must exist`() {
        val dir = taskDir()
        File(dir, "main.js").writeText("a")
        assertEquals("entry.js", JsSources.resolveEntryFile(dir, "entry.js")!!.name)
        assertNull("missing explicit entry must not fall back", JsSources.resolveEntryFile(dir, "nope.js"))
        assertNull("missing default must be null too", JsSources.resolveEntryFile(tmp.newFolder("empty"), null))
    }

    @Test
    fun `read source enforces the size cap`() {
        val dir = taskDir()
        val big = File(dir, "big.js")
        big.writeText("x".repeat(JsSources.MAX_FILE_BYTES + 1))
        try {
            JsSources.readSource(big)
            fail("expected JsSourceException")
        } catch (e: JsSourceException) {
            assertTrue(e.message!!.contains("cap"))
        }
        File(dir, "ok.js").writeText("export const ok = true")
        assertEquals("export const ok = true", JsSources.readSource(File(dir, "ok.js")))
    }

    @Test
    fun `module loader serves files under lib`() {
        val dir = taskDir()
        File(dir, "lib").mkdir()
        File(dir, "lib/helper.js").writeText("export const h = 1")
        val loader = JsSources.moduleLoaderFor(dir)!!
        assertEquals("export const h = 1", loader("./lib/helper.js"))
        assertNull("bare relative names (no ./ prefix) are refused", loader("lib/helper.js"))
    }

    @Test
    fun `module loader refuses traversal absolutes and non-lib paths`() {
        val dir = taskDir()
        File(dir, "lib").mkdir()
        File(dir, "lib/helper.js").writeText("x")
        File(dir, "secret.txt").writeText("s")
        val outside = tmp.newFolder("outside")
        File(outside, "evil.js").writeText("e")
        val loader = JsSources.moduleLoaderFor(dir)!!
        assertNull("non-lib task file refused", loader("./secret.txt"))
        assertNull("absolute name refused", loader("/etc/passwd"))
        assertNull("traversal refused", loader("./../../outside/evil.js"))
        assertNull("missing file null", loader("./lib/nope.js"))
        assertNull("blank null", loader(""))
    }

    @Test
    fun `module loader refuses when the lib dir exceeds the file cap`() {
        val dir = taskDir()
        val lib = File(dir, "lib").apply { mkdir() }
        repeat(JsSources.MAX_LIB_FILES + 1) { File(lib, "m$it.js").writeText("x") }
        val loader = JsSources.moduleLoaderFor(dir)!!
        assertNull("over-capped lib dir refuses everything", loader("./lib/m0.js"))
    }

    private fun fail(message: String): Nothing = org.junit.Assert.fail(message)
}
