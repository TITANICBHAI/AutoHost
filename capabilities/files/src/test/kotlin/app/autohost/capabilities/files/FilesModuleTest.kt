package app.autohost.capabilities.files

import app.autohost.core.broker.CallContext
import app.autohost.core.common.FakeClock
import app.autohost.core.model.ErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** P8-07: SAF tree scope + relative-path guard + media gating (plan §6.1 W2). */
class SharedFilesPolicyTest {

    private val grant = buildJsonObject {
        put("trees", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("content://com.android.providers.downloads.documents/tree/raw%3A1"))
        })
        put("media", true)
    }

    @Test
    fun `tree must be in the grant verbatim`() {
        assertEquals(
            SharedFilesPolicy.TreeVerdict.Allowed::class,
            SharedFilesPolicy.treeVerdict(grant, "content://com.android.providers.downloads.documents/tree/raw%3A1")::class,
        )
        assertEquals(
            SharedFilesPolicy.TreeVerdict.NotGranted,
            SharedFilesPolicy.treeVerdict(grant, "content://other/tree/x"),
        )
        assertEquals(SharedFilesPolicy.TreeVerdict.NoGrant, SharedFilesPolicy.treeVerdict(null, "content://x"))
        assertEquals(SharedFilesPolicy.TreeVerdict.NotGranted, SharedFilesPolicy.treeVerdict(grant, ""))
    }

    @Test
    fun `relative paths reject escaping and absolute forms`() {
        assertEquals(emptyList<String>(), SharedFilesPolicy.safeRelative(""))
        assertEquals(listOf("a", "b.txt"), SharedFilesPolicy.safeRelative("a/b.txt"))
        assertEquals(null, SharedFilesPolicy.safeRelative("../secret"))
        assertEquals(null, SharedFilesPolicy.safeRelative("/abs"))
        assertEquals(null, SharedFilesPolicy.safeRelative("a//../../b"))
        assertEquals(null, SharedFilesPolicy.safeRelative("with\\backslash"))
    }

    @Test
    fun `media scope flag gates media ops`() {
        assertTrue(SharedFilesPolicy.mediaAllowed(grant))
        assertTrue(!SharedFilesPolicy.mediaAllowed(buildJsonObject { put("trees", kotlinx.serialization.json.buildJsonArray { }) }))
        assertTrue(!SharedFilesPolicy.mediaAllowed(null))
    }

    @Test
    fun `read plan truncates over the cap`() {
        assertEquals(SharedFilesPolicy.ReadPlan.Fit::class, SharedFilesPolicy.readPlan(10)::class)
        assertEquals(SharedFilesPolicy.MAX_TRANSFER_BYTES, (SharedFilesPolicy.readPlan(10_000_000) as SharedFilesPolicy.ReadPlan.Truncated).bytes)
    }
}

/** P8-07: provider behaviour over a fake SAF/MediaStore seam. */
class FilesSharedProviderTest {

    private val clock = FakeClock()
    private val grant = buildJsonObject {
        put("trees", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("content://tree/one"))
        })
        put("media", true)
    }

    private fun ctx(grants: Map<String, JsonObject>) = CallContext(
        runId = "r", taskId = "t", profileId = null, grants = grants, nowMs = clock.nowMs(),
    )

    private class FakeSystem : FilesSharedProvider.System {
        val files = HashMap<String, ByteArray>()
        val entries = ArrayList<FilesSharedProvider.System.Entry>()
        var mediaReadPermission = true
        override fun listChildren(treeUri: String, segments: List<String>) = entries
        override fun read(treeUri: String, segments: List<String>) = files[segments.joinToString("/") { it }]
        override fun write(treeUri: String, segments: List<String>, bytes: ByteArray): Boolean {
            files[segments.joinToString("/") { it }] = bytes
            return true
        }
        override fun delete(treeUri: String, segments: List<String>): Boolean =
            files.remove(segments.joinToString("/") { it }) != null
        override fun mediaList(mediaType: String, limit: Int) = emptyList<FilesSharedProvider.System.MediaRow>()
        override fun mediaRead(id: String) = files["media:$id"]
        override fun mediaWrite(name: String, bytes: ByteArray, mediaType: String): String {
            files["media:101"] = bytes
            return "101"
        }
        override fun mediaDelete(id: String): Boolean = files.remove("media:$id") != null
        override fun hasMediaReadPermission() = mediaReadPermission
    }

    @Test
    fun `read and write round trip inside a granted tree`() = runBlocking {
        val system = FakeSystem()
        val provider = FilesSharedProvider(system)
        val args = buildJsonObject {
            put("tree", "content://tree/one")
            put("path", "sub/data.txt")
            put("body_b64", java.util.Base64.getEncoder().encodeToString("hello".encodeToByteArray()))
        }
        val written = provider.invoke(ctx(mapOf("files.shared" to grant)), "write", args)
        assertEquals(true, written.ok)
        assertEquals("hello", system.files["sub/data.txt"].toString(Charsets.UTF_8))
        val read = provider.invoke(ctx(mapOf("files.shared" to grant)), "read", buildJsonObject {
            put("tree", "content://tree/one")
            put("path", "sub/data.txt")
        })
        assertEquals(true, read.ok)
        assertEquals(
            "hello",
            String(java.util.Base64.getDecoder().decode((read.result as JsonObject)["body_b64"]!!.jsonPrimitive.content)),
        )
    }

    @Test
    fun `ung tree is a scope violation with the folder fix`() = runBlocking {
        val provider = FilesSharedProvider(FakeSystem())
        val result = provider.invoke(ctx(mapOf("files.shared" to grant)), "read", buildJsonObject {
            put("tree", "content://tree/NOT_GRANTED")
            put("path", "a.txt")
        })
        assertEquals(false, result.ok)
        assertEquals(ErrorCode.SCOPE_VIOLATION.name, result.error?.code)
        assertEquals("PICK_FOLDER", result.error?.fixAction)
    }

    @Test
    fun `no grant at all is permission denied`() = runBlocking {
        val provider = FilesSharedProvider(FakeSystem())
        val result = provider.invoke(ctx(emptyMap()), "list", buildJsonObject { put("tree", "content://tree/one") })
        assertEquals(ErrorCode.PERMISSION_DENIED.name, result.error?.code)
    }

    @Test
    fun `media ops need the media scope and the runtime permission`() = runBlocking {
        val noMedia = buildJsonObject {
            put("trees", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("content://tree/one"))
            })
        }
        val provider = FilesSharedProvider(FakeSystem())
        val gated = provider.invoke(ctx(mapOf("files.shared" to noMedia)), "media_list", buildJsonObject { })
        assertEquals(ErrorCode.SCOPE_VIOLATION.name, gated.error?.code)

        val system = FakeSystem()
        system.mediaReadPermission = false
        val lazy = FilesSharedProvider(system)
        val needsSetup = lazy.invoke(ctx(mapOf("files.shared" to grant)), "media_list", buildJsonObject { })
        assertEquals(ErrorCode.NEEDS_SETUP.name, needsSetup.error?.code)
        assertTrue(needsSetup.error?.reason.orEmpty().startsWith("RUNTIME_PERMISSION"))
    }

    @Test
    fun `writes over the cap are rejected`() = runBlocking {
        val provider = FilesSharedProvider(FakeSystem())
        val big = ByteArray(SharedFilesPolicy.MAX_TRANSFER_BYTES + 1)
        val result = provider.invoke(ctx(mapOf("files.shared" to grant)), "write", buildJsonObject {
            put("tree", "content://tree/one")
            put("path", "big.bin")
            put("body_b64", java.util.Base64.getEncoder().encodeToString(big))
        })
        assertEquals(ErrorCode.INVALID_ARGS.name, result.error?.code)
    }
}

/** P8-07: the host-tier task-private provider (first-build gap closed). */
class FilesPrivateProviderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ctx() = CallContext(
        runId = "r", taskId = "task-1", profileId = null, grants = emptyMap(), nowMs = 0L,
    )

    @Test
    fun `write read list delete round trip`() = runBlocking {
        val root = tmp.newFolder("taskdata")
        val provider = FilesPrivateProvider { root }
        provider.invoke(ctx(), "write", buildJsonObject { put("path", "logs/a.txt"); put("content", "hello") })
        val read = provider.invoke(ctx(), "read", buildJsonObject { put("path", "logs/a.txt") })
        assertEquals("hello", (read.result as JsonObject)["content"]!!.jsonPrimitive.content)
        val list = provider.invoke(ctx(), "list", buildJsonObject { put("path", "logs") })
        val entries = ((list.result as JsonObject)["entries"] as kotlinx.serialization.json.JsonArray)
            .mapNotNull { (it as kotlinx.serialization.json.JsonPrimitive).content }
        assertEquals(listOf("a.txt"), entries)
        provider.invoke(ctx(), "delete", buildJsonObject { put("path", "logs/a.txt") })
        val gone = provider.invoke(ctx(), "read", buildJsonObject { put("path", "logs/a.txt") })
        assertEquals(ErrorCode.NOT_FOUND.name, gone.error?.code)
    }

    @Test
    fun `traversal out of the private folder is a scope violation`() = runBlocking {
        val root = tmp.newFolder("taskdata2")
        val provider = FilesPrivateProvider { root }
        val result = provider.invoke(ctx(), "write", buildJsonObject { put("path", "../../escape.txt"); put("content", "x") })
        assertEquals(ErrorCode.SCOPE_VIOLATION.name, result.error?.code)
        assertTrue(!File(root.parentFile, "escape.txt").exists())
    }

    @Test
    fun `tasks are isolated by taskId`() = runBlocking {
        val root = tmp.newFolder("taskdata-root")
        val provider = FilesPrivateProvider { taskId -> File(root, taskId) }
        provider.invoke(CallContext("r", "task-A", null, emptyMap(), 0), "write", buildJsonObject { put("path", "f.txt"); put("content", "A") })
        provider.invoke(CallContext("r", "task-B", null, emptyMap(), 0), "write", buildJsonObject { put("path", "f.txt"); put("content", "B") })
        val a = provider.invoke(CallContext("r", "task-A", null, emptyMap(), 0), "read", buildJsonObject { put("path", "f.txt") })
        assertEquals("A", (a.result as JsonObject)["content"]!!.jsonPrimitive.content)
        assertTrue(File(root, "task-A/f.txt").exists() && File(root, "task-B/f.txt").exists())
    }
}
