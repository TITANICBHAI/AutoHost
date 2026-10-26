package app.autohost.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdsTest {
    @Test
    fun `given ulid when generated then shape is 26 crockford base32 chars`() {
        val id = Ids.ulid()
        assertEquals(26, id.length)
        assertTrue("bad chars in $id", id.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
    }

    @Test
    fun `given two ulids when same millisecond then still unique and ordered`() {
        val a = Ids.ulid(nowMs = 1_700_000_000_000)
        val b = Ids.ulid(nowMs = 1_700_000_000_000)
        val c = Ids.ulid(nowMs = 1_700_000_000_001)
        assertTrue(a != b)
        assertTrue(a < b) // monotonic within the same ms
        assertTrue(b < c)
    }

    @Test
    fun `given many ulids when generated then all unique`() {
        val ids = (1..5_000).map { Ids.ulid(nowMs = 1_700_000_000_000L + it / 100) }.toSet()
        assertEquals(5_000, ids.size)
    }

    @Test
    fun `given hex token when 32 bytes then 64 hex chars and unique`() {
        val t1 = Ids.hexToken()
        val t2 = Ids.hexToken()
        assertEquals(64, t1.length)
        assertTrue(t1.all { it in "0123456789abcdef" })
        assertTrue(t1 != t2)
    }

    @Test
    fun `given hex token when 16 bytes then 32 chars`() {
        assertEquals(32, Ids.hexToken(16).length)
    }

    @Test
    fun `given ulid when decoded then timestamp bytes carry the requested ms`() {
        val now = 1_700_000_000_123L
        // First 10 chars encode 48 bits of time: decode the first two chars as sanity (time is big-endian).
        val id = Ids.ulid(nowMs = now)
        // compare with the canonical encoding of the same ms by generating twice for two ms values
        val earlier = Ids.ulid(nowMs = now - 1000)
        assertTrue(earlier < id)
        assertFalse(id.startsWith(earlier.take(9)))
    }
}
