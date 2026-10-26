package app.autohost.capabilities.shell

import app.autohost.core.broker.CallContext
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** P9-04: the device.shell allowlist — fail-closed grants, metacharacter deny, glob semantics. */
class CommandAllowlistTest {

    private val grant = buildJsonObject {
        put("commands", JsonArray(listOf(JsonPrimitive("settings get *"), JsonPrimitive("dumpsys battery"))))
    }

    @Test fun `allowlisted commands pass`() {
        assertEquals(CommandAllowlist.Verdict.Allowed, CommandAllowlist.verdict(grant, "settings get secure x"))
        assertEquals(CommandAllowlist.Verdict.Allowed, CommandAllowlist.verdict(grant, "dumpsys battery"))
    }

    @Test fun `unlisted commands are denied`() {
        assertTrue(CommandAllowlist.verdict(grant, "rm -rf /data") is CommandAllowlist.Verdict.Denied)
        assertTrue(CommandAllowlist.verdict(grant, "dumpsys batteryx") is CommandAllowlist.Verdict.Denied)
    }

    @Test fun `no grant and empty allowlist both deny`() {
        assertTrue(CommandAllowlist.verdict(null, "settings get x") is CommandAllowlist.Verdict.Denied)
        val empty = buildJsonObject { put("commands", JsonArray(emptyList())) }
        assertTrue(CommandAllowlist.verdict(empty, "settings get x") is CommandAllowlist.Verdict.Denied)
    }

    @Test fun `metacharacters are refused even when a pattern would match`() {
        for (command in listOf(
            "settings get secure; rm -rf /data",
            "settings get secure && reboot",
            "settings get secure | grep x",
            "settings get `id`",
            "settings get $(id)",
            "settings get secure > /data/exfil",
            "settings get secure < /etc/passwd",
            "settings get secure\nreboot",
        )) {
            val verdict = CommandAllowlist.verdict(grant, command)
            assertTrue("expected deny for $command", verdict is CommandAllowlist.Verdict.Denied)
        }
    }

    @Test fun `dollar sign is refused (parameter expansion)`() {
        assertTrue(CommandAllowlist.verdict(grant, "settings get \$SECURE") is CommandAllowlist.Verdict.Denied)
    }

    @Test fun `empty and oversized commands are denied`() {
        assertTrue(CommandAllowlist.verdict(grant, "   ") is CommandAllowlist.Verdict.Denied)
        assertTrue(CommandAllowlist.verdict(grant, "x".repeat(4001)) is CommandAllowlist.Verdict.Denied)
    }

    @Test fun `glob semantics mirror the sim`() {
        assertTrue(CommandAllowlist.matches("settings get ?ecure", "settings get secure"))
        assertFalse(CommandAllowlist.matches("settings get ?ecure", "settings get secure x"))
        assertTrue(CommandAllowlist.matches("dumpsys *", "dumpsys window"))
        assertFalse(CommandAllowlist.matches("dumpsys *", "dumpsysx window"))
        assertTrue(CommandAllowlist.matches("*", "anything at all"))
        assertFalse(CommandAllowlist.matches("", "anything"))
    }
}

/** Fake transport (repo rule: fakes, not mocks — plain data recording). */
private class FakeTransport(
    override val transportId: String = "fake",
    private var state: TransportStatus = TransportStatus.Live,
    private val result: ShellResult? = null,
    private val failure: ShellTransportException? = null,
) : ShellTransport {
    val commands = mutableListOf<String>()

    override fun describe(): String = "fake transport"
    override fun status(): TransportStatus = state
    override fun invalidateStatus() {}
    fun setState(next: TransportStatus) {
        state = next
    }

    override fun exec(command: String, timeoutMs: Long): ShellResult {
        commands.add(command)
        failure?.let { throw it }
        return result ?: ShellResult("ok", "", 0, "shell_v2")
    }
}

private fun context(grants: Map<String, JsonObject> = emptyMap()): CallContext =
    CallContext(runId = "r1", taskId = "t1", profileId = null, grants = grants, nowMs = 0L)

/** P9-04: provider mapping — grants, allowlist verdicts, timeouts, transport status. */
class ShellProviderTest {

    private val allowGrant = buildJsonObject {
        put("commands", JsonArray(listOf(JsonPrimitive("settings get *"))))
    }

    @Test fun `availability follows the transport chain`() {
        val down = ShellProvider(ShellTransports(listOf(FakeTransport(state = TransportStatus.NeedsSetup("SHELL_TIER_NOT_PAIRED")))))
        assertTrue(down.availability(env()) is Availability.NeedsSetup)
        val live = ShellProvider(ShellTransports(listOf(FakeTransport())))
        assertEquals(Availability.Available, live.availability(env()))
    }

    @Test fun `run without grant is permission denied`() = runBlocking {
        val provider = ShellProvider(ShellTransports(listOf(FakeTransport())))
        val envelope = provider.invoke(context(), "run", buildJsonObject { put("command", "settings get x") })
        assertEquals(ErrorCode.PERMISSION_DENIED, envelope.errorOrNull()?.codeAsEnum())
    }

    @Test fun `run outside the allowlist is scope violation`() = runBlocking {
        val provider = ShellProvider(ShellTransports(listOf(FakeTransport())))
        val envelope = provider.invoke(context(mapOf("device.shell" to allowGrant)), "run", buildJsonObject { put("command", "reboot") })
        assertEquals(ErrorCode.SCOPE_VIOLATION, envelope.errorOrNull()?.codeAsEnum())
    }

    @Test fun `allowlisted run returns the transport result`() = runBlocking {
        val transport = FakeTransport(result = ShellResult("out-text", "err-text", 3, "shell_v2"))
        val provider = ShellProvider(ShellTransports(listOf(transport)))
        val envelope = provider.invoke(
            context(mapOf("device.shell" to allowGrant)),
            "run",
            buildJsonObject { put("command", "settings get secure x") },
        )
        val result = (envelope.resultOrNull() as JsonObject).jsonObject
        assertEquals("out-text", result["stdout"]!!.jsonPrimitive.content)
        assertEquals(3, result["exit_code"]!!.jsonPrimitive.int)
        assertEquals("fake", result["transport"]!!.jsonPrimitive.content)
        assertEquals(listOf("settings get secure x"), transport.commands)
    }

    @Test fun `run without a live transport is needs setup`() = runBlocking {
        val provider = ShellProvider(
            ShellTransports(listOf(FakeTransport(state = TransportStatus.NeedsSetup("SHELL_TIER_NOT_PAIRED")))),
        )
        val envelope = provider.invoke(
            context(mapOf("device.shell" to allowGrant)),
            "run",
            buildJsonObject { put("command", "settings get x") },
        )
        assertEquals(ErrorCode.NEEDS_SETUP, envelope.errorOrNull()?.codeAsEnum())
    }

    @Test fun `timeouts map to TIMEOUT, unknown method is invalid args`() = runBlocking {
        val provider = ShellProvider(ShellTransports(listOf(FakeTransport())))
        assertEquals(
            ErrorCode.INVALID_ARGS,
            provider.invoke(context(mapOf("device.shell" to allowGrant)), "nope", buildJsonObject { }).errorOrNull()?.codeAsEnum(),
        )
        val timing = ShellProvider(
            ShellTransports(
                listOf(
                    FakeTransport(
                        failure = ShellTransportException(ShellTransportException.Reason.TIMEOUT, "late"),
                    ),
                ),
            ),
        )
        assertEquals(
            ErrorCode.TIMEOUT,
            timing.invoke(
                context(mapOf("device.shell" to allowGrant)),
                "run",
                buildJsonObject { put("command", "settings get x") },
            ).errorOrNull()?.codeAsEnum(),
        )
    }

    @Test fun `transports reports each state`() = runBlocking {
        val provider = ShellProvider(
            ShellTransports(
                listOf(
                    FakeTransport(transportId = "adb.tcp"),
                    FakeTransport(transportId = "root", state = TransportStatus.NeedsSetup("NO_ROOT")),
                ),
            ),
        )
        val envelope = provider.invoke(context(mapOf("device.shell" to allowGrant)), "transports", buildJsonObject { })
        val rows = (envelope.resultOrNull() as JsonObject)["transports"] as JsonArray
        assertEquals(2, rows.size)
        assertEquals("live", (rows[0] as JsonObject)["state"]!!.jsonPrimitive.content)
        assertEquals("needs_setup", (rows[1] as JsonObject)["state"]!!.jsonPrimitive.content)
    }

    private fun env() = app.autohost.core.broker.DeviceEnv(sdkInt = 29)
}

/** P9-05: the uiautomator XML parser (pure, machine-generated format). */
class UiautomatorDumpTest {

    private val sample = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <hierarchy rotation="0">
          <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.fixture"
                content-desc="" checkable="false" checked="false" clickable="false" enabled="true"
                focusable="false" focused="false" scrollable="false" long-clickable="false" password="false"
                selected="false" bounds="[0,0][1080,2400]">
            <node index="1" text="Sign in" resource-id="com.fixture:id/login" class="android.widget.Button"
                  package="com.fixture" content-desc="go" checkable="false" clickable="true" enabled="true"
                  focused="true" long-clickable="true" password="false" selected="true" bounds="[100,200][300,400]"/>
          </node>
        </hierarchy>
    """.trimIndent()

    @Test fun `parses attributes into the flat node list`() {
        val nodes = UiautomatorDump.parse(sample)
        assertEquals(2, nodes.size)
        val root = nodes[0]
        assertEquals("com.fixture", root.pkg)
        assertEquals("android.widget.FrameLayout", root.cls)
        assertEquals(-1, root.parent)
        assertEquals(0, root.depth)
        assertEquals(0, root.index)
        val button = nodes[1]
        assertEquals("Sign in", button.text)
        assertEquals("com.fixture:id/login", button.id)
        assertEquals("go", button.desc)
        assertEquals(0, button.parent)
        assertEquals(1, button.depth)
        assertTrue(button.has("clickable"))
        assertTrue(button.has("long_clickable"))
        assertTrue(button.has("focused"))
        assertTrue(button.has("selected"))
        assertTrue(button.has("enabled"))
        assertFalse(button.has("password"))
        assertEquals(100, button.bounds.l)
        assertEquals(200, button.bounds.t)
        assertEquals(300, button.bounds.r)
        assertEquals(400, button.bounds.b)
    }

    @Test fun `escapes round-trip and nested structure keeps depth`() {
        val nested = """
            <hierarchy>
              <node text="a&amp;b" class="X" package="p" bounds="[0,0][1,1]">
                <node text="&lt;inner&gt;" class="Y" package="p" bounds="[0,0][1,1]">
                  <node text="deep" class="Z" package="p" bounds="[0,0][1,1]"/>
                </node>
              </node>
            </hierarchy>
        """.trimIndent()
        val nodes = UiautomatorDump.parse(nested)
        assertEquals(3, nodes.size)
        assertEquals("a&b", nodes[0].text)
        assertEquals("<inner>", nodes[1].text)
        assertEquals(2, nodes[2].depth)
        assertEquals(0, nodes[2].parent)
    }

    @Test fun `corrupt input fails with a typed parse error`() {
        try {
            UiautomatorDump.parse("<hierarchy><node")
            throw AssertionError("expected DumpParseException")
        } catch (e: UiautomatorDump.DumpParseException) {
            // expected
        }
        try {
            UiautomatorDump.parse("")
            throw AssertionError("expected DumpParseException")
        } catch (e: UiautomatorDump.DumpParseException) {
            // expected
        }
    }
}

/** P9-05/P9-06: the shell device.ui and device.screenshot providers over a fake transport. */
class ShellUiAndScreenshotProviderTest {

    private val dumpXml = """
        <hierarchy>
          <node text="Sign in" resource-id="com.fixture:id/login" class="android.widget.Button" package="com.fixture"
                clickable="true" bounds="[100,200][300,400]"/>
          <node text="" resource-id="com.fixture:id/secret" class="android.widget.EditText" package="com.other"
                bounds="[0,0][10,10]"/>
        </hierarchy>
    """.trimIndent()

    private fun transport() = FakeTransport(
        result = ShellResult("UI hierarchy dumped to: /data/local/tmp/x\n$dumpXml", "", 0, "shell_v2"),
    )

    @Test fun `snapshot parses the dump and reports package and compact text`() = runBlocking {
        val provider = ShellUiProvider(ShellTransports(listOf(transport())))
        val envelope = provider.invoke(context(), "snapshot", buildJsonObject { })
        val result = (envelope.resultOrNull() as JsonObject).jsonObject
        assertEquals(2, result["node_count"]!!.jsonPrimitive.int)
        assertEquals("com.fixture", result["package"]!!.jsonPrimitive.content)
        assertTrue((result["compact"]!!.jsonPrimitive.content).contains("Button"))
    }

    @Test fun `find uses the shared selector engine`() = runBlocking {
        val provider = ShellUiProvider(ShellTransports(listOf(transport())))
        val selector = buildJsonObject {
            put(
                "terms",
                JsonArray(listOf(buildJsonObject { put("text", buildJsonObject {
                    put("labels", JsonArray(listOf(JsonPrimitive("Sign in"))))
                    put("mode", "equals")
                }) })),
            )
        }
        val envelope = provider.invoke(context(), "find", buildJsonObject { put("selector", selector) })
        val node = ((envelope.resultOrNull() as JsonObject)["node"] as JsonObject).jsonObject
        assertEquals("Sign in", node["text"]!!.jsonPrimitive.content)
        assertEquals("com.fixture:id/login", node["id"]!!.jsonPrimitive.content)
    }

    @Test fun `acts scope by the device.ui.act grant`() = runBlocking {
        val transport = transport()
        val provider = ShellUiProvider(ShellTransports(listOf(transport)))
        val inScope = context(
            mapOf("device.ui.act" to buildJsonObject { put("packages", JsonArray(listOf(JsonPrimitive("com.fixture")))) }),
        )
        val outOfScope = context(
            mapOf("device.ui.act" to buildJsonObject { put("packages", JsonArray(listOf(JsonPrimitive("com.other")))) }),
        )
        val target = buildJsonObject { put("selector", buildJsonObject {
            put("terms", JsonArray(listOf(buildJsonObject { put("id", buildJsonObject {
                put("labels", JsonArray(listOf(JsonPrimitive("com.fixture:id/login"))))
                put("mode", "equals")
            }) })))
        }) }
        assertEquals(true, provider.invoke(inScope, "click", buildJsonObject { put("target", target) }).ok)
        assertEquals(
            ErrorCode.SCOPE_VIOLATION,
            provider.invoke(outOfScope, "click", buildJsonObject { put("target", target) }).errorOrNull()?.codeAsEnum(),
        )
    }

    @Test fun `click reports shell input honestly unverified without expect`() = runBlocking {
        val provider = ShellUiProvider(ShellTransports(listOf(transport())))
        val envelope = provider.invoke(context(), "tap", buildJsonObject { put("x", 10); put("y", 20) })
        val result = (envelope.resultOrNull() as JsonObject).jsonObject
        assertEquals("shell.input", result["method"]!!.jsonPrimitive.content)
        val transport = ShellTransports(listOf(transport()))
        val clickProvider = ShellUiProvider(transport)
        val target = buildJsonObject { put("ref", "u1.0") }
        // prime the ref cache with a dump first
        clickProvider.invoke(context(), "snapshot", buildJsonObject { })
        val clickResult = (clickProvider.invoke(context(), "click", buildJsonObject { put("target", target) })
            .resultOrNull() as JsonObject).jsonObject
        assertEquals(false, clickResult["verified"]!!.jsonPrimitive.boolean)
    }

    @Test fun `secret text is refused (shell lines are not secret-safe)`() = runBlocking {
        val provider = ShellUiProvider(ShellTransports(listOf(transport())))
        val target = buildJsonObject { put("ref", "u1.0") }
        val envelope = provider.invoke(
            context(), "set_text",
            buildJsonObject { put("target", target); put("text", "hunter2"); put("secret", true) },
        )
        assertEquals(ErrorCode.TEXT_INPUT_REJECTED, envelope.errorOrNull()?.codeAsEnum())
    }

    @Test fun `press maps keyevent codes and rejects unknown keys`() = runBlocking {
        val transport = transport()
        val provider = ShellUiProvider(ShellTransports(listOf(transport)))
        provider.invoke(context(), "press", buildJsonObject { put("key", "back") })
        assertTrue(transport.commands.contains("input keyevent 4"))
        assertEquals(
            ErrorCode.INVALID_ARGS,
            provider.invoke(context(), "press", buildJsonObject { put("key", "warp") }).errorOrNull()?.codeAsEnum(),
        )
    }

    @Test fun `screencap writes a png into the evidence dir with IHDR dimensions`() = runBlocking {
        val dir = File(java.lang.System.getProperty("java.io.tmpdir"), "shell-evidence-${System.nanoTime()}")
        val png = pngBytes(64, 32)
        val transport = FakeTransport(result = ShellResult("", "", 0, "shell_v2", stdoutBytes = png))
        val provider = ShellScreenshotProvider(ShellTransports(listOf(transport)), dir)
        val envelope = provider.invoke(context(), "capture", buildJsonObject { })
        val result = (envelope.resultOrNull() as JsonObject).jsonObject
        assertEquals("shell.screencap", result["provider"]!!.jsonPrimitive.content)
        assertEquals(64, result["width"]!!.jsonPrimitive.int)
        assertEquals(32, result["height"]!!.jsonPrimitive.int)
        val written = File(result["path"]!!.jsonPrimitive.content)
        assertTrue(written.isFile)
        assertTrue(written.length() > 0)
        written.delete()
        dir.delete()
    }

    @Test fun `non-png screencap output is an honest block`() = runBlocking {
        val transport = FakeTransport(result = ShellResult("error: device offline", "", 1, "shell"))
        val provider = ShellScreenshotProvider(
            ShellTransports(listOf(transport)),
            File(java.lang.System.getProperty("java.io.tmpdir")),
        )
        val envelope = provider.invoke(context(), "capture", buildJsonObject { })
        assertEquals(ErrorCode.SCREENSHOT_BLOCKED, envelope.errorOrNull()?.codeAsEnum())
    }

    /** Minimal valid PNG: signature + IHDR with width/height (enough for the header parser). */
    private fun pngBytes(width: Int, height: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val header = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // signature
            0, 0, 0, 13, // IHDR length
            0x49, 0x48, 0x44, 0x52, // "IHDR"
        )
        out.write(header)
        fun int(value: Int) = byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
        )
        out.write(int(width))
        out.write(int(height))
        out.write(ByteArray(32)) // rest of the chunk + padding (parser only reads the header)
        return out.toByteArray()
    }
}

/** Envelope helpers used above. */
private fun Envelope.errorOrNull() = error
private fun Envelope.resultOrNull() = result

private fun app.autohost.core.model.AhError.codeAsEnum(): ErrorCode = ErrorCode.valueOf(code)
