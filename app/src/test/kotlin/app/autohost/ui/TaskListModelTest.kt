package app.autohost.ui

import app.autohost.core.data.db.RunEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskListModelTest {

    private fun entry(id: String, name: String) = TaskEntry(id, name, TaskEntry.Source.INSTALLED, null, null)

    private fun run(id: String, task: String, status: String, started: Long, ended: Long? = null) = RunEntity(
        runId = id, taskId = task, profileId = null, trigger = "manual", status = status, startedAt = started, endedAt = ended,
    )

    @Test fun `a task with no runs is ready with no last result`() {
        val row = TaskListModel.rows(listOf(entry("a", "Alpha")), emptyList()).single()
        assertEquals(TaskStatus.READY, row.status)
        assertEquals(LastResult.NONE, row.lastResult)
        assertNull(row.lastRunAt)
    }

    @Test fun `an active run makes the task running`() {
        for (active in listOf("QUEUED", "STARTING", "RUNNING", "PAUSED", "WAITING_USER")) {
            val row = TaskListModel.rows(listOf(entry("a", "Alpha")), listOf(run("r1", "a", active, 10))).single()
            assertEquals(active, TaskStatus.RUNNING, row.status)
        }
    }

    @Test fun `the last result comes from the newest finished run`() {
        val runs = listOf(
            run("r1", "a", "FAILED", 10, 20),
            run("r2", "a", "SUCCEEDED", 30, 40),
            run("r3", "a", "FAILED", 5, 8),
        )
        val row = TaskListModel.rows(listOf(entry("a", "Alpha")), runs).single()
        assertEquals(LastResult.SUCCEEDED, row.lastResult)
        assertEquals(40L, row.lastRunAt)
        assertEquals(TaskStatus.READY, row.status)
    }

    @Test fun `an active run does not hide the previous finished result`() {
        val runs = listOf(run("r1", "a", "SUCCEEDED", 10, 20), run("r2", "a", "RUNNING", 30))
        val row = TaskListModel.rows(listOf(entry("a", "Alpha")), runs).single()
        assertEquals(TaskStatus.RUNNING, row.status)
        assertEquals(LastResult.SUCCEEDED, row.lastResult)
    }

    @Test fun `interrupted counts as failed and cancelled as stopped`() {
        val interrupted = TaskListModel.rows(listOf(entry("a", "A")), listOf(run("r", "a", "INTERRUPTED", 1, 2))).single()
        val cancelled = TaskListModel.rows(listOf(entry("a", "A")), listOf(run("r", "a", "CANCELLED", 1, 2))).single()
        assertEquals(LastResult.FAILED, interrupted.lastResult)
        assertEquals(LastResult.STOPPED, cancelled.lastResult)
    }

    @Test fun `runs of other tasks and unknown statuses are ignored`() {
        val runs = listOf(run("r1", "b", "RUNNING", 1), run("r2", "a", "SOMETHING_NEW", 2, 3))
        val row = TaskListModel.rows(listOf(entry("a", "Alpha")), runs).single()
        assertEquals(TaskStatus.READY, row.status)
        assertEquals(LastResult.NONE, row.lastResult)
    }

    @Test fun `search matches the name ignoring case and a blank query keeps everything`() {
        val rows = TaskListModel.rows(listOf(entry("x1", "Morning Routine"), entry("x2", "HTTP check")), emptyList())
        assertEquals(listOf("x1"), TaskListModel.search(rows, "  rout ").map { it.entry.taskId })
        assertEquals(2, TaskListModel.search(rows, "").size)
        assertEquals(0, TaskListModel.search(rows, "x1").size) // ids are not searched
    }
    @Test fun `age picks the coarsest unit and never goes negative`() {
        val now = 10_000_000L
        assertEquals(TaskListModel.AgeUnit.NOW to 0L, TaskListModel.age(now, now - 59_000))
        assertEquals(TaskListModel.AgeUnit.NOW to 0L, TaskListModel.age(now, now + 5_000))
        assertEquals(TaskListModel.AgeUnit.MINUTES to 5L, TaskListModel.age(now, now - 5 * 60_000))
        assertEquals(TaskListModel.AgeUnit.HOURS to 3L, TaskListModel.age(now, now - 3 * 3_600_000))
        assertEquals(TaskListModel.AgeUnit.DAYS to 2L, TaskListModel.age(now, now - 49 * 3_600_000))
    }
}
