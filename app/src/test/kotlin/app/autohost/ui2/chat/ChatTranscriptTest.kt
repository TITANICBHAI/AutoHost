package app.autohost.ui2.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTranscriptTest {

    private fun entry(n: Int) = ChatEntry("run-$n", "goal $n", n * 1000L)

    @Test fun `a transcript survives being written and read`() {
        val original = ChatTranscript(
            entries = listOf(entry(1), ChatEntry("run-2", "say \"hi\" \\ and é \n twice", 5L)),
            packages = listOf("com.example.a", "com.example.b"),
            profileId = "p1",
        )
        assertEquals(original, ChatTranscript.parse(original.toJson()))
    }

    @Test fun `nothing saved, blank text and damaged text all read as an empty chat`() {
        val empty = ChatTranscript()
        assertEquals(empty, ChatTranscript.parse(null))
        assertEquals(empty, ChatTranscript.parse(""))
        assertEquals(empty, ChatTranscript.parse("   "))
        assertEquals(empty, ChatTranscript.parse("{not json"))
        assertEquals(empty, ChatTranscript.parse("[1,2,3]"))
        assertEquals(empty, ChatTranscript.parse("\"text\""))
    }

    @Test fun `an entry without a run id is skipped and the rest are kept`() {
        val text = """{"v":1,"entries":[{"goal":"no id"},{"run":"","goal":"blank id"},{"run":"r1","goal":"ok","at":7},5]}"""
        val parsed = ChatTranscript.parse(text)
        assertEquals(listOf(ChatEntry("r1", "ok", 7L)), parsed.entries)
    }

    @Test fun `a missing time reads as zero and a missing goal as empty`() {
        val parsed = ChatTranscript.parse("""{"entries":[{"run":"r1"}]}""")
        assertEquals(listOf(ChatEntry("r1", "", 0L)), parsed.entries)
        assertNull(parsed.profileId)
    }

    @Test fun `only the newest fifty goals are kept`() {
        var t = ChatTranscript()
        for (n in 1..(ChatTranscript.MAX_ENTRIES + 7)) t = t.withEntry(entry(n))
        assertEquals(ChatTranscript.MAX_ENTRIES, t.entries.size)
        assertEquals("run-8", t.entries.first().runId)
        assertEquals("run-${ChatTranscript.MAX_ENTRIES + 7}", t.entries.last().runId)
        assertEquals(ChatTranscript.MAX_ENTRIES, ChatTranscript.parse(t.toJson()).entries.size)
    }

    @Test fun `adding the same run again replaces it instead of listing it twice`() {
        val t = ChatTranscript().withEntry(entry(1)).withEntry(entry(2)).withEntry(ChatEntry("run-1", "edited", 9L))
        assertEquals(listOf("run-2", "run-1"), t.entries.map { it.runId })
        assertEquals("edited", t.entries.last().goal)
    }

    @Test fun `setup drops blank and repeated apps and clearing keeps the setup`() {
        val t = ChatTranscript().withEntry(entry(1)).withSetup(listOf("a", "", "a", "b"), "p2")
        assertEquals(listOf("a", "b"), t.packages)
        assertEquals("p2", t.profileId)
        val cleared = t.cleared()
        assertTrue(cleared.entries.isEmpty())
        assertEquals(listOf("a", "b"), cleared.packages)
        assertEquals("p2", cleared.profileId)
    }
}
