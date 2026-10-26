package app.autohost.core.engine

import app.autohost.core.broker.PermissionIndex
import app.autohost.core.engine.host.InMemoryKvBackend
import app.autohost.core.engine.host.StoreProvider
import app.autohost.core.model.CapabilityNeed
import app.autohost.core.model.Needs
import app.autohost.core.model.RuntimeSpec
import app.autohost.core.model.TaskManifest
import app.autohost.core.engine.tasks.GrantBuilder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrantBuilderTest {
    private val index = PermissionIndex.parse(
        """
        {"capabilities":[
          {"id":"device.ui","permissions":[{"id":"device.ui.observe"},{"id":"device.ui.act"}]},
          {"id":"device.app","permissions":[{"id":"device.app.query"},{"id":"device.app.launch"},{"id":"device.app.stop"}]}
        ]}
        """.trimIndent(),
    )

    private val config = buildJsonObject { put("target", "com.example") }

    @Test
    fun `given permission level need then granted with templated scope`() {
        val manifest = TaskManifest(
            id = "t",
            name = "T",
            runtime = RuntimeSpec("python", "main.py"),
            needs = Needs(capabilities = listOf(CapabilityNeed("device.ui.act", buildJsonObject {
                put("packages", JsonArray(listOf(JsonPrimitive("\${config.target}"))))
            }))),
        )
        val grants = GrantBuilder(index, devMode = false).build(manifest, config, buildJsonObject { })
        assertTrue(grants.containsKey("device.ui.act"))
        val packages = (grants["device.ui.act"]!!["packages"] as JsonArray)
        assertEquals("com.example", (packages.first() as JsonPrimitive).content)
        assertFalse(grants.containsKey("device.ui.observe"))
    }

    @Test
    fun `given capability level need then all permissions granted`() {
        val manifest = TaskManifest(
            id = "t",
            name = "T",
            runtime = RuntimeSpec("python", "main.py"),
            needs = Needs(capabilities = listOf(CapabilityNeed("device.ui"))),
        )
        val grants = GrantBuilder(index, devMode = false).build(manifest, config, buildJsonObject { })
        assertTrue(grants.containsKey("device.ui.observe"))
        assertTrue(grants.containsKey("device.ui.act"))
    }

    @Test
    fun `given implicit needs then always granted`() {
        val manifest = TaskManifest(id = "t", name = "T", runtime = RuntimeSpec("python", "main.py"))
        val grants = GrantBuilder(index, devMode = false).build(manifest, config, buildJsonObject { })
        for (permission in listOf("store.profile", "store.task", "state.checkpoint", "secrets.read", "system.info")) {
            assertTrue("implicit $permission", grants.containsKey(permission))
        }
    }

    @Test
    fun `given dev mode then every known permission granted unrestricted`() {
        val manifest = TaskManifest(id = "t", name = "T", runtime = RuntimeSpec("python", "main.py"))
        val grants = GrantBuilder(index, devMode = true).build(manifest, config, buildJsonObject { })
        for (permission in index.allPermissions()) {
            assertTrue("dev-mode $permission", grants.containsKey(permission))
        }
    }

    @Test
    fun `given unknown need then ignored fail closed`() {
        val manifest = TaskManifest(
            id = "t",
            name = "T",
            runtime = RuntimeSpec("python", "main.py"),
            needs = Needs(capabilities = listOf(CapabilityNeed("device.ghost"))),
        )
        val grants = GrantBuilder(index, devMode = false).build(manifest, config, buildJsonObject { })
        assertFalse(grants.containsKey("device.ghost"))
        assertFalse(grants.keys.any { it.startsWith("device.ghost") })
    }

    @Test
    fun `given unresolved placeholder then scope stays literal`() {
        val manifest = TaskManifest(
            id = "t",
            name = "T",
            runtime = RuntimeSpec("python", "main.py"),
            needs = Needs(capabilities = listOf(CapabilityNeed("device.ui.act", buildJsonObject {
                put("packages", JsonArray(listOf(JsonPrimitive("\${config.not_there}"))))
            }))),
        )
        val grants = GrantBuilder(index, devMode = false).build(manifest, config, buildJsonObject { })
        val packages = (grants["device.ui.act"]!!["packages"] as JsonArray)
        assertEquals("\${config.not_there}", (packages.first() as JsonPrimitive).content)
    }
}

class StoreProviderTest {
    private val backend = InMemoryKvBackend()
    private val provider = StoreProvider(backend)
    private val ctx = app.autohost.core.broker.CallContext(
        runId = "run-1",
        taskId = "task-1",
        profileId = null,
        grants = mapOf(
            "store.profile" to buildJsonObject { },
            "store.task" to buildJsonObject { },
        ),
        nowMs = 0L,
    )

    @Test
    fun `given missing scope arg then permission unknown`() {
        assertNull(provider.permissionFor("get", buildJsonObject { }))
        assertEquals("store.profile", provider.permissionFor("get", buildJsonObject { put("scope", "profile") }))
        assertEquals("store.task", provider.permissionFor("get", buildJsonObject { put("scope", "task") }))
    }

    @Test
    fun `given set and get then value round trips`() = runTest {
        val set = provider.invoke(ctx, "set", buildJsonObject {
            put("scope", "task"); put("key", "counter"); put("value", 42)
        })
        assertTrue(set.ok)
        val get = provider.invoke(ctx, "get", buildJsonObject { put("scope", "task"); put("key", "counter") })
        assertTrue(get.ok)
        assertEquals(42, (get.result as JsonObject)["value"]?.let { (it as JsonPrimitive).content?.toInt() })
    }

    @Test
    fun `given missing key when get then empty result not error`() = runTest {
        val get = provider.invoke(ctx, "get", buildJsonObject { put("scope", "profile"); put("key", "nope") })
        assertTrue(get.ok)
        assertNull((get.result as? JsonObject)?.get("value"))
    }

    @Test
    fun `given task scope and profile scope then owners differ`() = runTest {
        provider.invoke(ctx, "set", buildJsonObject { put("scope", "task"); put("key", "k"); put("value", "task-value") })
        provider.invoke(ctx, "set", buildJsonObject { put("scope", "profile"); put("key", "k"); put("value", "profile-value") })
        val taskGet = provider.invoke(ctx, "get", buildJsonObject { put("scope", "task"); put("key", "k") })
        val profileGet = provider.invoke(ctx, "get", buildJsonObject { put("scope", "profile"); put("key", "k") })
        assertEquals("task-value", ((taskGet.result as JsonObject)["value"] as JsonPrimitive).content)
        assertEquals("profile-value", ((profileGet.result as JsonObject)["value"] as JsonPrimitive).content)
    }

    @Test
    fun `given list and delete then keys managed`() = runTest {
        provider.invoke(ctx, "set", buildJsonObject { put("scope", "task"); put("key", "a.1"); put("value", 1) })
        provider.invoke(ctx, "set", buildJsonObject { put("scope", "task"); put("key", "a.2"); put("value", 2) })
        val list = provider.invoke(ctx, "list", buildJsonObject { put("scope", "task"); put("prefix", "a.") })
        val keys = ((list.result as JsonObject)["keys"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("a.1", "a.2"), keys)
        val del = provider.invoke(ctx, "delete", buildJsonObject { put("scope", "task"); put("key", "a.1") })
        assertEquals(true, ((del.result as JsonObject)["deleted"] as JsonPrimitive).content.toBooleanStrict())
        val get = provider.invoke(ctx, "get", buildJsonObject { put("scope", "task"); put("key", "a.1") })
        assertNull((get.result as? JsonObject)?.get("value"))
        assertNotNull(backend.get("task:task-1", "a.2"))
    }
}
