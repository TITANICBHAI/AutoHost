package app.autohost.packs

import android.content.Context
import app.autohost.core.common.AhLog
import app.autohost.core.common.Clock
import app.autohost.core.common.Ids
import app.autohost.core.engine.tasks.AppPacks
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.JsonObject

/**
 * P11-04 (ADR-080): installs, stores and resolves app packs (plan §7.10).
 *
 * An `.aapack` is a zip whose `pack.json` parses against the pure [AppPacks] contract — fail
 * closed, a malformed pack never lands. Packs live under `filesDir/packs/<id>/<version>/`; the
 * id is unique (a new version of the same id replaces the stored copy after the new parse
 * succeeds — the honest "fix ships once" path). At run start [injectPacks] resolves the task's
 * `uses_packs` and merges the payload into the run config under the reserved `packs` key, which
 * every runtime already receives (Python `ctx.config`, the JS prelude, flow `{{config.*}}`).
 */
class PackController(
    private val context: Context,
    private val clock: Clock,
) {

    private val root: File get() = File(context.filesDir, "packs")

    // ---- install / remove --------------------------------------------------------------------

    sealed class InstallOutcome {
        data class Installed(val pack: AppPacks.Pack, val dir: File) : InstallOutcome()
        data class Rejected(val issues: List<AppPacks.Issue>) : InstallOutcome()
    }

    /** Installs from `.aapack` bytes. The stored copy is only replaced after the new parse succeeds. */
    fun install(name: String, open: () -> java.io.InputStream): InstallOutcome {
        val staging = File(root, "import-${Ids.ulid(clock.nowMs())}")
        return try {
            staging.mkdirs()
            ZipInputStream(open()).use { zip ->
                var entries = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val target = File(staging, entry.name)
                    if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        return InstallOutcome.Rejected(
                            listOf(AppPacks.Issue("/${entry.name}", "PATH_ESCAPE", "zip entries must stay inside the pack")),
                        )
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                    entries += 1
                }
                if (entries == 0) {
                    return InstallOutcome.Rejected(listOf(AppPacks.Issue("/", "EMPTY", "the .aapack is empty")))
                }
            }
            val manifestFile = File(staging, PACK_MANIFEST)
            if (!manifestFile.isFile) {
                return InstallOutcome.Rejected(listOf(AppPacks.Issue("/", "NO_MANIFEST", "$PACK_MANIFEST missing")))
            }
            when (val parsed = AppPacks.parse(manifestFile.readText())) {
                else -> {
                    val pack = parsed.pack
                        ?: return InstallOutcome.Rejected(parsed.issues)
                    val destination = File(root, "${pack.id}/${pack.version}")
                    if (destination.exists()) destination.deleteRecursively()
                    destination.parentFile?.mkdirs()
                    if (!staging.renameTo(destination)) {
                        staging.copyRecursively(destination, overwrite = true)
                        staging.deleteRecursively()
                    }
                    AhLog.i(
                        TAG,
                        "app pack installed: ${pack.id}@${pack.version}",
                        payloadOf("pack", pack.id, "version", pack.version, "source", name),
                    )
                    InstallOutcome.Installed(pack, destination)
                }
            }
        } catch (e: Exception) {
            InstallOutcome.Rejected(listOf(AppPacks.Issue("/", "IO", "unreadable .aapack: ${e.message}")))
        } finally {
            if (staging.exists() && staging.name.startsWith("import-")) staging.deleteRecursively()
        }
    }

    fun remove(packId: String): Boolean {
        val dir = File(root, packId)
        val existed = dir.exists()
        dir.deleteRecursively()
        if (existed) AhLog.i(TAG, "app pack removed: $packId", payloadOf("pack", packId))
        return existed
    }

    // ---- listing / resolution ----------------------------------------------------------------

    data class PackInfo(val id: String, val version: String, val target: String, val title: String?)

    fun installedIds(): Set<String> = installed().keys

    fun installed(): Map<String, AppPacks.Pack> {
        val out = LinkedHashMap<String, AppPacks.Pack>()
        val rootDir = root
        if (!rootDir.isDirectory) return out
        for (packDir in rootDir.listFiles()?.filter { it.isDirectory }.orEmpty()) {
            // Highest installed version per id wins (dir names are strict semver — lexicographic
            // is safe within one id because semver sorts lexically only for equal-width parts;
            // parse-and-compare instead to stay honest).
            for (versionDir in packDir.listFiles()?.filter { it.isDirectory }.orEmpty()) {
                val manifestFile = File(versionDir, PACK_MANIFEST)
                if (!manifestFile.isFile) continue
                val pack = (AppPacks.parse(manifestFile.readText()) as? AppPacks.ParseResult)?.pack ?: continue
                val current = out[pack.id]
                if (current == null || compareVersions(pack.version, current.version) > 0) out[pack.id] = pack
            }
        }
        return out
    }

    fun list(): List<PackInfo> =
        installed().values.map { PackInfo(it.id, it.version, it.target, it.title) }.sortedBy { it.id }

    /**
     * The run-start merge: `uses_packs` → [AppPacks.configPayload] under the reserved key.
     * Missing packs are an honest warning (the run proceeds; the task's own error handling
     * decides what a missing pack means); a config key collision with a user's own `packs`
     * value is logged and the HOST value wins — the reserved key is not user surface.
     */
    fun injectPacks(manifest: app.autohost.core.model.TaskManifest, config: JsonObject): JsonObject {
        if (manifest.usesPacks.isEmpty()) return config
        val resolution = AppPacks.resolve(manifest.usesPacks, installed())
        for (missing in resolution.missing) {
            AhLog.w(TAG, "app pack missing at run start", payloadOf("pack", missing))
        }
        if (resolution.packs.isEmpty()) return config
        if (config.containsKey(AppPacks.CONFIG_KEY)) {
            AhLog.w(TAG, "config already has a '${AppPacks.CONFIG_KEY}' key; the host payload wins (reserved key)")
        }
        val merged = LinkedHashMap(config)
        merged[AppPacks.CONFIG_KEY] = AppPacks.configPayload(resolution)
        return JsonObject(merged)
    }

    private fun compareVersions(a: String, b: String): Int {
        val parse = { v: String -> v.substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 } }
        val (pa, pb) = parse(a) to parse(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val left = pa.getOrElse(i) { 0 }
            val right = pb.getOrElse(i) { 0 }
            if (left != right) return left - right
        }
        return 0
    }

    companion object {
        const val PACK_MANIFEST = "pack.json"
        const val PACK_EXTENSION = ".aapack"
        private const val TAG = "packs"
    }
}

/** Tiny structured-log payload helper so the call sites stay readable. */
private fun payloadOf(vararg pairs: String): kotlinx.serialization.json.JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        var i = 0
        while (i + 1 < pairs.size) {
            put(pairs[i], kotlinx.serialization.json.JsonPrimitive(pairs[i + 1]))
            i += 2
        }
    }
