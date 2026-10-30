package app.autohost.capabilities.shell

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbKeyFilesCorruptFileTest {

    @Test
    fun `given corrupt key file when load then a fresh key is generated and persisted`() {
        val dir = Files.createTempDirectory("adbkeys").toFile()
        try {
            val keyFile = File(dir, "adbkey.pk8.b64")
            keyFile.writeText("this is not base64 pkcs8 !!!")

            val first = AdbKeyFiles(dir, "test@host").load()
            val second = AdbKeyFiles(dir, "test@host").load()

            assertEquals(first.fingerprint(), second.fingerprint())
            assertTrue(keyFile.readText().isNotBlank())
            assertTrue(keyFile.readText() != "this is not base64 pkcs8 !!!")
        } finally {
            dir.deleteRecursively()
        }
    }
}
