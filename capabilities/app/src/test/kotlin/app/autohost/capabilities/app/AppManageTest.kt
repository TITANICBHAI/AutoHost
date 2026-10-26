package app.autohost.capabilities.app

import app.autohost.capabilities.shell.ShellResult
import app.autohost.capabilities.shell.ShellTransport
import app.autohost.capabilities.shell.ShellTransports
import app.autohost.capabilities.shell.TransportStatus
import app.autohost.core.broker.CallContext
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P9-09: the device.app.manage capability — pure policy verdicts and the provider gates/chain
 * over a fake shell transport.
 */
class AppManageTest {

    private class FakeTransport(
        private var state: TransportStatus = TransportStatus.Live,
        private val exitCode: Int? = 0,
        private val stdout: String = "Success",
        private val stderr: String = "",
    ) : ShellTransport {
        val commands = mutableListOf<String>()

        override val transportId: String = "fake"
        override fun describe(): String = "fake"
        override fun status(): TransportStatus = state
        override fun invalidateStatus() {}
        override fun exec(command: String, timeoutMs: Long): ShellResult {
            commands.add(command)
            return ShellResult(stdout, stderr, exitCode, "shell_v2")
        }
    }

    private fun ctx(grant: Boolean = true) = CallContext(
        runId = "r1",
        taskId = "t1",
        profileId = null,
        grants = if (grant) mapOf("device.app.manage" to JsonObject(emptyMap())) else emptyMap(),
        nowMs = 0L,
    )

    // --- policy ---------------------------------------------------------------------------------------------

    @Test fun `policy verdicts pin names paths and confirms`() {
        assertEquals(null, AppManagePolicy.packageVerdict("com.example.app"))
        assertTrue(AppManagePolicy.packageVerdict("com example")?.contains("unexpected") == true)
        assertEquals(null, AppManagePolicy.permissionVerdict("android.permission.CAMERA"))
        assertTrue(AppManagePolicy.permissionVerdict("CAMERA")?.contains("fully-qualified") == true)
        assertEquals(null, AppManagePolicy.apkPathVerdict("/data/local/tmp/app.apk"))
        assertTrue(AppManagePolicy.apkPathVerdict("/data/local/tmp/../etc.apk")?.contains("'..'") == true)
        assertTrue(AppManagePolicy.apkPathVerdict("/data/local/tmp/app.bin")?.contains(".apk") == true)
        assertEquals(AppManagePolicy.CONFIRM_DETAIL, AppManagePolicy.confirmVerdict(false))
        assertEquals(null, AppManagePolicy.confirmVerdict(true))
    }

    // --- provider -------------------------------------------------------------------------------------------

    @Test fun `force stop is verified through the shell exit code`() = runBlocking {
        val transport = FakeTransport(exitCode = 0)
        val provider = AppManageProvider(transports = ShellTransports(listOf(transport)))
        val envelope = provider.invoke(ctx(), "force_stop", buildJsonObject { put("package", JsonPrimitive("com.example.app")) })
        val payload = envelope.result!!.jsonObject
        assertEquals("VERIFIED", payload["result"]!!.jsonPrimitive.content)
        assertEquals("shell", payload["method"]!!.jsonPrimitive.content)
        assertEquals("am force-stop com.example.app", transport.commands[0])
    }

    @Test fun `force stop falls back to kill background when no transport is live`() = runBlocking {
        val provider = AppManageProvider(
            transports = ShellTransports(listOf(FakeTransport(state = TransportStatus.NeedsSetup("SHELL_TIER_NOT_PAIRED")))),
            killer = { true },
        )
        val envelope = provider.invoke(ctx(), "force_stop", buildJsonObject { put("package", JsonPrimitive("com.example.app")) })
        val payload = envelope.result!!.jsonObject
        assertEquals("BEST_EFFORT", payload["result"]!!.jsonPrimitive.content)
        assertEquals("kill_background", payload["method"]!!.jsonPrimitive.content)
        assertFalse(payload["verified"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun `missing grant is permission denied and the switch is upstream`() = runBlocking {
        val provider = AppManageProvider(transports = ShellTransports(listOf(FakeTransport())))
        val envelope = provider.invoke(ctx(grant = false), "force_stop", buildJsonObject { put("package", JsonPrimitive("com.example.app")) })
        assertEquals(ErrorCode.PERMISSION_DENIED, envelope.errorOrNull()?.codeAsEnum())
    }

    @Test fun `destructive methods demand confirm`() = runBlocking {
        val transport = FakeTransport()
        val provider = AppManageProvider(transports = ShellTransports(listOf(transport)))
        for (method in listOf("clear_state", "uninstall")) {
            val envelope = provider.invoke(ctx(), method, buildJsonObject { put("package", JsonPrimitive("com.example.app")) })
            assertEquals(ErrorCode.SCOPE_VIOLATION, envelope.errorOrNull()?.codeAsEnum())
        }
        val install = provider.invoke(ctx(), "install", buildJsonObject { put("path", JsonPrimitive("/data/local/tmp/a.apk")) })
        assertEquals(ErrorCode.SCOPE_VIOLATION, install.errorOrNull()?.codeAsEnum())
        assertTrue(transport.commands.isEmpty()) // nothing ran
    }

    @Test fun `clear state verifies exit code plus success output`() = runBlocking {
        val ok = FakeTransport(exitCode = 0, stdout = "Success")
        val provider = AppManageProvider(transports = ShellTransports(listOf(ok)))
        val envelope = provider.invoke(
            ctx(),
            "clear_state",
            buildJsonObject { put("package", JsonPrimitive("com.example.app")); put("confirm", JsonPrimitive("true")) },
        )
        assertEquals("VERIFIED", envelope.result!!.jsonObject["result"]!!.jsonPrimitive.content)
        assertEquals("pm clear com.example.app", ok.commands[0])

        val bad = AppManageProvider(transports = ShellTransports(listOf(FakeTransport(exitCode = 1, stdout = "Failure"))))
        val failed = bad.invoke(
            ctx(),
            "clear_state",
            buildJsonObject { put("package", JsonPrimitive("com.example.app")); put("confirm", JsonPrimitive("true")) },
        )
        assertEquals(ErrorCode.CAPABILITY_UNAVAILABLE, failed.errorOrNull()?.codeAsEnum())
    }

    @Test fun `permission names validate before exec`() = runBlocking {
        val transport = FakeTransport()
        val provider = AppManageProvider(transports = ShellTransports(listOf(transport)))
        val envelope = provider.invoke(
            ctx(),
            "grant_permission",
            buildJsonObject { put("package", JsonPrimitive("com.example.app")); put("permission", JsonPrimitive("CAMERA")) },
        )
        assertEquals(ErrorCode.INVALID_ARGS, envelope.errorOrNull()?.codeAsEnum())
        assertTrue(transport.commands.isEmpty())
    }

    @Test fun `install stat then stream and report honest failure`() = runBlocking {
        val transport = FakeTransport(exitCode = 0, stdout = "Success")
        // stat returns the size; the install result uses the same FakeTransport stdout — so the
        // size line IS the success line; use a two-command fake via stdout scripting below.
        val scripted = object : ShellTransport {
            override val transportId: String = "scripted"
            val commands = mutableListOf<String>()
            override fun describe(): String = "scripted"
            override fun status(): TransportStatus = TransportStatus.Live
            override fun invalidateStatus() {}
            override fun exec(command: String, timeoutMs: Long): ShellResult =
                if (command.startsWith("stat ")) {
                    commands.add(command)
                    ShellResult("1234", "", 0, "shell_v2")
                } else {
                    commands.add(command)
                    ShellResult("Success", "", 0, "shell_v2")
                }
        }
        val provider = AppManageProvider(transports = ShellTransports(listOf(scripted)))
        val envelope = provider.invoke(
            ctx(),
            "install",
            buildJsonObject { put("path", JsonPrimitive("/data/local/tmp/app.apk")); put("confirm", JsonPrimitive("true")) },
        )
        assertEquals("VERIFIED", envelope.result!!.jsonObject["result"]!!.jsonPrimitive.content)
        assertEquals("stat -c %s /data/local/tmp/app.apk", scripted.commands[0])
        assertEquals("cat /data/local/tmp/app.apk | pm install -S 1234 -", scripted.commands[1])

        // stat failure (unreadable path) → NOT_FOUND.
        val unreadable = object : ShellTransport {
            override val transportId: String = "unreadable"
            override fun describe(): String = "unreadable"
            override fun status(): TransportStatus = TransportStatus.Live
            override fun invalidateStatus() {}
            override fun exec(command: String, timeoutMs: Long): ShellResult =
                ShellResult("", "stat: No such file", 1, "shell_v2")
        }
        val failing = AppManageProvider(transports = ShellTransports(listOf(unreadable)))
        val missing = failing.invoke(
            ctx(),
            "install",
            buildJsonObject { put("path", JsonPrimitive("/data/local/tmp/gone.apk")); put("confirm", JsonPrimitive("true")) },
        )
        assertEquals(ErrorCode.NOT_FOUND, missing.errorOrNull()?.codeAsEnum())
    }

    @Test fun `availability needs setup without transports and installer`() {
        val provider = AppManageProvider(transports = null)
        assertEquals(Availability.NeedsSetup::class, provider.availability(DeviceEnv(sdkInt = 29))::class)
        val withUi = AppManageProvider(transports = null, stopUi = object : AppStopUi {
            override suspend fun forceStopViaUi(packageName: String): Boolean = false
        })
        assertEquals(Availability.Available, withUi.availability(DeviceEnv(sdkInt = 29)))
    }
}
