package app.autohost.core.common

import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AhResultTest {
    @Test
    fun `given ok result when mapped then value transforms`() = runTest {
        val result = AhResult.ok(2).map { it * 3 }
        assertEquals(6, result.getOrNull())
    }

    @Test
    fun `given err result when mapped then error survives`() {
        val result: AhResult<Int> = AhResult.err(ErrorCode.NOT_FOUND, "nothing here", reason = "NO_MATCH")
        val mapped = result.map { it * 3 }
        assertNull(mapped.getOrNull())
        assertEquals(ErrorCode.NOT_FOUND, mapped.errorOrNull()?.let { ErrorCode.valueOf(it.code) })
        assertEquals("NO_MATCH", mapped.errorOrNull()?.reason)
        assertFalse(result.isOk)
    }

    @Test
    fun `given err when flat mapped then second call skipped`() {
        var called = false
        val result: AhResult<Int> = AhResult.err(ErrorCode.TIMEOUT, "slow")
            .flatMap { called = true; AhResult.ok(it) }
        assertFalse(called)
        assertTrue(result.errorOrNull()?.retryable == true)
    }

    @Test
    fun `given ok when get or throw then returns value`() {
        assertEquals("x", AhResult.ok("x").getOrThrow())
    }

    @Test
    fun `given error code retryability then matches errors json contract`() {
        assertTrue(ErrorCode.TIMEOUT.retryable)
        assertTrue(ErrorCode.STALE_NODE.retryable)
        assertFalse(ErrorCode.PERMISSION_DENIED.retryable)
    }

    @Test
    fun `given json parse then shapes validated`() {
        val ok = AhJson.parseObject("""{"a":1}""")
        assertTrue(ok.isOk)
        val notObject = AhJson.parseObject("[1,2]")
        assertTrue(notObject.errorOrNull()?.code == ErrorCode.INVALID_ARGS.name)
        val malformed = AhJson.parseObject("{nope")
        assertFalse(malformed.isOk)
        assertEquals(buildJsonObject { put("x", JsonPrimitive(1)) }, AhJson.json.parseToJsonElement("""{"x":1}"""))
    }
}
