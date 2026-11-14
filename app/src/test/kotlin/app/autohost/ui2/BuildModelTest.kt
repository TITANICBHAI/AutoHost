package app.autohost.ui2

import app.autohost.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildModelTest {

    @Test fun `every way to make a task is on one list and the ids are unique`() {
        val items = BuildModel.items(pythonInBuild = true, labs = false)
        assertEquals(items.size, items.map { it.id }.toSet().size)
        val routes = items.mapNotNull { (it.target as? HubTarget.Go)?.route }
        assertTrue(Routes.MACRO in routes && Routes.TEMPLATES in routes)
        assertTrue(items.any { it.target == HubTarget.AddTask })
    }

    @Test fun `the studio only shows when labs is on`() {
        assertFalse(BuildModel.items(true, false).any { it.id == "studio" })
        assertTrue(BuildModel.items(true, true).any { it.id == "studio" })
    }

    @Test fun `the python card tells the truth about the build and never promises an editor`() {
        val with = BuildModel.items(true, false).first { it.id == "python" }
        val without = BuildModel.items(false, false).first { it.id == "python" }
        assertTrue(with.line.contains("runs Python"))
        assertTrue(without.line.contains("no Python"))
        assertEquals(null, with.target)
        assertFalse(with.line.lowercase().contains("coming") || with.line.lowercase().contains("soon"))
    }

    @Test fun `no card asks for a computer or shows an id`() {
        BuildModel.items(true, true).forEach {
            val t = (it.title + " " + it.line).lowercase()
            assertFalse(it.id, t.contains("adb") || t.contains("usb") || t.contains("computer") || t.contains("json"))
        }
    }
}
