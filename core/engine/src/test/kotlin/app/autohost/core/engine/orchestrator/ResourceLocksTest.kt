package app.autohost.core.engine.orchestrator

import app.autohost.core.common.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4-08: locks and queue (plan §11.4) — fake-clock driven; no sleeps.
 */
class ResourceLocksTest {

    private val clock = FakeClock(startMs = 1_000_000)

    private fun request(
        runId: String,
        uiActing: Boolean = true,
        packages: Set<String> = emptySet(),
        profileId: String? = null,
        priority: Int = 0,
        dueAtMs: Long = clock.nowMs(),
        lockTimeoutMs: Long = ResourceLocks.DEFAULT_LOCK_TIMEOUT_MS,
    ) = ResourceLocks.Request(
        runId = runId, taskId = "t", profileId = profileId, uiActing = uiActing,
        packages = packages, priority = priority, dueAtMs = dueAtMs, lockTimeoutMs = lockTimeoutMs,
    )

    @Test fun `global ui lock admits one ui run at a time`() = runBlocking {
        val locks = ResourceLocks(clock)
        val first = locks.acquire(request("r1"))
        assertTrue(first is ResourceLocks.Acquisition.Granted)
        val second = locks.acquire(request("r2"))
        assertEquals(0, (second as ResourceLocks.Acquisition.Queued).position)
        (first as ResourceLocks.Acquisition.Granted).handle.release()
        val third = locks.poll("r2")
        assertTrue(third is ResourceLocks.Acquisition.Granted)
        (third as ResourceLocks.Acquisition.Granted).handle.release()
        Unit
    }

    @Test fun `non-ui runs coexist with a ui run`() = runBlocking {
        val locks = ResourceLocks(clock)
        val ui = locks.acquire(request("ui")) as ResourceLocks.Acquisition.Granted
        val batch = locks.acquire(request("batch", uiActing = false))
        assertTrue(batch is ResourceLocks.Acquisition.Granted)
        ui.handle.release()
        (batch as ResourceLocks.Acquisition.Granted).handle.release()
        Unit
    }

    @Test fun `per package and per profile locks hold independently`() = runBlocking {
        val locks = ResourceLocks(clock)
        val a = locks.acquire(request("a", uiActing = false, packages = setOf("com.example"))) 
        assertTrue(a is ResourceLocks.Acquisition.Granted)
        val b = locks.acquire(request("b", uiActing = false, packages = setOf("com.example")))
        assertTrue(b is ResourceLocks.Acquisition.Queued)
        val c = locks.acquire(request("c", uiActing = false, profileId = "p1"))
        assertTrue(c is ResourceLocks.Acquisition.Granted)
        val d = locks.acquire(request("d", uiActing = false, profileId = "p1"))
        assertTrue(d is ResourceLocks.Acquisition.Queued)
        (a as ResourceLocks.Acquisition.Granted).handle.release()
        (c as ResourceLocks.Acquisition.Granted).handle.release()
        Unit
    }

    @Test fun `queue orders by priority then due time`() = runBlocking {
        val locks = ResourceLocks(clock)
        locks.acquire(request("holder"))
        locks.acquire(request("low", priority = 0, dueAtMs = clock.nowMs()))
        locks.acquire(request("urgent", priority = 10, dueAtMs = clock.nowMs() + 500))
        locks.acquire(request("mid", priority = 5, dueAtMs = clock.nowMs()))
        val snap = locks.snapshot()
        assertEquals(listOf("urgent", "mid", "low"), snap.queued.map { it.runId })
    }

    @Test fun `lock timeout skips honestly`() = runBlocking {
        val locks = ResourceLocks(clock)
        locks.acquire(request("holder"))
        // queued, then the clock passes the lock timeout
        val queued = locks.acquire(request("late", dueAtMs = clock.nowMs()))
        assertTrue(queued is ResourceLocks.Acquisition.Queued)
        clock.advance(ResourceLocks.DEFAULT_LOCK_TIMEOUT_MS + 1_000)
        val timedOut = locks.poll("late")
        assertEquals(ResourceLocks.Acquisition.Timeout, timedOut)
        // and the queue no longer holds the entry
        assertEquals(0, locks.snapshot().queued.size)
    }

    @Test fun `dequeue cancels a queued entry`() = runBlocking {
        val locks = ResourceLocks(clock)
        locks.acquire(request("holder"))
        locks.acquire(request("waiting"))
        locks.dequeue("waiting")
        assertEquals(0, locks.snapshot().queued.size)
        assertEquals(null, locks.poll("waiting"))
    }
}
