package app.autohost.core.engine.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P15-06: the adaptive worker decision (placeholder thresholds, pinned contract). */
class WorkerPoolPlanTest {

    @Test
    fun `given plenty of ram when planned then the full pool`() {
        assertEquals(4, WorkerPoolPlan.workersFor(6_000))
        assertEquals(4, WorkerPoolPlan.workersFor(WorkerPoolPlan.HIGH_RAM_MB))
    }

    @Test
    fun `given mid ram when planned then three workers`() {
        assertEquals(3, WorkerPoolPlan.workersFor(WorkerPoolPlan.MID_RAM_MB))
        assertEquals(3, WorkerPoolPlan.workersFor(2_500))
    }

    @Test
    fun `given low ram when planned then two workers and never fewer`() {
        assertEquals(2, WorkerPoolPlan.workersFor(1_000))
        assertEquals(2, WorkerPoolPlan.workersFor(0))
    }

    @Test
    fun `given unknown ram when planned then fail open to the full pool`() {
        assertEquals(4, WorkerPoolPlan.workersFor(-1))
    }

    @Test
    fun `given any input when planned then the result stays within the manifest bounds`() {
        for (ram in intArrayOf(-5, 0, 100, 2_000, 2_999, 3_000, 12_000)) {
            val workers = WorkerPoolPlan.workersFor(ram)
            assertTrue(workers in WorkerPoolPlan.MIN_WORKERS..WorkerPoolPlan.MAX_WORKERS)
        }
    }
}
