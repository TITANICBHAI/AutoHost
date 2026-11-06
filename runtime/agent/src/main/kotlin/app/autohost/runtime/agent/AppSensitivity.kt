package app.autohost.runtime.agent

// Adapted from closepaw tool/AppClassifier.kt (Apache-2.0, Copyright 2026 ClosePaw Authors).
// Changes: org.json replaced by kotlinx.serialization, android.util.Log removed (:runtime:agent
// is pure JVM), classify() pins the BLOCKED floor at write time AND at read time, tiers
// re-declared as AppTier (docs/REUSE_MATRIX.md RM-B1, P13-08).

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** App sensitivity tiers (AGENT_RUNTIME §8): BLOCKED content never reaches the model. */
enum class AppTier { BLOCKED, CAUTIOUS, NORMAL }

/**
 * Package → tier. The bundled resource is a FLOOR: entries can be added by the user, never
 * removed (pinned in [classify] too — removal is refused at read time, not just at write time).
 * Unknown packages default to CAUTIOUS (fail safe, per RM-B1).
 */
class AppSensitivity(
    userAdded: Map<String, AppTier> = emptyMap(),
    bundledText: String? = null, // test seam: overrides the bundled resource (fail-closed port, RM-G2)
) {

    private val bundled: Map<String, AppTier> = loadBundled(bundledText)

    /** The user's additions — BLOCKED additions are allowed; bundled entries can never be lifted. */
    private val effective: Map<String, AppTier> = run {
        val merged = bundled.toMutableMap()
        for ((pkg, tier) in userAdded) {
            if (bundled[pkg] == AppTier.BLOCKED) continue // floor: a bundled BLOCKED stays BLOCKED
            merged[pkg] = tier
        }
        merged
    }

    fun classify(pkg: String): AppTier = effective[pkg] ?: AppTier.CAUTIOUS

    /** The observation floor: BLOCKED content is withheld wherever it appears. */
    fun isBlocked(pkg: String): Boolean = classify(pkg) == AppTier.BLOCKED

    private fun loadBundled(override: String?): Map<String, AppTier> {
        // Fail closed (the RM-G2 port of AppClassifierSecurityTest's load-failure regression):
        // a corrupt floor must THROW at construction — the classifier never silently degrades
        // into "everything is NORMAL".
        val text = override ?: javaClass.classLoader!!
            .getResourceAsStream(BUNDLED_PATH)!!
            .readBytes().decodeToString()
        val root = Json.parseToJsonElement(text).jsonObject
        val apps = root["apps"]!!.jsonArray
        val map = LinkedHashMap<String, AppTier>()
        for (entry in apps) {
            val obj = entry.jsonObject
            val pkg = obj["package"]!!.jsonPrimitive.content
            val tier = runCatching {
                AppTier.valueOf(obj["tier"]!!.jsonPrimitive.content)
            }.getOrDefault(AppTier.CAUTIOUS)
            map[pkg] = tier
        }
        return map
    }

    companion object {
        const val BUNDLED_PATH = "autohost/app_sensitivity.json"
    }
}
