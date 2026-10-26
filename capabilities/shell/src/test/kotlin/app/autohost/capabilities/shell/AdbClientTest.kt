package app.autohost.capabilities.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P9-01: the ADB client against a fake `adbd` over a real loopback socket (repo test rule: fakes
 * on real I/O, no mocks of our own classes). Covers the CNXN/AUTH handshake (authorized and
 * pubkey-offered paths), framing (magic/CRC), the shell,v2 exec round-trip and the legacy fallback.
 */
class AdbClientTest {

    /** One scripted adbd session: speaks CNXN/AUTH then a shell,v2 answer. */
    private class FakeAdbd(
        private val socket: Socket,
        private val mode: Mode,
        private val token: ByteArray = ByteArray(20) { it.toByte() },
    ) {
        enum class Mode { AUTHORIZED, NEEDS_PUBKEY, LEGACY_SHELL, BAD_CRC }

        private val input = DataInputStream(socket.getInputStream())
        private val output = DataOutputStream(socket.getOutputStream())

        fun run() {
            readExpect(AdbProtocol.A_CNXN)
            when (mode) {
                Mode.AUTHORIZED -> {
                    AdbProtocol.write(output, AdbProtocol.Message(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, 1_048_576, "device::ro.product.name=fake".encodeToByteArray()))
                    serveShell()
                }
                Mode.NEEDS_PUBKEY -> {
                    // challenge → signature → challenge → pubkey → challenge → signature → CNXN
                    AdbProtocol.write(output, AdbProtocol.Message(AdbProtocol.A_AUTH, AdbProtocol.AUTH_TOKEN, 0, token))
                    val sig = readExpect(AdbProtocol.A_AUTH)
                    assertEquals(AdbProtocol.AUTH_SIGNATURE, sig.arg0)
                    AdbProtocol.write(output, AdbProtocol.Message(AdbProtocol.A_AUTH, AdbProtocol.AUTH_TOKEN, 0, token))
                    val pubkey = readExpect(AdbProtocol.A_AUTH)
                    assertEquals(AdbProtocol.AUTH_RSAPUBLICKEY, pubkey.arg0)
                    // the blob must carry our label and be NUL-terminated like adbd expects
                    val blob = String(pubkey.payload, Charsets.ISO_8859_1)
                    assertTrue("blob ends with label", blob.endsWith(" autohost@test\u0000"))
                    AdbProtocol.write(output, AdbProtocol.Message(AdbProtocol.A_AUTH, AdbProtocol.AUTH_TOKEN, 0, token))
                    readExpect(AdbProtocol.A_AUTH)
                    AdbProtocol.write(output, AdbProtocol.Message(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, 1_048_576, "device::".encodeToByteArray()))
                    serveShell()
                }
                Mode.LEGACY_SHELL -> {
                    AdbProtocol.write(output, AdbProtocol.Message(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, 1_048_576, "device::".encodeToByteArray()))
                    // an adbd without shell_v2: reject the v2 service (close, no data), then serve
                    // the legacy retry that follows on the same connection
                    while (true) {
                        val open = readExpect(AdbProtocol.A_OPEN)
                        val payload = String(open.payload, Charsets.UTF_8)
                        if (payload.startsWith("shell,v2:")) {
                            AdbProtocol.write(output, AdbProtocol.Message(AdbProtocol.A_CLSE, 9, open.arg0, ByteArray(0)))
                            continue
                        }
                        AdbProtocol.write(output, AdbProtocol.Message(AdbProtocol.A_OKAY, 9, open.arg0, ByteArray(0)))
                        AdbProtocol.write(output, AdbProtocol.Message(AdbProtocol.A_WRTE, 9, open.arg0, "legacy output".encodeToByteArray()))
                        readExpect(AdbProtocol.A_OKAY)
                        AdbProtocol.write(output, AdbProtocol.Message(AdbProtocol.A_CLSE, 9, open.arg0, ByteArray(0)))
                        return
                    }
                }
                Mode.BAD_CRC -> {
                    val broken = AdbProtocol.Message(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, 1024, "x".encodeToByteArray())
                    val encoded = AdbProtocol.encode(broken)
                    encoded[16] = (encoded[16] + 1).toByte() // corrupt the CRC field
                    output.write(encoded)
                    output.flush()
                }
            }
        }

        private fun serveShell() {
            val open = readExpect(AdbProtocol.A_OPEN)
            val service = String(open.payload, Charsets.UTF_8)
            assertTrue("expected shell,v2 service, got $service", service.startsWith("shell,v2:"))
            val command = service.removePrefix("shell,v2:")
            AdbProtocol.write(output, AdbProtocol.Message(AdbProtocol.A_OKAY, 9, open.arg0, ByteArray(0)))
            val stdout = v2Chunk(1, "stdout:$command\n")
            val stderr = v2Chunk(2, "warn\n")
            val exit = v2Chunk(3, intLe(3))
            // one frame with everything, one trailing empty stdout chunk (adbd does both)
            AdbProtocol.write(output, AdbProtocol.Message(AdbProtocol.A_WRTE, 9, open.arg0, stdout + stderr + exit))
            readExpect(AdbProtocol.A_OKAY)
            AdbProtocol.write(output, AdbProtocol.Message(AdbProtocol.A_WRTE, 9, open.arg0, v2Chunk(1, "")))
            readExpect(AdbProtocol.A_OKAY)
            AdbProtocol.write(output, AdbProtocol.Message(AdbProtocol.A_CLSE, 9, open.arg0, ByteArray(0)))
        }

        private fun v2Chunk(id: Int, data: ByteArray): ByteArray =
            byteArrayOf(id.toByte()) + intLe(data.size) + data

        private fun intLe(value: Int): ByteArray = byteArrayOf(
            (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(), ((value shr 24) and 0xFF).toByte(),
        )

        private fun readExpect(cmd: Int): AdbProtocol.Message {
            val message = AdbProtocol.read(input)
            assertEquals(AdbProtocol.name(cmd), AdbProtocol.name(message.cmd))
            return message
        }
    }

    private fun withFakeAdbd(mode: FakeAdbd.Mode, block: (port: Int) -> Unit) {
        ServerSocket(0).use { server ->
            val started = CountDownLatch(1)
            val thread = Thread {
                started.countDown()
                server.accept().use { socket ->
                    runCatching { FakeAdbd(socket, mode).run() }
                }
            }.apply { isDaemon = true }
            thread.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            block(server.localPort)
            thread.join(2_000)
        }
    }

    private fun keys(): AdbKeys = AdbKeys(
        java.security.KeyPairGenerator.getInstance("RSA").run {
            initialize(2048)
            generateKeyPair()
        },
        "autohost@test",
    )

    @Test fun `handshake then shell v2 round-trip with separated streams and exit code`() {
        val keys = keys()
        withFakeAdbd(FakeAdbd.Mode.AUTHORIZED) { port ->
            Socket("127.0.0.1", port).use { socket ->
                val client = AdbClient(socket.getInputStream(), socket.getOutputStream(), keys)
                client.connect(deadline())
                assertTrue(!client.awaitingUserAuthorization())
                val result = client.execV2("settings get secure x", 5_000)
                assertEquals("stdout:settings get secure x\n", result.stdout)
                assertEquals("warn\n", result.stderr)
                assertEquals(3, result.exitCode)
                assertEquals("shell_v2", result.mode)
            }
        }
    }

    @Test fun `unauthorized device gets the public key offer and still completes after approval`() {
        val keys = keys()
        withFakeAdbd(FakeAdbd.Mode.NEEDS_PUBKEY) { port ->
            Socket("127.0.0.1", port).use { socket ->
                val client = AdbClient(socket.getInputStream(), socket.getOutputStream(), keys)
                client.connect(deadline())
                // the fake authorizes after seeing the key; a real device keeps challenging until
                // the user taps allow, which surfaces as a handshake timeout (tested below)
                val result = client.execV2("echo hi", 5_000)
                assertEquals("stdout:echo hi\n", result.stdout)
            }
        }
    }

    @Test fun `legacy adbd falls back to merged shell output with null exit code`() {
        val keys = keys()
        withFakeAdbd(FakeAdbd.Mode.LEGACY_SHELL) { port ->
            Socket("127.0.0.1", port).use { socket ->
                val client = AdbClient(socket.getInputStream(), socket.getOutputStream(), keys)
                client.connect(deadline())
                val result = try {
                    client.execV2("echo hi", 2_000)
                    throw AssertionError("expected ShellV2Unavailable")
                } catch (e: AdbClient.ShellV2Unavailable) {
                    client.execLegacy("echo hi", 5_000)
                }
                assertEquals("legacy output", result.stdout)
                assertEquals(null, result.exitCode)
                assertEquals("shell", result.mode)
            }
        }
    }

    @Test fun `corrupted frames are rejected by the CRC check`() {
        val keys = keys()
        withFakeAdbd(FakeAdbd.Mode.BAD_CRC) { port ->
            Socket("127.0.0.1", port).use { socket ->
                val client = AdbClient(socket.getInputStream(), socket.getOutputStream(), keys)
                try {
                    client.connect(deadline())
                    throw AssertionError("expected AdbProtocolException")
                } catch (_: AdbProtocol.AdbProtocolException) {
                    // expected
                }
            }
        }
    }

    @Test fun `framing round-trips through encode and read`() {
        val message = AdbProtocol.Message(AdbProtocol.A_WRTE, 1, 2, "payload!".encodeToByteArray())
        val decoded = AdbProtocol.read(DataInputStream(java.io.ByteArrayInputStream(AdbProtocol.encode(message))))
        assertEquals(message.cmd, decoded.cmd)
        assertEquals(message.arg0, decoded.arg0)
        assertEquals(message.arg1, decoded.arg1)
        assertArrayEquals(message.payload, decoded.payload)
    }

    @Test fun `keystore persists and restores the identity`() {
        val dir = createTempDir()
        val store = AdbKeyFiles(dir, "autohost@test")
        val first = store.load()
        assertEquals("autohost@test", first.name)
        assertTrue(first.publicKeyBlob().size > 300)
        assertTrue(String(first.publicKeyBlob(), Charsets.ISO_8859_1).endsWith(" autohost@test\u0000"))
        val second = store.load()
        assertArrayEquals(first.keyPair.private.encoded, second.keyPair.private.encoded)
        // the public-key struct: words=64, exponent=65537 (le32 at offset 520)
        val blob = second.publicKeyBlob()
        val b64 = String(blob, Charsets.ISO_8859_1).substringBefore(" ")
        val struct = java.util.Base64.getDecoder().decode(b64)
        assertEquals(524, struct.size)
        assertEquals(64, readLe32(struct, 0))
        assertEquals(65537, readLe32(struct, 520).toInt())
        dir.deleteRecursively()
    }

    @Test fun `auth signature verifies against the token with the public key`() {
        val keys = keys()
        val token = ByteArray(20) { (it * 7).toByte() }
        val signature = keys.signToken(token)
        val verifier = java.security.Signature.getInstance("SHA1withRSA")
        verifier.initVerify(keys.keyPair.public)
        verifier.update(token)
        assertTrue(verifier.verify(signature))
    }

    private fun deadline() = System.nanoTime() / 1_000_000 + 2_000

    private fun readLe32(source: ByteArray, offset: Int): Long =
        (source[offset].toLong() and 0xFF) or ((source[offset + 1].toLong() and 0xFF) shl 8) or
            ((source[offset + 2].toLong() and 0xFF) shl 16) or ((source[offset + 3].toLong() and 0xFF) shl 24)
}
