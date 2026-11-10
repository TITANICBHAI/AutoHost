package app.autohost.ui

import app.autohost.DevTask
import app.autohost.core.data.db.TaskEntity
import app.autohost.core.model.RuntimeSpec
import app.autohost.core.model.TaskManifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class TaskCatalogTest {

    private fun installed(id: String, name: String) =
        TaskEntity(taskId = id, name = name, activeVersion = "1.0.0", source = "template", updatedAt = 1L)

    private fun dev(id: String, name: String) = DevTask(
        taskId = id,
        name = name,
        manifest = TaskManifest(schema = 1, id = id, name = name, runtime = RuntimeSpec(type = "python", entry = "main.py")),
        dir = File("/dev/$id"),
        config = JsonObject(emptyMap()),
    )

    @Test fun `installed and dev-folder tasks both appear`() {
        val list = TaskCatalog.merge(listOf(installed("a.one", "One")), listOf(dev("b.two", "Two")))
        assertEquals(listOf("a.one", "b.two"), list.map { it.taskId })
        assertEquals(TaskEntry.Source.INSTALLED, list[0].source)
        assertEquals(TaskEntry.Source.DEV_FOLDER, list[1].source)
        assertEquals("python", list[1].runtime)
    }

    @Test fun `a task that is in both sources appears once and the dev folder wins`() {
        val list = TaskCatalog.merge(listOf(installed("a.one", "One (installed)")), listOf(dev("a.one", "One (dev)")))
        assertEquals(1, list.size)
        assertEquals("One (dev)", list.single().name)
        assertNotNull(list.single().dev)
    }

    @Test fun `installed tasks carry no dev data`() {
        assertNull(TaskCatalog.merge(listOf(installed("a.one", "One")), emptyList()).single().dev)
    }

    @Test fun `the list is sorted by name ignoring case`() {
        val list = TaskCatalog.merge(listOf(installed("x", "banana"), installed("y", "Apple")), listOf(dev("z", "cherry")))
        assertEquals(listOf("Apple", "banana", "cherry"), list.map { it.name })
    }

    @Test fun `an empty catalog is an empty list`() {
        assertEquals(emptyList<TaskEntry>(), TaskCatalog.merge(emptyList(), emptyList()))
    }

    @Test fun `a new task appears without a refresh`() = runBlocking {
        val installedFlow = MutableStateFlow(emptyList<TaskEntity>())
        val devFlow = MutableStateFlow(emptyList<DevTask>())
        val catalog = TaskCatalog.observe(installedFlow, devFlow)
        assertEquals(0, catalog.first().size)
        installedFlow.value = listOf(installed("a.one", "One"))
        assertEquals(listOf("a.one"), catalog.first().map { it.taskId })
        devFlow.value = listOf(dev("b.two", "Two"))
        assertEquals(listOf("a.one", "b.two"), catalog.first().map { it.taskId })
    }
}
