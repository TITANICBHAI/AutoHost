package app.autohost.core.engine

import app.autohost.core.broker.AuditRecord
import app.autohost.core.common.FakeClock
import app.autohost.core.engine.orchestrator.IdleWatchdogPolicy
import app.autohost.core.engine.orchestrator.Incident
import app.autohost.core.engine.orchestrator.IncidentTracker
import app.autohost.core.engine.orchestrator.RetentionRules
import app.autohost.core.engine.orchestrator.RetentionPolicy
import app.autohost.core.engine.orchestrator.ResumePolicy
import app.autohost.core.engine.orchestrator.RunDirStat
import app.autohost.core.model.TaskManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * P5-05/P5-07/P5-08 pure policies: idle watchdog, auto-resume, incidents, retention (plan §12.5, §12.3, §13.1).
 */
class ReliabilityPoliciesTest {

    // ---- P5-07: idle watchdog --------------------------------------------------

    @Test
    fun `idle watchdog is quiet under the warn threshold`() {
        val verdict = IdleWatchdogPolicy.verdict(0L, 79_000L, 100_000L)
        assertTrue(verdict is IdleWatchdogPolicy.Verdict.None)
    }

    @Test
    fun `idle watchdog warns at 80 percent with the percent`() {
        val verdict = IdleWatchdogPolicy.verdict(0L, 85_000L, 100_000L)
        assertTrue(verdict is IdleWatchdogPolicy.Verdict.Warn)
        assertEquals(85, (verdict as IdleWatchdogPolicy.Verdict.Warn).consumedPercent)
    }

    @Test
    fun `idle watchdog expires at the limit`() {
        assertTrue(IdleWatchdogPolicy.verdict(0L, 100_000L, 100_000L) is IdleWatchdogPolicy.Verdict.Expire)
    }

    @Test
    fun `idle watchdog disabled with non-positive budget`() {
        assertTrue(IdleWatchdogPolicy.verdict(0L, 999_999L, 0L) is IdleWatchdogPolicy.Verdict.None)
    }

    // ---- P5-05: resume policy --------------------------------------------------

    @Test
    fun `resume policy starts attempt two by default`() {
        val decision = ResumePolicy.decide(TaskManifest.Execution(maxAttempts = 3), 1)
        assertEquals(ResumePolicy.Decision.Resume(2), decision)
    }

    @Test
    fun `resume policy stops when attempts are exhausted`() {
        val decision = ResumePolicy.decide(TaskManifest.Execution(maxAttempts = 2), 2)
        assertEquals(ResumePolicy.Decision.Stop("ATTEMPTS_EXHAUSTED"), decision)
    }

    @Test
    fun `resume policy honours auto_resume false`() {
        val decision = ResumePolicy.decide(TaskManifest.Execution(maxAttempts = 3, autoResume = false), 1)
        assertEquals(ResumePolicy.Decision.Stop("AUTO_RESUME_DISABLED"), decision)
    }

    @Test
    fun `resume policy treats maxAttempts below one as a single attempt`() {
        val decision = ResumePolicy.decide(TaskManifest.Execution(maxAttempts = 0), 1)
        assertTrue(decision is ResumePolicy.Decision.Stop)
    }

    // ---- P5-07: incidents --------------------------------------------------------

    @Test
    fun `failed call opens an incident and a later success in the same stage resolves it`() {
        val clock = FakeClock()
        val tracker = IncidentTracker(clock)
        val fail = AuditRecord(1, "run-1", "task", "device.ui", "click", ok = false, errorCode = "STALE_NODE", durationMs = 5)
        val pass = AuditRecord(2, "run-1", "task", "device.ui", "click", ok = true, errorCode = null, durationMs = 5)
        tracker.onAudit(fail, "setup")
        assertEquals(1, tracker.openOf("run-1").size)
        tracker.onAudit(pass, "setup")
        assertTrue(tracker.openOf("run-1").isEmpty())
        val incident = tracker.allOf("run-1").single()
        assertFalse(incident.open)
    }

    @Test
    fun `success in a different stage does not resolve the incident`() {
        val clock = FakeClock()
        val tracker = IncidentTracker(clock)
        tracker.onAudit(audit(ok = false), "setup")
        tracker.onAudit(audit(ok = true), "collect")
        assertEquals(1, tracker.openOf("run-1").size)
    }

    @Test
    fun `repeat failures update lastSeen instead of duplicating`() {
        val clock = FakeClock()
        val tracker = IncidentTracker(clock)
        tracker.onAudit(audit(ok = false), "setup")
        tracker.onAudit(audit(ok = false), "setup")
        assertEquals(1, tracker.allOf("run-1").size)
        assertEquals(2, tracker.allOf("run-1").single().lastSeenAtMs)
    }

    @Test
    fun `closeRun resolves everything as run-ended`() {
        val clock = FakeClock()
        val tracker = IncidentTracker(clock)
        tracker.onAudit(audit(ok = false), "setup")
        tracker.closeRun("run-1")
        assertTrue(tracker.openOf("run-1").isEmpty())
    }

    private fun audit(ok: Boolean, ts: Long = 1) =
        AuditRecord(ts, "run-1", "task", "device.ui", "click", ok = ok, errorCode = if (ok) null else "STALE_NODE", durationMs = 5)

    // ---- P5-08: retention ----------------------------------------------------------

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `retention deletes by age, then count, then bytes`() {
        val now = 1_000_000_000L
        val day = 24L * 60 * 60 * 1000
        val old = RunDirStat("/a", now - 20 * day, 10)
        val recent = listOf(
            RunDirStat("/b", now - 1 * day, 100),
            RunDirStat("/c", now - 2 * day, 100),
            RunDirStat("/d", now - 3 * day, 100),
        )
        val doomed = RetentionPolicy.selectForDeletion(
            listOf(old) + recent,
            RetentionRules(maxAgeDays = 14, maxRuns = 2, maxBytes = 0),
            now,
        )
        assertTrue("/a" in doomed) // too old
        assertTrue("/d" in doomed) // beyond maxRuns=2 (oldest survivor)
        assertFalse("/b" in doomed)
        assertFalse("/c" in doomed)
    }

    @Test
    fun `retention byte budget drops the oldest first`() {
        val now = 1_000_000_000L
        val entries = listOf(
            RunDirStat("/oldest", now - 3_000, 100),
            RunDirStat("/mid", now - 2_000, 100),
            RunDirStat("/new", now - 1_000, 100),
        )
        val doomed = RetentionPolicy.selectForDeletion(entries, RetentionRules(maxAgeDays = 365, maxRuns = 100, maxBytes = 150), now)
        assertEquals(setOf("/oldest"), doomed)
    }

    @Test
    fun `retention apply deletes real directories`() {
        val a = tmp.newFolder("a")
        File(a, "evidence").mkdirs()
        File(File(a, "evidence"), "shot.png").writeBytes(ByteArray(10))
        val b = tmp.newFolder("b")
        val stats = RetentionPolicy.stats(tmp.root)
        assertEquals(2, stats.size)
        val deleted = RetentionPolicy.apply(stats, setOf(a.absolutePath))
        assertEquals(1, deleted)
        assertFalse(File(a.absolutePath).exists())
        assertTrue(b.exists())
    }
}
