package app.autohost.core.engine.surfaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 7 part 2 surface policies: tile bindings (P7-07), the RUN_TASK contract (P7-10), the bubble policy (P7-06). */
class SurfacesPoliciesTest {

    // --- TileBindings (P7-07) ------------------------------------------------------------------------------------

    @Test fun `tile binding round trip keeps task profile and stage`() {
        val json = TileBindings.format(TileBindings.Binding("com.example.task", "prof-1", "login"))
        val parsed = TileBindings.parse(2, json)
        assertEquals(TileBindings.Binding("com.example.task", "prof-1", "login"), parsed.binding)
        assertNull(parsed.error)
    }

    @Test fun `tile binding without extras omits them`() {
        val parsed = TileBindings.parse(1, TileBindings.format(TileBindings.Binding("task_a")))
        assertEquals(TileBindings.Binding("task_a", null, null), parsed.binding)
    }

    @Test fun `tile binding rejects junk fail-closed`() {
        assertNull(TileBindings.parse(3, null).binding)
        assertNull(TileBindings.parse(3, "").binding)
        assertNull(TileBindings.parse(3, "not json").binding)
        assertNull(TileBindings.parse(3, "[1,2]").binding)
        assertNull(TileBindings.parse(3, "{}").binding)
        assertNull(TileBindings.parse(3, "{\"taskId\":\"bad id!\"}").binding)
        assertNull(TileBindings.parse(3, "{\"taskId\":\"ok\",\"stage\":\"bad stage\"}").binding)
    }

    @Test fun `tile pref keys cover exactly five slots`() {
        assertEquals("tile.1.binding", TileBindings.prefKey(1))
        assertEquals("tile.5.binding", TileBindings.prefKey(5))
    }

    // --- ExternalRunRequest (P7-10) ------------------------------------------------------------------------------

    private fun parse(
        token: String? = "tok",
        task: String? = "my_task",
        profile: String? = null,
        stage: String? = null,
        payload: String? = null,
        expected: String? = "tok",
    ) = ExternalRunRequest.parse(token, task, profile, stage, payload, expected)

    @Test fun `external run accepts a valid minimal request`() {
        val outcome = parse()
        assertTrue(outcome is ExternalRunRequest.Outcome.Accepted)
        outcome as ExternalRunRequest.Outcome.Accepted
        assertEquals("my_task", outcome.taskId)
        assertNull(outcome.profileId)
        assertNull(outcome.stage)
        assertEquals(0, outcome.payload.size)
    }

    @Test fun `external run accepts profile stage and payload`() {
        val outcome = parse(profile = "p1", stage = "s1", payload = "{\"k\":1}")
        outcome as ExternalRunRequest.Outcome.Accepted
        assertEquals("p1", outcome.profileId)
        assertEquals("s1", outcome.stage)
        assertEquals(1, outcome.payload.size)
    }

    @Test fun `external run rejects bad tokens`() {
        assertEquals("MISSING_TOKEN", (parse(token = null) as ExternalRunRequest.Outcome.Rejected).reason)
        assertEquals("BAD_TOKEN", (parse(token = "wrong") as ExternalRunRequest.Outcome.Rejected).reason)
        assertEquals("NO_TOKEN_CONFIGURED", (parse(expected = "") as ExternalRunRequest.Outcome.Rejected).reason)
    }

    @Test fun `external run rejects malformed fields`() {
        assertEquals("MISSING_TASK", (parse(task = null) as ExternalRunRequest.Outcome.Rejected).reason)
        assertEquals("BAD_TASK_ID", (parse(task = "bad task!") as ExternalRunRequest.Outcome.Rejected).reason)
        assertEquals("BAD_PROFILE_ID", (parse(profile = "p 1") as ExternalRunRequest.Outcome.Rejected).reason)
        assertEquals("BAD_STAGE", (parse(stage = "s/1") as ExternalRunRequest.Outcome.Rejected).reason)
        assertEquals("BAD_PAYLOAD", (parse(payload = "{not json") as ExternalRunRequest.Outcome.Rejected).reason)
    }

    @Test fun `token compare is constant time and correct`() {
        assertTrue(ExternalRunRequest.constantTimeEquals("abc", "abc"))
        assertFalse(ExternalRunRequest.constantTimeEquals("abc", "abd"))
        assertFalse(ExternalRunRequest.constantTimeEquals("abc", "abcd"))
    }

    // --- BubblePolicy (P7-06) ------------------------------------------------------------------------------------

    private fun run(id: String, active: Boolean = true, uiActing: Boolean = false) =
        BubblePolicy.BubbleRun(id, active, uiActing)

    @Test fun `bubble auto mode shows only for ui-acting runs`() {
        assertTrue(BubblePolicy.decide(BubblePolicy.MODE_AUTO, listOf(run("1", uiActing = true))))
        assertFalse(BubblePolicy.decide(BubblePolicy.MODE_AUTO, listOf(run("1", uiActing = false))))
        assertFalse(BubblePolicy.decide(BubblePolicy.MODE_AUTO, listOf(run("1", uiActing = true, active = false))))
        assertFalse(BubblePolicy.decide(BubblePolicy.MODE_AUTO, emptyList()))
    }

    @Test fun `bubble always and off modes are honest`() {
        assertTrue(BubblePolicy.decide(BubblePolicy.MODE_ALWAYS, listOf(run("1"))))
        assertFalse(BubblePolicy.decide(BubblePolicy.MODE_ALWAYS, listOf(run("1", active = false))))
        assertFalse(BubblePolicy.decide(BubblePolicy.MODE_OFF, listOf(run("1", uiActing = true))))
        // unknown mode falls back to auto (fail-closed toward the narrower behaviour)
        assertFalse(BubblePolicy.decide("weird", listOf(run("1", uiActing = false))))
    }
}
