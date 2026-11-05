package app.autohost.capabilities.a11y

import app.autohost.core.model.Bounds
import app.autohost.core.model.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P12-11 (RM-A1): the identity guard — stale/relabelled nodes refuse, never a wrong-node click. */
class NodeIdentityGuardTest {

    private fun node(
        id: String = "com.x:id/btn", cls: String = "android.widget.Button",
        text: String = "Continue", bounds: Bounds = Bounds(10, 20, 200, 80),
    ) = UiNode(
        index = 0, parent = -1, depth = 0, pkg = "com.x", cls = cls, id = id,
        text = text, desc = "", bounds = bounds, flags = setOf("clickable"),
    )

    @Test
    fun `given the same node when verified then Match`() {
        assertEquals(NodeIdentityGuard.Verdict.Match, NodeIdentityGuard.verify(node(), node()))
    }

    @Test
    fun `given a missing current node when verified then Stale`() {
        assertTrue(NodeIdentityGuard.verify(node(), null) is NodeIdentityGuard.Verdict.Stale)
    }

    @Test
    fun `given a changed resource id when verified then Stale`() {
        val verdict = NodeIdentityGuard.verify(node(), node(id = "com.x:id/other"))
        assertTrue(verdict is NodeIdentityGuard.Verdict.Stale)
    }

    @Test
    fun `given a changed class when verified then Stale`() {
        assertTrue(
            NodeIdentityGuard.verify(node(), node(cls = "android.widget.TextView")) is
                NodeIdentityGuard.Verdict.Stale,
        )
    }

    @Test
    fun `given moved bounds when verified then Stale (the node the user saw is gone)`() {
        val verdict = NodeIdentityGuard.verify(node(), node(bounds = Bounds(500, 20, 700, 80)))
        assertTrue(verdict is NodeIdentityGuard.Verdict.Stale)
    }

    @Test
    fun `given a relabelled node when verified then Relabelled (still refuses — the label the user saw is gone)`() {
        val verdict = NodeIdentityGuard.verify(node(), node(text = "Cancel"))
        assertTrue(verdict is NodeIdentityGuard.Verdict.Relabelled)
        assertEquals("Continue", (verdict as NodeIdentityGuard.Verdict.Relabelled).from)
        assertEquals("Cancel", verdict.to)
    }
}
