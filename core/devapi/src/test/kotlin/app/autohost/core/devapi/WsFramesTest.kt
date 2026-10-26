package app.autohost.core.devapi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P11-07 (ADR-081): the RFC 6455 frame codec — the accept vector from the RFC, round-trips in
 * both roles, every length form, and the mask-expectation honesty rules.
 */
class WsFramesTest {

    @Test
    fun acceptValueMatchesTheRfc6455Example() {
        // RFC 6455 §1.3: key "dGhlIHNhbXBsZSBub25jZQ==" → accept "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsFrames.accept("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun clientFramesRoundTripMasked() {
        for (text in listOf("{}", "system.ping", "x".repeat(125), "y".repeat(126), "z".repeat(65_600))) {
            val encoded = WsFrames.encodeText(text, mask = true)
            val decoded = WsFrames.decode(encoded, expectMasked = true)
            assertTrue(decoded is WsFrames.Decode.Frame)
            assertEquals(WsFrames.OP_TEXT, (decoded as WsFrames.Decode.Frame).frame.opcode)
            assertEquals(text, decoded.frame.text)
        }
    }

    @Test
    fun serverFramesRoundTripUnmasked() {
        val response = """{"jsonrpc":"2.0","id":1,"result":{"status":"ok"}}"""
        val encoded = WsFrames.encodeText(response, mask = false)
        val decoded = WsFrames.decode(encoded, expectMasked = false)
        assertTrue(decoded is WsFrames.Decode.Frame)
        assertEquals(response, (decoded as WsFrames.Decode.Frame).frame.text)
    }

    @Test
    fun the64BitLengthFormWorks() {
        val text = "a".repeat(70_000)
        val encoded = WsFrames.encodeText(text, mask = true)
        assertEquals(70_000, encoded.size - 14) // 2-byte header + 8-byte extended length + 4-byte mask
        val decoded = WsFrames.decode(encoded, expectMasked = true)
        assertTrue(decoded is WsFrames.Decode.Frame)
        assertEquals(text, (decoded as WsFrames.Decode.Frame).frame.text)
    }

    @Test
    fun maskExpectationIsEnforcedBothWays() {
        val masked = WsFrames.encodeText("hi", mask = true)
        val unmasked = WsFrames.encodeText("hi", mask = false)
        assertTrue(WsFrames.decode(masked, expectMasked = false) is WsFrames.Decode.Invalid)
        assertTrue(WsFrames.decode(unmasked, expectMasked = true) is WsFrames.Decode.Invalid)
        assertTrue(WsFrames.decode(byteArrayOf(0x81.toByte(), 0x02), expectMasked = true) is WsFrames.Decode.NeedMore)
    }

    @Test
    fun closeAndPingOpcodesSurvive() {
        val close = WsFrames.encode(WsFrames.OP_CLOSE, ByteArray(0), mask = true)
        val decodedClose = WsFrames.decode(close, expectMasked = true)
        assertTrue(decodedClose is WsFrames.Decode.Frame && decodedClose.frame.opcode == WsFrames.OP_CLOSE)
        val ping = WsFrames.encode(WsFrames.OP_PING, "hb".encodeToByteArray(), mask = false)
        val decodedPing = WsFrames.decode(ping, expectMasked = false)
        assertTrue(decodedPing is WsFrames.Decode.Frame)
        assertArrayEquals("hb".encodeToByteArray(), (decodedPing as WsFrames.Decode.Frame).frame.payload)
    }
}
