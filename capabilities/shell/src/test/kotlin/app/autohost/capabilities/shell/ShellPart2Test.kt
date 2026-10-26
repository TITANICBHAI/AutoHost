package app.autohost.capabilities.shell

import app.autohost.core.broker.CallContext
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P9-10: the logcat parser and the provider gates (pure JVM, no Android framework).
 */
class LogcatOutputTest {

    private val sample = listOf(
        "10-26 12:00:00.000  1000  1234 D AutoHost: starting",
        "1761460800.123 I/ActivityManager(  543): start proc",
        "1761460800.456 E/AutoHost(1234): boom",
        "--------- beginning of main", // banner: skipped
        "",
    ).joinToString("\n")

    @Test fun `epoch stamps parse to millis`() {
        val entries = LogcatOutput.parse(sample)
        assertEquals(3, entries.size)
        assertEquals(1_761_460_800_123L, entries[0].tsMs)
        assertEquals("I", entries[0].level)
        assertEquals("ActivityManager", entries[0].tag)
        assertEquals(543, entries[0].pid)
        assertEquals("start proc", entries[0].message)
    }

    @Test fun `missing pid parses as null and banners are skipped`() {
        val entries = LogcatOutput.parse("1761460800.789 W/AutoHost: no pid here")
        assertEquals(1, entries.size)
        assertEquals(null, entries[0].pid)
        assertEquals("no pid here", entries[0].message)
    }

    @Test fun `filters apply level pid and text`() {
        val entries = LogcatOutput.parse(sample)
        assertEquals(1, LogcatOutput.filter(entries, minLevel = "E").size)
        assertEquals(2, LogcatOutput.filter(entries, pid = 42).size)
        assertEquals(1, LogcatOutput.filter(entries, contains = "boom").size)
        assertTrue(LogcatOutput.filter(entries, levels = setOf("W")).isEmpty())
    }
}

class LogcatProviderTest {

    private fun ctx(grants: Map<String, kotlinx.serialization.json.JsonObject>) =
        CallContext(runId = "r1", taskId = "t1", profileId = null, grants = grants, nowMs = 0L)

    private fun grant() = buildJsonObject {
        put("logcat", kotlinx.serialization.json.JsonArray(emptyList()))
    }

    @Test fun `no grant is permission denied before any exec`() = runBlocking {
        val transport = FakeTransport()
        val provider = LogcatProvider(ShellTransports(listOf(transport)))
        val envelope = provider.invoke(ctx(emptyMap()), "snapshot", buildJsonObject {})
        assertEquals(ErrorCode.PERMISSION_DENIED, envelope.errorOrNull()?.codeAsEnum())
        assertTrue(transport.commands.isEmpty())
    }

    @Test fun `snapshot execs the dump and parses entries`() = runBlocking {
        val transport = FakeTransport(
            result = ShellResult(
                "1761460800.123 I/Tag1( 42): hello\n1761460800.456 E/Tag2( 7): bad",
                "", 0, "shell_v2",
            ),
        )
        val provider = LogcatProvider(ShellTransports(listOf(transport)))
        val envelope = provider.invoke(
            ctx(mapOf("device.logcat" to grant())),
            "snapshot",
            buildJsonObject { put("level", JsonPrimitive("E")) },
        )
        val payload = envelope.result!!.jsonObject
        assertEquals(1, payload["count"]!!.jsonPrimitive.content.toInt())
        val line = (payload["lines"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
        assertEquals("bad", line["message"]!!.jsonPrimitive.content)
        assertTrue(transport.commands[0].startsWith("logcat -d -v epoch"))
    }

    @Test fun `bad level is invalid args`() = runBlocking {
        val provider = LogcatProvider(ShellTransports(listOf(FakeTransport())))
        val envelope = provider.invoke(
            ctx(mapOf("device.logcat" to grant())),
            "snapshot",
            buildJsonObject { put("level", JsonPrimitive("X")) },
        )
        assertEquals(ErrorCode.INVALID_ARGS, envelope.errorOrNull()?.codeAsEnum())
    }
}

/** P9-10: the shell command builders for the part-2 family — shapes the tests and docs quote. */
class ShellCommandsPart2Test {

    @Test fun `manage builders quote safely`() {
        assertEquals("am force-stop com.example.app", ShellCommands.forceStop("com.example.app"))
        assertEquals("pm clear com.example.app", ShellCommands.pmClear("com.example.app"))
        assertEquals(
            "pm grant com.example.app android.permission.CAMERA",
            ShellCommands.pmGrant("com.example.app", "android.permission.CAMERA"),
        )
        assertEquals(
            "cat /data/local/tmp/a.apk | pm install -S 123 -",
            ShellCommands.pmInstall("/data/local/tmp/a.apk", 123),
        )
    }

    @Test fun `builders refuse unsafe inputs`() {
        for (bad in listOf("com.foo;rm", "com.foo bar", "", "\$(x)")) {
            runCatching { ShellCommands.pmClear(bad) }.onSuccess { throw AssertionError("accepted '$bad'") }
        }
        runCatching { ShellCommands.pmInstall("/data/local/tmp/../etc.apk", 1) }
            .onSuccess { throw AssertionError("accepted traversal") }
        runCatching { ShellCommands.pmInstall("/data/local/tmp/a.apk", -1) }
            .onSuccess { throw AssertionError("accepted bad size") }
    }

    @Test fun `logcat dump bounds tags and tail`() {
        assertEquals("logcat -d -v epoch -t 100", ShellCommands.logcatDump(100, emptyList()))
        assertEquals(
            "logcat -d -v epoch -t 100 AutoHost:* *:S",
            ShellCommands.logcatDump(100, listOf("AutoHost")),
        )
        runCatching { ShellCommands.logcatDump(0, emptyList()) }
            .onSuccess { throw AssertionError("accepted zero lines") }
        runCatching { ShellCommands.logcatDump(100, listOf("bad tag")) }
            .onSuccess { throw AssertionError("accepted space in tag") }
    }

    @Test fun `settings put escapes single quotes only`() {
        assertEquals(
            "settings put secure my.key 'plain'",
            ShellCommands.settingsPut("secure", "my.key", "plain"),
        )
        assertEquals(
            "settings put system k 'it'\\''s'",
            ShellCommands.settingsPut("system", "k", "it's"),
        )
        runCatching { ShellCommands.settingsPut("nonsense", "k", "v") }
            .onSuccess { throw AssertionError("accepted bad table") }
    }

    @Test fun `radio display and appop builders`() {
        assertEquals("svc wifi enable", ShellCommands.svcWifi(true))
        assertEquals("svc bluetooth disable", ShellCommands.svcBluetooth(false))
        assertEquals("svc data enable", ShellCommands.svcData(true))
        assertEquals("settings put global airplane_mode_on 1", ShellCommands.airplaneModePut(true))
        assertEquals("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true", ShellCommands.airplaneModeBroadcast())
        assertEquals("cmd uimode night auto", ShellCommands.uimodeNight("auto"))
        assertEquals("settings put system accelerometer_rotation 0", ShellCommands.accelerometerRotation(false))
        assertEquals("settings put system user_rotation 1", ShellCommands.userRotation(1))
        assertEquals("settings put system font_scale 1.1", ShellCommands.fontScale("1.1"))
        assertEquals("appops set app.autohost android:mock_location allow", ShellCommands.mockLocationAppop(true))
        assertEquals("appops get app.autohost android:mock_location", ShellCommands.mockLocationAppopGet())
    }
}

/** P9-10: radio/display/mock-location providers over a fake transport. */
class DeviceControlProvidersTest {

    private fun ctx(grant: String) = CallContext(
        runId = "r1",
        taskId = "t1",
        profileId = null,
        grants = mapOf(grant to buildJsonObject { put(grant, kotlinx.serialization.json.JsonArray(emptyList())) }),
        nowMs = 0L,
    )

    @Test fun `radio set reports applied without a verification claim`() = runBlocking {
        val transport = FakeTransport(result = ShellResult("", "", 0, "shell_v2"))
        val provider = RadioProvider(ShellTransports(listOf(transport)))
        val envelope = provider.invoke(
            ctx("device.radio"),
            "set",
            buildJsonObject { put("radio", JsonPrimitive("wifi")); put("enable", JsonPrimitive("false")) },
        )
        val payload = envelope.result!!.jsonObject
        assertFalse(payload["verified"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(payload["applied"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("svc wifi disable", transport.commands[0])
    }

    @Test fun `airplane mode runs the two-step sequence`() = runBlocking {
        val transport = FakeTransport(result = ShellResult("", "", 0, "shell_v2"))
        val provider = RadioProvider(ShellTransports(listOf(transport)))
        provider.invoke(
            ctx("device.radio"),
            "set",
            buildJsonObject { put("radio", JsonPrimitive("airplane")); put("enable", JsonPrimitive("true")) },
        )
        assertEquals("settings put global airplane_mode_on 1", transport.commands[0])
        assertTrue(transport.commands[1].startsWith("am broadcast"))
    }

    @Test fun `unknown radio is invalid args`() = runBlocking {
        val provider = RadioProvider(ShellTransports(listOf(FakeTransport())))
        val envelope = provider.invoke(
            ctx("device.radio"),
            "set",
            buildJsonObject { put("radio", JsonPrimitive("teleport")); put("enable", JsonPrimitive("true")) },
        )
        assertEquals(ErrorCode.INVALID_ARGS, envelope.errorOrNull()?.codeAsEnum())
    }

    @Test fun `display orientation locks then rotates`() = runBlocking {
        val transport = FakeTransport(result = ShellResult("", "", 0, "shell_v2"))
        val provider = DisplayProvider(ShellTransports(listOf(transport)))
        provider.invoke(
            ctx("device.display"),
            "set_orientation",
            buildJsonObject { put("orientation", JsonPrimitive("landscape")) },
        )
        assertEquals("settings put system accelerometer_rotation 0", transport.commands[0])
        assertEquals("settings put system user_rotation 1", transport.commands[1])
    }

    @Test fun `mock location verifies through appops get`() = runBlocking {
        val transport = FakeTransport(result = ShellResult("mock_location: allow", "", 0, "shell_v2"))
        val provider = MockLocationProvider(ShellTransports(listOf(transport)))
        val envelope = provider.invoke(
            ctx("device.location.mock"),
            "set",
            buildJsonObject { put("enable", JsonPrimitive("true")) },
        )
        val payload = envelope.result!!.jsonObject
        assertTrue(payload["verified"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("appops set app.autohost android:mock_location allow", transport.commands[0])
        assertEquals("appops get app.autohost android:mock_location", transport.commands[1])
    }

    @Test fun `no live transport is needs setup`() = runBlocking {
        val provider = MockLocationProvider(ShellTransports(listOf(FakeTransport(state = TransportStatus.NeedsSetup("SHELL_TIER_NOT_PAIRED")))))
        val envelope = provider.invoke(
            ctx("device.location.mock"),
            "set",
            buildJsonObject { put("enable", JsonPrimitive("true")) },
        )
        assertEquals(ErrorCode.NEEDS_SETUP, envelope.errorOrNull()?.codeAsEnum())
    }
}
