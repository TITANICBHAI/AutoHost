package app.autohost.ui2

import app.autohost.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHubModelTest {

    private val sections = SettingsHubModel.sections(pythonInBuild = true, devFolder = "/sdcard/Android/data/app.autohost/files/dev")

    @Test fun `every section has rows and every row has a plain line`() {
        assertTrue(sections.isNotEmpty())
        sections.forEach { s ->
            assertTrue(s.title, s.rows.isNotEmpty())
            s.rows.forEach { assertTrue(it.title, it.title.isNotBlank() && it.line.isNotBlank()) }
        }
    }

    @Test fun `the features that used to hide are reachable from the hub`() {
        val targets = sections.flatMap { it.rows }.mapNotNull { (it.target as? HubTarget.Go)?.route }.toSet()
        listOf(Routes.AI_SETTINGS, Routes.ASK, Routes.MACRO, Routes.SCHEDULES, Routes.SURFACES, Routes.HEALTH, Routes.SETTINGS, Routes.RUNS_HISTORY, Routes.SETUP)
            .forEach { assertTrue(it.name, it in targets) }
        assertTrue(sections.flatMap { it.rows }.any { it.target == HubTarget.AddTask })
        assertTrue("the welcome can be replayed", sections.flatMap { it.rows }.any { it.target == HubTarget.Welcome })
    }

    @Test fun `python row tells the truth about the build`() {
        val withPy = SettingsHubModel.sections(true, null).flatMap { it.rows }.first { it.title == "Python scripts" }.line
        val without = SettingsHubModel.sections(false, null).flatMap { it.rows }.first { it.title == "Python scripts" }.line
        assertTrue(withPy.contains("runs Python"))
        assertTrue(without.contains("no Python"))
        assertFalse(withPy == without)
    }

    @Test fun `no row sends the person to a computer`() {
        sections.flatMap { it.rows }.forEach {
            val t = (it.title + " " + it.line).lowercase()
            assertFalse(it.title, t.contains(" adb") || t.contains("computer") || t.contains("usb"))
        }
    }

    @Test fun `the task folder row explains why a file is the better route`() {
        val folder = sections.flatMap { it.rows }.first { it.title == "Task folder" }
        assertEquals(null, folder.target)
        assertTrue(folder.line.contains(".zip"))
    }
}
