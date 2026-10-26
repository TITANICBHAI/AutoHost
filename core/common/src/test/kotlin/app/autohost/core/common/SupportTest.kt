package app.autohost.core.common

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ForegroundTrackerTest {
    @Test
    fun `given update when read then latest foreground wins`() = runTest {
        ForegroundTracker.reset()
        ForegroundTracker.update("com.a", ".MainActivity", nowMs = 100)
        val fg = ForegroundTracker.state.first()
        assertEquals("com.a", fg?.pkg)
        assertEquals(".MainActivity", fg?.activity)
        assertEquals(100, fg?.tsMs)
    }

    @Test
    fun `given stale observation when is foreground then false after max age`() = runTest {
        ForegroundTracker.reset()
        ForegroundTracker.update("com.a", null, nowMs = 1_000)
        assertTrue(ForegroundTracker.isForeground("com.a", nowMs = 3_500))
        assertFalse(ForegroundTracker.isForeground("com.a", nowMs = 4_500))
        assertFalse(ForegroundTracker.isForeground("com.b", nowMs = 1_100))
    }

    @Test
    fun `given blank package when update then ignored`() = runTest {
        ForegroundTracker.reset()
        ForegroundTracker.update("", null, nowMs = 1)
        assertNull(ForegroundTracker.state.first())
    }

    @Test
    fun `given live source when read then mirrors tracker`() = runTest {
        ForegroundTracker.reset()
        ForegroundTracker.update("com.x", ".A", nowMs = 5)
        assertEquals("com.x", ForegroundSource.live.current()?.pkg)
    }
}

class ScopedTempTest {
    @Test
    fun `given scoped temp when closed then everything deleted`() {
        val root = File(System.getProperty("java.io.tmpdir"), "autohost-scoped-test-${System.nanoTime()}")
        val scope = ScopedTemp(root)
        val dir = scope.newDir("run")
        val file = File(dir, "dump.txt")
        file.writeText("hello")
        assertTrue(file.exists())
        scope.close()
        assertFalse(file.exists())
        assertFalse(dir.exists())
        assertFalse(root.exists())
    }

    @Test
    fun `given unsafe name when created then sanitized`() {
        val root = File(System.getProperty("java.io.tmpdir"), "autohost-scoped-test-${System.nanoTime()}")
        ScopedTemp(root).use { scope ->
            val dir = scope.newDir("a/b:c")
            assertTrue(dir.name.matches(Regex("a_b_c-\\d+")))
        }
    }
}
