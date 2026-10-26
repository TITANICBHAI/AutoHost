package app.autohost.core.engine.tasks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * P11-04 (ADR-080): app packs (plan §7.10) — the pure half.
 *
 * An `.aapack` bundle carries the brittle per-app knowledge one package needs — named selectors,
 * named Screen signatures, locale label variants and tips — so a fix ships once for every task
 * that references it (`uses_packs` in the task manifest). The runtime mechanism is deliberately
 * boring: at run start the host resolves the referenced packs and injects their content into the
 * run config under the reserved key `packs` (`config.packs.<pack-id>`), which every runtime
 * already receives (Python `ctx.config`, the JS prelude, flow `{{config.*}}`) — no new broker
 * surface, no file IO in workers, version-pinned per run.
 *
 * Pure: JSON in, decisions out, no IO. The install/store half is `app/packs/PackController.kt`.
 */
object AppPacks {

    const val SCHEMA = 1
    /** The reserved run-config key the resolved packs ride under (ADR-080). */
    const val CONFIG_KEY = "packs"
    const val MAX_TIPS = 50
    const val MAX_LABEL_LOCALES = 20

    private val PACK_ID = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9][a-z0-9-]*)+$")
    private val PACKAGE_NAME = Regex("^[a-z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private val SEMVER = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$")
    private val LOCALE_TAG = Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
    private val NAME_KEY = Regex("^[a-z][a-z0-9_]*$")
    private val TOP_KEYS = setOf("schema", "id", "target", "version", "title", "selectors", "screens", "labels", "tips")

    data class Pack(
        val id: String,
        val target: String,
        val version: String,
        val title: String?,
        val selectors: JsonObject,
        val screens: JsonObject,
        val labels: JsonObject, // locale → {key → text}
        val tips: List<String>,
    )

    data class Issue(val path: String, val code: String, val message: String)

    data class ParseResult(val pack: Pack?, val issues: List<Issue>) {
        val ok: Boolean get() = pack != null
    }

    /** Fail-closed parse: a malformed root yields `pack = null`; per-field problems land in issues. */
    fun parse(text: String): ParseResult {
        val root = try {
            kotlinx.serialization.json.Json.parseToJsonElement(text)
        } catch (e: Exception) {
            null
        }
        if (root !is JsonObject) {
            return ParseResult(null, listOf(Issue("/", "TYPE", "pack.json must be a JSON object")))
        }
        val issues = ArrayList<Issue>()
        for (key in root.keys) {
            if (key !in TOP_KEYS) issues += Issue("/$key", "UNKNOWN_KEY", "\"$key\" is not part of pack v1")
        }
        fun str(key: String): String? = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        val schema = (root["schema"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (schema != SCHEMA) issues += Issue("/schema", "SCHEMA", "\"schema\" must be $SCHEMA")
        val id = str("id")
        if (id == null) issues += Issue("/id", "REQUIRED", "pack needs an \"id\"")
        else if (!PACK_ID.matches(id)) issues += Issue("/id", "PATTERN", "\"$id\" is not a reverse-DNS pack id")
        val target = str("target")
        if (target == null) issues += Issue("/target", "REQUIRED", "pack needs a \"target\" (the android package it describes)")
        else if (!PACKAGE_NAME.matches(target)) issues += Issue("/target", "PATTERN", "\"$target\" is not an android package name")
        val version = str("version")
        if (version == null) issues += Issue("/version", "REQUIRED", "pack needs a \"version\" (semver)")
        else if (!SEMVER.matches(version)) issues += Issue("/version", "PATTERN", "\"$version\" is not strict semver")
        val title = str("title")
        val selectors = root["selectors"] as? JsonObject ?: JsonObject(emptyMap())
        val screens = root["screens"] as? JsonObject ?: JsonObject(emptyMap())
        if (root.containsKey("selectors") && root["selectors"] !is JsonObject) {
            issues += Issue("/selectors", "TYPE", "\"selectors\" must be an object of name → selector")
        }
        if (root.containsKey("screens") && root["screens"] !is JsonObject) {
            issues += Issue("/screens", "TYPE", "\"screens\" must be an object of name → signature")
        }
        for ((section, obj) in mapOf("selectors" to selectors, "screens" to screens)) {
            for (name in obj.keys) {
                if (!NAME_KEY.matches(name)) {
                    issues += Issue("/$section/$name", "PATTERN", "names must be snake_case identifiers")
                }
            }
        }
        val labels = root["labels"] as? JsonObject
        if (root.containsKey("labels") && labels == null) {
            issues += Issue("/labels", "TYPE", "\"labels\" must be an object of locale → {key → text}")
        }
        labels?.let { localeMap ->
            if (localeMap.size > MAX_LABEL_LOCALES) {
                issues += Issue("/labels", "MAX", "at most $MAX_LABEL_LOCALES locales")
            }
            for ((locale, entries) in localeMap) {
                if (!LOCALE_TAG.matches(locale)) {
                    issues += Issue("/labels/$locale", "PATTERN", "\"$locale\" is not a BCP-47 language tag")
                }
                if (entries !is JsonObject || entries.keys.any { !NAME_KEY.matches(it) }) {
                    issues += Issue("/labels/$locale", "TYPE", "label entries must be snake_case name → string")
                }
            }
        }
        val tips = (root["tips"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (root.containsKey("tips") && tips == null) {
            issues += Issue("/tips", "TYPE", "\"tips\" must be an array of strings")
        }
        if ((tips?.size ?: 0) > MAX_TIPS) issues += Issue("/tips", "MAX", "at most $MAX_TIPS tips")

        // Fail closed: anything beyond an unknown-key note blocks the install. (ADR-080)
        val blocking = issues.any { it.code != "UNKNOWN_KEY" }
        if (blocking) return ParseResult(null, issues)
        return ParseResult(
            Pack(
                id = id ?: return ParseResult(null, issues),
                target = target ?: return ParseResult(null, issues),
                version = version ?: return ParseResult(null, issues),
                title = title,
                selectors = selectors,
                screens = screens,
                labels = labels ?: JsonObject(emptyMap()),
                tips = tips ?: emptyList(),
            ),
            issues,
        )
    }

    // ---- resolution -------------------------------------------------------------------------

    data class Resolution(
        val packs: List<Pack>,
        val missing: List<String>,
    ) {
        val ok: Boolean get() = missing.isEmpty()
    }

    /** Resolves a task's `uses_packs` against the installed packs, in task order. */
    fun resolve(usesPacks: List<String>, installed: Map<String, Pack>): Resolution {
        val packs = ArrayList<Pack>()
        val missing = ArrayList<String>()
        for (name in usesPacks) {
            val found = installed[name]
            if (found == null) missing += name else packs += found
        }
        return Resolution(packs, missing)
    }

    /**
     * The run-config payload under [CONFIG_KEY]: pack id → `{target, version, selectors, screens,
     * labels, tips}`. Deterministic (task order), so flows and tasks read stable paths.
     */
    fun configPayload(resolution: Resolution): JsonObject = kotlinx.serialization.json.buildJsonObject {
        for (pack in resolution.packs) {
            put(pack.id, kotlinx.serialization.json.buildJsonObject {
                put("target", JsonPrimitive(pack.target))
                put("version", JsonPrimitive(pack.version))
                put("selectors", pack.selectors)
                put("screens", pack.screens)
                put("labels", pack.labels)
                put("tips", JsonArray(pack.tips.map { JsonPrimitive(it) }))
            })
        }
    }

    // ---- lookups (the Python/JS helper mirrors these — keep semantics identical) -------------

    /** A named selector from the pack, or null when absent (callers fail honestly). */
    fun selector(pack: Pack, name: String): JsonObject? = pack.selectors[name] as? JsonObject

    /** A named screen signature from the pack, or null when absent. */
    fun screen(pack: Pack, name: String): JsonObject? = pack.screens[name] as? JsonObject

    /**
     * A label with locale fallback: exact tag → language-only tag → "en" → the first locale with
     * the key (stable per pack), or null when nobody has it.
     */
    fun label(pack: Pack, key: String, locale: String?): String? {
        fun entry(localeTag: String): String? =
            ((pack.labels[localeTag] as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (locale != null) {
            entry(locale)?.let { return it }
            val language = locale.substringBefore('-')
            if (language != locale) entry(language)?.let { return it }
        }
        entry("en")?.let { return it }
        for ((_, entries) in pack.labels) {
            ((entries as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { return it }
        }
        return null
    }
}
