package app.autohost.runtime.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P13-10 (AGENT_RUNTIME §7.4, RM-C2): the stuck detector — the RM-G2 port of ClosePaw's
 * `LoopDetectionPolicyTest` rewritten to AutoHost's state-aware fingerprints, plus the
 * ADR-091 (12) hard stops (warn at 3 same fingerprints, stop at 5, or 3 identical actions).
 */
class StuckDetectorTest {

    private fun fingerprint(label: String) = """Button "btn" "$label" {clickable} (0,0,100,50)"""

    @Test
    fun `given differing screens when observed then no warning (the upstream port)`() {
        val detector = StuckDetector()
        repeat(5) { index ->
            val verdict = detector.observe(fingerprint("Screen-$index"))
            assertEquals(StuckDetector.Verdict.None, verdict)
        }
    }

    @Test
    fun `given the full window of identical screens when observed then the factual warning names the count`() {
        val detector = StuckDetector()
        var verdict: StuckDetector.Verdict = StuckDetector.Verdict.None
        repeat(5) {
            verdict = detector.observe(fingerprint("Stuck"))
        }
        // turns 3 AND 4 and 5 warn (3 ≥ warnAfter); at 5 the verdict is the hard stop
        assertTrue(verdict is StuckDetector.Verdict.Stop)
        assertEquals("stuck: the screen has not changed for 5 steps", (verdict as StuckDetector.Verdict.Stop).message)
    }

    @Test
    fun `given three identical screens when observed then a factual warning, not a stop (ADR-091 12)`() {
        val detector = StuckDetector()
        detector.observe(fingerprint("A"))
        detector.observe(fingerprint("A"))
        val third = detector.observe(fingerprint("A"))
        assertTrue(third is StuckDetector.Verdict.Warn)
        assertEquals("the screen has not changed for 3 steps", (third as StuckDetector.Verdict.Warn).message)
    }

    @Test
    fun `given the run breaks before the window fills when observed then no warning (the upstream port)`() {
        val detector = StuckDetector()
        detector.observe(fingerprint("Stuck"))
        detector.observe(fingerprint("Stuck"))
        detector.observe(fingerprint("Different")) // the run breaks at 2 — never warns
        assertEquals(StuckDetector.Verdict.None, detector.observe(fingerprint("Stuck")))
    }

    @Test
    fun `given near-identical screens below the 0_95 threshold when observed then no warning (the upstream port)`() {
        // 40 shared lines + 1 varying line: Jaccard = 40/42 ≈ 0.952 ≥ 0.95 → SAME.
        // 3 shared + 1 varying: 3/5 = 0.6 < 0.95 → NOT the same screen.
        val detector = StuckDetector()
        val shared = (0 until 3).joinToString("\n") { "Text \"s$it\" \"Shared $it\" {} (0,$it,10,${it + 5})" }
        repeat(5) { index ->
            val fp = shared + "\n" + "Text \"v\" \"Item $index\" {} (0,99,10,104)"
            assertEquals(StuckDetector.Verdict.None, detector.observe(fp))
        }
    }

    @Test
    fun `given near-identical screens above the threshold when observed then they count as the same screen`() {
        val detector = StuckDetector()
        val shared = (0 until 40).joinToString("\n") { "Text \"s$it\" \"Shared $it\" {} (0,$it,10,${it + 5})" }
        var verdict: StuckDetector.Verdict = StuckDetector.Verdict.None
        repeat(3) { index ->
            verdict = detector.observe(shared + "\n" + "Text \"v\" \"clock $index\" {} (0,999,10,1004)")
        }
        assertTrue("40 shared + 1 varying ≈ 0.952 must count as the same screen", verdict is StuckDetector.Verdict.Warn)
    }

    @Test
    fun `given an empty history when observed then no warning (the upstream port)`() {
        // (the detector has no "empty observe" call — the equivalent: one observation is never a run)
        val detector = StuckDetector()
        assertEquals(StuckDetector.Verdict.None, detector.observe(fingerprint("Single")))
    }

    @Test
    fun `given two empty fingerprints when compared then jaccard is 0 (never a false same)`() {
        assertEquals(0.0, StuckDetector.jaccard(emptySet(), emptySet()), 1e-9)
    }

    @Test
    fun `given three identical actions when recorded then the run stops even though the screen changed`() {
        val detector = StuckDetector()
        val args = """{"ref":"[1]"}"""
        detector.recordAction("ui_click", args, "[1]")
        detector.recordAction("ui_click", args, "[1]")
        val third = detector.recordAction("ui_click", args, "[1]")
        assertTrue(third is StuckDetector.Verdict.Stop)
        assertTrue((third as StuckDetector.Verdict.Stop).message.contains("repeated 3 times"))
    }

    @Test
    fun `given actions that differ in target when recorded then no stop (the upstream lesson - repetition alone is not stuck)`() {
        val detector = StuckDetector()
        for (n in 1..3) {
            assertEquals(StuckDetector.Verdict.None, detector.recordAction("ui_scroll_until", """{"n":"$n"}""", null))
        }
    }

    @Test
    fun `given the same action with a changed fingerprint when recorded then the action stop still fires (checked state is state-aware)`() {
        // A toggle flicking on/off changes the screen — but 3 identical clicks are still stuck.
        val detector = StuckDetector()
        val args = """{"ref":"[1]"}"""
        assertEquals(StuckDetector.Verdict.None, detector.observe(fingerprint("on")))
        assertEquals(StuckDetector.Verdict.None, detector.recordAction("ui_click", args, "[1]"))
        assertEquals(StuckDetector.Verdict.None, detector.observe(fingerprint("off")))
        assertEquals(StuckDetector.Verdict.None, detector.recordAction("ui_click", args, "[1]"))
        assertEquals(StuckDetector.Verdict.None, detector.observe(fingerprint("on")))
        val third = detector.recordAction("ui_click", args, "[1]")
        assertTrue(third is StuckDetector.Verdict.Stop)
    }
}
