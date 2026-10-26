package app.autohost.capabilities.app

import app.autohost.core.broker.CallContext
import app.autohost.core.common.FakeClock
import app.autohost.core.common.ForegroundSource
import app.autohost.core.common.ForegroundTracker
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakePackageReader(var apps: List<AppInfo> = emptyList()) : PackageReader {
    override fun query(pkg: String): AppInfo? = apps.firstOrNull { it.pkg == pkg }

    override fun list(filter: String?): List<AppInfo> =
        apps.filter { filter == null || it.pkg.contains(filter, ignoreCase = true) || it.label.contains(filter, ignoreCase = true) }
}

class FakeLauncher : ActivityLauncher {
    val launched = mutableListOf<String>()
    var result = true

    override fun launch(packageName: String): Boolean {
        launched.add(packageName)
        return result
    }
}

class AppProviderTest {
    private val clock = FakeClock()
    private val reader = FakePackageReader(
        listOf(
            AppInfo("app.autohost.fixture", "AutoHost Fixture", "1.0", 5),
            AppInfo("com.example.calculator", "Calculator", "2.3.1", 42),
        ),
    )
    private val foregroundPkg = MutableForeground()
    private lateinit var launcher: FakeLauncher
    private lateinit var provider: AppProvider

    private val ctx = CallContext(
        runId = "run-1",
        taskId = "task-1",
        profileId = null,
        grants = mapOf(
            "device.app.query" to buildJsonObject { },
            "device.app.launch" to buildJsonObject {
                put("packages", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("app.autohost.fixture"))))
            },
        ),
        nowMs = 0L,
    )

    private class MutableForeground(var current: ForegroundTracker.Foreground? = null) : ForegroundSource {
        override fun current(): ForegroundTracker.Foreground? = current
    }

    private fun setUp(launcher: FakeLauncher = FakeLauncher()) {
        this.launcher = launcher
        provider = AppProvider(reader, launcher, foregroundPkg, clock, pollIntervalMs = 10)
    }

    @Test
    fun `given installed package when queried then installed with version`() = runTest {
        setUp()
        val envelope = provider.invoke(ctx, "query", buildJsonObject { put("package", "com.example.calculator") })
        assertTrue(envelope.ok)
        val result = envelope.result!!.jsonObject
        assertTrue((result["installed"] as JsonPrimitive).content.toBooleanStrict())
        assertEquals("2.3.1", (result["version_name"] as JsonPrimitive).content)
    }

    @Test
    fun `given unknown package when queried then installed false not error`() = runTest {
        setUp()
        val envelope = provider.invoke(ctx, "query", buildJsonObject { put("package", "com.ghost") })
        assertTrue(envelope.ok)
        assertFalse((envelope.result!!.jsonObject["installed"] as JsonPrimitive).content.toBooleanStrict())
    }

    @Test
    fun `given filter when listed then matching packages returned`() = runTest {
        setUp()
        val envelope = provider.invoke(ctx, "list", buildJsonObject { put("filter", "calc") })
        val packages = (envelope.result!!.jsonObject["packages"] as kotlinx.serialization.json.JsonArray)
        assertEquals(1, packages.size)
    }

    @Test
    fun `given foreground tracked when read then package and activity reported`() = runTest {
        setUp()
        foregroundPkg.current = ForegroundTracker.Foreground("app.autohost.fixture", ".HomeActivity", clock.nowMs())
        val envelope = provider.invoke(ctx, "foreground", buildJsonObject { })
        val result = envelope.result!!.jsonObject
        assertEquals("app.autohost.fixture", (result["package"] as JsonPrimitive).content)
        assertEquals(".HomeActivity", (result["activity"] as JsonPrimitive).content)
    }

    @Test
    fun `given nothing foreground when read then not found with reason`() = runTest {
        setUp()
        val envelope = provider.invoke(ctx, "foreground", buildJsonObject { })
        assertEquals(ErrorCode.NOT_FOUND.name, envelope.error?.code)
        assertEquals("NO_FOREGROUND", envelope.error?.reason)
    }

    @Test
    fun `given package appears while waiting then wait foreground succeeds`() = runTest {
        setUp()
        // appears after the first poll: flip the source as the clock advances
        foregroundPkg.current = null
        provider = AppProvider(reader, launcher, {
            if (clock.nowMs() >= 500) ForegroundTracker.Foreground("app.autohost.fixture", null, clock.nowMs()) else null
        }, clock, pollIntervalMs = 10)
        val envelope = provider.invoke(ctx, "wait_foreground", buildJsonObject {
            put("package", "app.autohost.fixture"); put("timeout_ms", 10_000)
        })
        assertTrue(envelope.ok)
    }

    @Test
    fun `given package never appears then wait foreground times out`() = runTest {
        setUp()
        val tickingClock = FakeClock(autoAdvancePerReadMs = 100)
        provider = AppProvider(reader, launcher, { null }, tickingClock, pollIntervalMs = 10)
        val envelope = provider.invoke(ctx, "wait_foreground", buildJsonObject {
            put("package", "app.autohost.fixture"); put("timeout_ms", 1_000)
        })
        assertEquals(ErrorCode.TIMEOUT.name, envelope.error?.code)
        assertEquals("FOREGROUND_TIMEOUT", envelope.error?.reason)
    }

    @Test
    fun `given launcher present when launched then verified when foreground follows`() = runTest {
        setUp()
        // foreground flips right after launch
        provider = AppProvider(reader, launcher, {
            if (launcher.launched.isNotEmpty()) ForegroundTracker.Foreground("app.autohost.fixture", null, clock.nowMs()) else null
        }, clock, pollIntervalMs = 10)
        val envelope = provider.invoke(ctx, "launch", buildJsonObject { put("package", "app.autohost.fixture") })
        assertTrue(envelope.ok)
        val result = envelope.result!!.jsonObject
        assertEquals("LAUNCHED_VERIFIED", (result["result"] as JsonPrimitive).content)
    }

    @Test
    fun `given launch without verification then result is launched unverified`() = runTest {
        setUp()
        val tickingClock = FakeClock(autoAdvancePerReadMs = 500)
        provider = AppProvider(reader, launcher, { null }, tickingClock, pollIntervalMs = 10)
        val envelope = provider.invoke(ctx, "launch", buildJsonObject { put("package", "app.autohost.fixture") })
        assertTrue(envelope.ok)
        assertEquals("LAUNCHED_UNVERIFIED", (envelope.result!!.jsonObject["result"] as JsonPrimitive).content)
    }

    @Test
    fun `given no launcher when launch then needs setup with a11y fix action`() = runTest {
        provider = AppProvider(reader, null, foregroundPkg, clock)
        val envelope = provider.invoke(ctx, "launch", buildJsonObject { put("package", "app.autohost.fixture") })
        assertEquals(ErrorCode.NEEDS_SETUP.name, envelope.error?.code)
        assertEquals("OPEN_ACCESSIBILITY_SETTINGS", envelope.error?.fixAction)
    }

    @Test
    fun `given stop with no killer and target not foreground then best effort`() = runTest {
        setUp()
        val envelope = provider.invoke(ctx, "stop", buildJsonObject { put("package", "com.x") })
        assertTrue(envelope.ok)
        assertEquals("BEST_EFFORT", (envelope.result!!.jsonObject["result"] as JsonPrimitive).content)
    }

    @Test
    fun `given stop when target leaves foreground then verified`() = runTest {
        setUp()
        var foregroundTarget = true
        provider = AppProvider(reader, launcher, {
            ForegroundTracker.Foreground(if (foregroundTarget) "com.x" else "other.app", null, clock.nowMs())
        }, clock, killer = { foregroundTarget = false }, pollIntervalMs = 10)
        val envelope = provider.invoke(ctx, "stop", buildJsonObject { put("package", "com.x") })
        assertTrue(envelope.ok)
        assertEquals("VERIFIED", (envelope.result!!.jsonObject["result"] as JsonPrimitive).content)
    }

    @Test
    fun `given shell strategy when stop then refused until phase 9`() = runTest {
        setUp()
        val envelope = provider.invoke(ctx, "stop", buildJsonObject { put("package", "com.x"); put("strategy", "shell") })
        assertEquals(ErrorCode.CAPABILITY_UNAVAILABLE.name, envelope.error?.code)
        assertEquals("SHELL_TIER_NOT_PAIRED", envelope.error?.reason)
    }

    @Test
    fun `given unknown method then permission unknown`() {
        setUp()
        assertEquals(null, provider.permissionFor("explode", buildJsonObject { }))
        assertEquals("device.app.query", provider.permissionFor("foreground", buildJsonObject { }))
        assertEquals("device.app.launch", provider.permissionFor("launch", buildJsonObject { }))
        assertEquals("device.app.stop", provider.permissionFor("stop", buildJsonObject { }))
    }
}
