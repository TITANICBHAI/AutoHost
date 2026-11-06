package app.autohost.capabilities.a11y

import app.autohost.core.model.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P12-09 (A5/RM-A3): the hotspot promotion table (A5: the container-centre dead zone). */
class HotspotRefinerTest {

    private fun candidate(id: String, l: Int, t: Int, r: Int, b: Int) =
        HotspotRefiner.Candidate(id, Bounds(l, t, r, b))

    private val container = Bounds(0, 0, 1000, 400)

    @Test
    fun `given an off-centre actionable child when refined then promoted to the child`() {
        val child = candidate("btn", 700, 100, 950, 300)
        val result = HotspotRefiner.refine(container, listOf(child))
        assertEquals(HotspotRefiner.Result.Promoted(child), result)
    }

    @Test
    fun `given two children when refined then the smallest wins`() {
        val big = candidate("big", 500, 0, 1000, 400) // area 200_000
        val small = candidate("small", 750, 100, 950, 300) // area 40_000
        val result = HotspotRefiner.refine(container, listOf(big, small))
        assertEquals(HotspotRefiner.Result.Promoted(small), result)
    }

    @Test
    fun `given two equal-area minimal candidates when refined then Ambiguous (never a guess)`() {
        val a = candidate("a", 500, 0, 750, 400)
        val b = candidate("b", 750, 0, 1000, 400) // same area as a
        val result = HotspotRefiner.refine(container, listOf(a, b))
        assertTrue(result is HotspotRefiner.Result.Ambiguous)
        assertEquals(2, (result as HotspotRefiner.Result.Ambiguous).candidates.size)
    }

    @Test
    fun `given no actionable children when refined then Keep (click the target itself)`() {
        assertEquals(HotspotRefiner.Result.Keep, HotspotRefiner.refine(container, emptyList()))
    }

    @Test
    fun `given an explicit tap point no child contains when refined then Keep (the tap missed them all)`() {
        val child = candidate("btn", 700, 100, 950, 300)
        // R3 fix: the default-centre case contradicted T1/T2 (never-compiled inconsistency);
        // the tap-miss rule is exercised with an EXPLICIT tap on the other side of the screen
        assertEquals(
            HotspotRefiner.Result.Keep,
            HotspotRefiner.refine(container, listOf(child), tapX = 100, tapY = 200),
        )
    }

    @Test
    fun `given an explicit tap point inside a child when refined then that child is promoted`() {
        val child = candidate("btn", 700, 100, 950, 300)
        val result = HotspotRefiner.refine(container, listOf(child), tapX = 800, tapY = 200)
        assertEquals(HotspotRefiner.Result.Promoted(child), result)
    }

    @Test
    fun `given a child overlapping the container edge when refined then not promoted (must be strictly inside)`() {
        val overlapping = candidate("edge", -50, 0, 500, 400) // sticks out of the container
        assertEquals(HotspotRefiner.Result.Keep, HotspotRefiner.refine(container, listOf(overlapping)))
    }
}
