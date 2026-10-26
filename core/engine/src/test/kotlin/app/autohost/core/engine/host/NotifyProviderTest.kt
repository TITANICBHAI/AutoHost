package app.autohost.core.engine.host

import app.autohost.core.broker.CallContext
import app.autohost.core.model.Envelope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P7-05: notify.post provider — validation of actions/progress, sink passthrough. */
class NotifyProviderTest {

    private class RecordingSink : NotificationSink {
        val posted = mutableListOf<NotificationRequest>()
        var nextId = 41

        override fun post(request: NotificationRequest): Int {
            posted.add(request)
            return nextId++
        }
    }

    private fun ctx(taskId: String = "com.example.t") =
        CallContext(runId = "r1", taskId = taskId, profileId = null, grants = emptyMap(), nowMs = 0L)

    private fun invoke(p: NotifyProvider, args: kotlinx.serialization.json.JsonObject): Envelope =
        runBlocking { p.invoke(ctx(), "post", args) }

    @Test
    fun posts_plain_notification() {
        val sink = RecordingSink()
        val envelope = invoke(NotifyProvider(sink), buildJsonObject {
            put("title", "Service check failed")
            put("text", "HTTP 500")
        })
        assertEquals(41, ((envelope.result as kotlinx.serialization.json.JsonObject)["id"] as JsonPrimitive).content.toInt())
        assertEquals(1, sink.posted.size)
        assertEquals("com.example.t", sink.posted[0].taskId)
        assertTrue(sink.posted[0].actions.isEmpty())
        assertEquals(null, sink.posted[0].progress)
    }

    @Test
    fun posts_actions_with_args() {
        val sink = RecordingSink()
        val envelope = invoke(NotifyProvider(sink), buildJsonObject {
            put("title", "Done")
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("label", "Undo")
                    put("handler", "undo")
                    put("args", buildJsonObject { put("n", 1) })
                })
            })
        })
        assertTrue(envelope.ok)
        val actions = sink.posted[0].actions
        assertEquals(1, actions.size)
        assertEquals("Undo", actions[0].label)
        assertEquals("undo", actions[0].handler)
        assertEquals(1, (actions[0].args["n"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun rejects_bad_actions_and_progress() {
        val sink = RecordingSink()
        val p = NotifyProvider(sink)
        fun bad(args: kotlinx.serialization.json.JsonObject): Boolean = !invoke(p, args).ok

        assertTrue(bad(buildJsonObject {
            put("title", "t")
            put("actions", buildJsonArray { add(buildJsonObject { put("label", "A") }) })
        }))
        assertTrue(bad(buildJsonObject {
            put("title", "t")
            put("actions", buildJsonArray {
                add(buildJsonObject { put("label", "A"); put("handler", "h") })
                add(buildJsonObject { put("label", "B"); put("handler", "h") })
                add(buildJsonObject { put("label", "C"); put("handler", "h") })
                add(buildJsonObject { put("label", "D"); put("handler", "h") })
            })
        }))
        assertTrue(bad(buildJsonObject { put("title", "t"); put("progress", 1.5) }))
        assertTrue(bad(buildJsonObject { put("title", ""); put("text", "") }))
        assertTrue(bad(buildJsonObject {
            put("title", "t")
            put("actions", buildJsonArray { add(JsonPrimitive("not-an-object")) })
        }))
        assertFalse(
            runBlocking { p.invoke(ctx(), "nope", buildJsonObject { put("title", "t") }) }.ok,
        )
        assertEquals(0, sink.posted.size) // nothing invalid ever reached the sink
    }
}
