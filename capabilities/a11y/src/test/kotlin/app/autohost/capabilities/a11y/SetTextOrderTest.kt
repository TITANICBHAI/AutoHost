package app.autohost.capabilities.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P12-05 (A1/RM-A1): the set_text verify ORDER — perform → **refresh()** → read. The recording
 * fake fails the test unless the calls arrive in exactly that order; a stale-reading handle (the
 * A1 defect: read without refresh) fails verification and would have sent the chain through
 * IME/paste → doubled text.
 */
class SetTextOrderTest {

    /** Recording fake: readText returns the STALE text until refresh() is called. */
    private class RecordingHandle(
        private val staleText: String,
        private val freshText: String,
        private val performResult: Boolean = true,
    ) : NodeActions.SetTextHandle {
        val order = mutableListOf<String>()

        override fun performSetText(newText: String): Boolean {
            order.add("perform")
            return performResult
        }

        override fun refresh(): Boolean {
            order.add("refresh")
            return true
        }

        override fun readText(): String {
            order.add("read")
            return if (order.contains("refresh")) freshText else staleText
        }
    }

    @Test
    fun `given a working handle when set then the order is perform refresh read and verification passes`() {
        val handle = RecordingHandle(staleText = "", freshText = "hello")
        val verified = NodeActions.setTextVia(handle, "hello")
        assertTrue(verified)
        assertEquals(listOf("perform", "refresh", "read"), handle.order)
    }

    @Test
    fun `given a handle that reads WITHOUT refreshing when set then verification fails (the A1 stale read)`() {
        // The A1 defect shape: readText returns the PRE-SET content because the node was never
        // refreshed. The old implementation did exactly this.
        val stale = object : NodeActions.SetTextHandle {
            val order = mutableListOf<String>()
            override fun performSetText(newText: String): Boolean {
                order.add("perform"); return true
            }
            override fun refresh(): Boolean {
                order.add("refresh"); return false // refresh refused (OEM quirk shape)
            }
            override fun readText(): String {
                order.add("read"); return "" // the field still reads empty
            }
        }
        val verified = NodeActions.setTextVia(stale, "hello")
        // the honest outcome: NOT verified — the provider reports UNVERIFIED, never falls through
        // to IME/paste on a false rejection (the doubled-text guard rides the truthful verify).
        assertFalse(verified)
        assertEquals(listOf("perform", "refresh", "read"), stale.order)
    }

    @Test
    fun `given a refused perform when set then no refresh or read happens (fail fast)`() {
        val handle = RecordingHandle(staleText = "", freshText = "x", performResult = false)
        val verified = NodeActions.setTextVia(handle, "hello")
        assertFalse(verified)
        assertEquals(listOf("perform"), handle.order)
    }

    @Test
    fun `given a refresh refusal but a real text change when set then verification still compares the read`() {
        // Some OEM nodes return false from refresh() yet the field DID update — the read decides.
        val handle = object : NodeActions.SetTextHandle {
            override fun performSetText(newText: String): Boolean = true
            override fun refresh(): Boolean = false
            override fun readText(): String = "hello"
        }
        assertTrue(NodeActions.setTextVia(handle, "hello"))
    }
}
