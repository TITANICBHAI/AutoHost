package app.autohost.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiGateTest {
    @Test
    fun `given no install when queried then sdk int is zero and everything is unavailable`() {
        ApiGate.reset()
        assertEquals(0, ApiGate.sdkInt)
        assertFalse(ApiGate.atLeast(29))
        assertTrue(ApiGate.below(29))
        assertFalse(ApiGate.isInstalled)
    }

    @Test
    fun `given installed value when queried then at least and below agree`() {
        ApiGate.install(30)
        try {
            assertEquals(30, ApiGate.sdkInt)
            assertTrue(ApiGate.atLeast(29))
            assertTrue(ApiGate.atLeast(30))
            assertFalse(ApiGate.atLeast(31))
            assertTrue(ApiGate.below(34))
            assertFalse(ApiGate.below(30))
        } finally {
            ApiGate.reset()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given zero when install then fails closed`() {
        ApiGate.install(0)
    }
}
