package app.autohost.ui2

import app.autohost.core.data.db.RunEntity
import app.autohost.core.model.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayModelTest {

    private fun run(id: String, task: String, status: String, started: Long, ended: Long? = null, stage: String? = null) =
        RunEntity(runId = id, taskId = task, profileId = null, trigger = "manual", status = status, startedAt = started, endedAt = ended, currentStage = stage)

    private val names = mapOf("a" to "Alpha", "b" to "")

    @Test fun `waiting runs are separated from running ones and come first`() {
        val m = TodayModel.build(
            listOf(run("1", "a", "RUNNING", 10, stage = "login"), run("2", "a", "WAITING_USER", 20), run("3", "a", "PAUSED", 5)),
            names,
        )
        assertEquals(listOf("2"), m.needsYou.map { it.runId })
        assertEquals(listOf("1", "3"), m.active.map { it.runId })
        assertEquals("login", m.active.first().stage)
        assertTrue(m.recent.isEmpty())
    }

    @Test fun `recent holds only finished runs, newest first, limited`() {
        val runs = (1..8).map { run("r$it", "a", "SUCCEEDED", it * 10L, it * 10L + 1) } + run("live", "a", "RUNNING", 99)
        val m = TodayModel.build(runs, names, recentLimit = 3)
        assertEquals(listOf("r8", "r7", "r6"), m.recent.map { it.runId })
        assertEquals(listOf("live"), m.active.map { it.runId })
    }

    @Test fun `a task without a usable name shows its id and unknown statuses are dropped`() {
        val m = TodayModel.build(listOf(run("1", "b", "FAILED", 1, 2), run("2", "zzz", "WEIRD", 3, 4), run("3", "zzz", "CANCELLED", 5, 6)), names)
        assertEquals(setOf("b", "zzz"), m.recent.map { it.taskName }.toSet())
        assertEquals(2, m.recent.size)
        assertEquals(RunStatus.CANCELLED, m.recent.first().status)
    }

    @Test fun `no runs means empty`() {
        assertTrue(TodayModel.build(emptyList(), names).isEmpty)
    }
}
