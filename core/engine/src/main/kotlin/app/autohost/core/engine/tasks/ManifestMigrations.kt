package app.autohost.core.engine.tasks

import app.autohost.core.common.AhJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * P2-05: manifest schema migrations (plan §8.5) + semver comparison + removal policy.
 *
 * The host ships a chain of pure migration functions vN→vN+1. Host version is [ManifestValidator.HOST_SCHEMA_VERSION];
 * a manifest newer than the host is rejected with HOST_TOO_OLD (never guessed at). Migrations are pure: JSON in,
 * JSON out, no IO, deterministic — so they are trivially testable and safe to run on import *and* on host upgrade.
 */
object ManifestMigrations {

    /** Migrations keyed by the version they upgrade *from*. Registry grows as the schema evolves. */
    private val chain: Map<Int, (JsonObject) -> JsonObject> = emptyMap()
    // Example for a future v2 (kept here as the documented shape, not active code):
    //   1 to { old -> JsonObject(old + mapOf("host_api" to JsonPrimitive(">=1 <2"))) },

    fun migrate(text: String): Pair<JsonObject, List<String>> {
        val root = try {
            AhJson.json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: error("manifest is not a JSON object")
        val from = (root["schema"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 1
        if (from > ManifestValidator.HOST_SCHEMA_VERSION) {
            throw ManifestValidator.Issue("/schema", "HOST_TOO_OLD", "").let {
                MigrationException("manifest schema $from is newer than host ${ManifestValidator.HOST_SCHEMA_VERSION}")
            }
        }
        var current = root
        val applied = ArrayList<String>()
        var version = from
        while (version < ManifestValidator.HOST_SCHEMA_VERSION) {
            val step = chain[version]
                ?: throw MigrationException("no migration registered from schema $version to ${version + 1}")
            current = step(current)
            version += 1
            applied += "v$version"
            // keep the schema counter honest after each step
            current = JsonObject(current + mapOf("schema" to kotlinx.serialization.json.JsonPrimitive(version)))
        }
        return current to applied
    }

    class MigrationException(message: String) : Exception(message)
}

/** Strict semver ordering for the versions the manifest pattern allows (MAJOR.MINOR.PATCH[-pre][+build]). */
object SemVer {
    /** Negative if a < b, positive if a > b, 0 if equal. Pre-release sorts *before* the release (semver §11). */
    fun compare(a: String, b: String): Int {
        val (coreA, preA) = split(a)
        val (coreB, preB) = split(b)
        for (i in 0 until 3) {
            val c = coreA[i].compareTo(coreB[i])
            if (c != 0) return c
        }
        if (preA == null && preB == null) return 0
        if (preA == null) return 1
        if (preB == null) return -1
        val partsA = preA.split('.')
        val partsB = preB.split('.')
        for (i in 0 until maxOf(partsA.size, partsB.size)) {
            val pa = partsA.getOrNull(i)
            val pb = partsB.getOrNull(i)
            if (pa == null) return -1 // shorter pre-release wins
            if (pb == null) return 1
            val na = pa.toLongOrNull()
            val nb = pb.toLongOrNull()
            val c = when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1 // numeric identifiers always lower than alphanumeric
                nb != null -> 1
                else -> pa.compareTo(pb)
            }
            if (c != 0) return c
        }
        return 0
    }

    private fun split(v: String): Pair<List<Long>, String?> {
        val noBuild = v.substringBefore('+')
        val core = noBuild.substringBefore('-')
        val pre = noBuild.substringAfter('-', "").ifEmpty { null }
        val numbers = core.split('.').map { it.toLong() }
        return numbers to pre
    }
}

/**
 * Removal policy (plan §8.4): confirm is the caller's job; this executes — delete bundle dirs, revoke grants,
 * cancel schedules (caller notifies the scheduler through [onBeforeDelete]), optionally keep run history.
 */
class TaskRemover(
    private val tasksRoot: File,
    private val onBeforeDelete: (taskId: String) -> Unit = {},
) {
    data class Removed(val bundleDirs: Int, val historyKept: Boolean)

    fun remove(taskId: String, keepHistory: Boolean): Removed {
        onBeforeDelete(taskId)
        val taskRoot = File(tasksRoot, taskId)
        var dirs = 0
        if (taskRoot.exists()) {
            dirs = taskRoot.walkBottomUp().count { it.isDirectory }
            taskRoot.deleteRecursively()
        }
        return Removed(bundleDirs = dirs, historyKept = keepHistory)
    }
}
