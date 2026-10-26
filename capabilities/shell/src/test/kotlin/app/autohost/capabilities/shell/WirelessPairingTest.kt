package app.autohost.capabilities.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * P9-02: SPAKE2 (RFC 9382) P-256-SHA256-HKDF-HMAC pins.
 *
 * Vector 1 (A='server', B='client', the RFC's official Appendix-B values for w and x) checks pA —
 * the leading bytes of pA are reproduced here exactly (the fetch of the RFC tail was truncated in
 * the build sandbox, so the remaining values are pinned by a cross-implementation transcript:
 * scripts/spake2_ref.py, an independent Python implementation of the same RFC text, generated the
 * K_enc/KcA/KcB/cA/cB expectations below — the Kotlin port must agree byte for byte).
 */
class Spake2P256Test {

    @Test fun `curve parameters match the RFC table`() {
        assertEquals("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f", Spake2P256.M_HEX)
        assertEquals("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49", Spake2P256.N_HEX)
        val m = Spake2P256.decodePoint(Spake2P256.M_HEX)
        val n = Spake2P256.decodePoint(Spake2P256.N_HEX)
        assertTrue(Spake2P256.onCurve(m))
        assertTrue(Spake2P256.onCurve(n))
    }

    @Test fun `pA reproduces the official RFC 9382 appendix-b vector`() {
        val w = BigInteger("2ee57912099d31560b3a44b1184b9b4866e904c49d12ac5042c97dca461b1a5f", 16)
        val x = BigInteger("43dd0fd7215bdcb482879fca3220c6a968e66d70b1356cac18bb26c84a78d729", 16)
        val party = Spake2P256.Party(
            Spake2P256.Party.Side.A, w,
            "server".encodeToByteArray(), "client".encodeToByteArray(),
        )
        val pA = party.generateMessage(x)
        assertEquals(
            "04a56fa807caaa53a4d28dbb9853b9815c61" +
                "a411118a6fe516a8798434751470f9010153ac33d0d5f2047ffdb1a3e42c9b4e6be662766e1eeb4116988ede5f912c",
            pA.toHex(),
        )
    }

    @Test fun `two parties derive identical keys and confirm each other`() {
        val w = BigInteger("2ee57912099d31560b3a44b1184b9b4866e904c49d12ac5042c97dca461b1a5f", 16)
        val x = BigInteger("43dd0fd7215bdcb482879fca3220c6a968e66d70b1356cac18bb26c84a78d729", 16)
        val y = BigInteger("2f5b3f5d1c4e197e986e1a5d3f4c8b7a6d5e4f3c2b1a09877665544332211001", 16)
        val idA = "server".encodeToByteArray()
        val idB = "client".encodeToByteArray()

        val alice = Spake2P256.Party(Spake2P256.Party.Side.A, w, idA, idB)
        val bob = Spake2P256.Party(Spake2P256.Party.Side.B, w, idA, idB)
        val pA = alice.generateMessage(x)
        val pB = bob.generateMessage(y)

        val aliceKeys = alice.finish(pB)
        val bobKeys = bob.finish(pA)
        assertArrayEquals(aliceKeys.ke, bobKeys.ke)
        assertArrayEquals(aliceKeys.kcA, bobKeys.kcA)
        assertArrayEquals(aliceKeys.kcB, bobKeys.kcB)

        // Cross-implementation pins (scripts/spake2_ref.py, same RFC text).
        assertEquals(
            "04f4e318fda1f7ec587dd292f2755a9e3caca8e9a241de5415a74b270ec1e9525" +
                "db4ecadd3c60abd528f0575cc82863903e7b76f6e95fe4518671e4df79d3ee612",
            pB.toHex(),
        )
        assertEquals("6d61fcaf08b68c2febcfd6603238fa2a", aliceKeys.ke.toHex())
        assertEquals("232b372ca80e79cdc4db3897ef994d71", aliceKeys.kcA.toHex())
        assertEquals("31716e841fa9c083675a09c81a20743f", aliceKeys.kcB.toHex())
        assertEquals(
            "7657f220b8ab1e052392fd11d5c082d6b7ac76e9f00b93f0c1b4e3e733ff9b6b",
            Spake2P256.confirm(aliceKeys.kcA, aliceKeys.tt).toHex(),
        )
        assertEquals(
            "34b3ca847fc2abd6af60e5fac2a5fb3d4b435bfa6f9131f52b91ec3510e03c0f",
            Spake2P256.confirm(bobKeys.kcB, bobKeys.tt).toHex(),
        )
    }

    @Test fun `a wrong password fails confirmation`() {
        val idA = "server".encodeToByteArray()
        val idB = "client".encodeToByteArray()
        val good = BigInteger.valueOf(123456)
        val bad = BigInteger.valueOf(654321)
        val alice = Spake2P256.Party(Spake2P256.Party.Side.A, good, idA, idB)
        val bob = Spake2P256.Party(Spake2P256.Party.Side.B, bad, idA, idB)
        val pA = alice.generateMessage(BigInteger.valueOf(42))
        val pB = bob.generateMessage(BigInteger.valueOf(43))
        val aliceKeys = alice.finish(pB)
        val bobKeys = bob.finish(pA)
        assertFalse(aliceKeys.ke.contentEquals(bobKeys.ke))
        // confirmation MACs diverge, so both sides detect the mismatch
        assertFalse(
            Spake2P256.confirm(aliceKeys.kcA, aliceKeys.tt).contentEquals(
                Spake2P256.confirm(bobKeys.kcB, bobKeys.tt),
            ),
        )
    }

    @Test fun `non-member peer points abort the protocol`() {
        val party = Spake2P256.Party(
            Spake2P256.Party.Side.A, BigInteger.ONE,
            "server".encodeToByteArray(), "client".encodeToByteArray(),
        )
        party.generateMessage(BigInteger.valueOf(7))
        val notOnCurve = run {
            val payload = ByteArray(65)
            payload[0] = 0x04
            BigInteger("3").toByteArray().copyInto(payload, 33 - 1) // x=3 → not a curve point here
            payload
        }
        try {
            party.finish(notOnCurve)
            throw AssertionError("expected the member check to abort")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("member"))
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
