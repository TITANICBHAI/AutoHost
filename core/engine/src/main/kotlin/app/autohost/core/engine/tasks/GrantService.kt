package app.autohost.core.engine.tasks

import app.autohost.core.broker.PermissionIndex
import app.autohost.core.model.CapabilityNeed
import app.autohost.core.model.TaskManifest
import app.autohost.core.common.AhJson
import kotlinx.serialization.json.JsonObject

/**
 * P2-10: the grant resolution half of the permission system (plan §14.3).
 *
 * [GrantBuilder] still builds per-run grant maps; this service is the *persisted* half:
 *  - [resolve] turns manifest needs + resolved config/profile into permission grants (explicit needs only —
 *    no dev-mode grant-all; the Permission Review decides),
 *  - [fingerprint] gives each permission a stable scope hash so the UI can detect "changed → will re-ask",
 *  - [diff] produces the dev-mode one-tap diff (added / removed / widened / narrowed) between the currently
 *    installed version and an update (plan §8.3),
 *  - [needsReview] says whether a re-review is due (new task, first install, or any diff).
 *
 * Widening rules for the `packages` scope (the only scope key v1 uses):
 *  - none → some packages (or "unrestricted") = widened;
 *  - some → a strict superset = widened; a strict subset = narrowed; disjoint = widened (new attack surface);
 *  - unrestricted → anything = narrowed.
 */
class GrantService(private val index: PermissionIndex) {

    /** One persisted grant row: the permission, its resolved scope, and the scope fingerprint. */
    data class GrantRecord(
        val permission: String,
        val capability: String,
        val scope: JsonObject,
        val fingerprint: String,
        val required: Boolean,
    )

    fun resolve(manifest: TaskManifest, config: JsonObject, profile: JsonObject): List<GrantRecord> {
        val records = ArrayList<GrantRecord>()

        fun add(need: CapabilityNeed, required: Boolean) {
            val scope = ScopeTemplating.apply(need.scope ?: JsonObject(emptyMap()), config, profile)
            when {
                index.isPermission(need.id) -> records += record(need.id, need.id, scope, required)
                index.isCapability(need.id) ->
                    for (permission in index.permissionsOf(need.id)) records += record(permission, need.id, scope, required)
                // unknown ids are the validator's job; resolution simply skips them (fail closed downstream)
            }
        }

        manifest.needs.capabilities.forEach { add(it, required = true) }
        manifest.needs.optional.forEach { add(it, required = false) }
        // implicit host grants are added by GrantBuilder at run time, not persisted as review rows
        return records
    }

    private fun record(permission: String, capability: String, scope: JsonObject, required: Boolean) =
        GrantRecord(permission, capability, scope, fingerprint(scope), required)

    fun fingerprint(scope: JsonObject): String {
        val canonical = scope.toString() // kotlinx JSON objects iterate in insertion order; scopes come from the manifest verbatim
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.encodeToByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Diff between the grants of the installed version (old, may be empty on first install) and an update. */
    fun diff(old: List<GrantRecord>, new: List<GrantRecord>): List<ImportReport.GrantDiffRow> {
        val rows = ArrayList<ImportReport.GrantDiffRow>()
        val oldByPermission = old.associateBy { it.permission }
        val newByPermission = new.associateBy { it.permission }

        for ((permission, record) in newByPermission) {
            val previous = oldByPermission[permission]
            when {
                previous == null -> rows += ImportReport.GrantDiffRow(
                    capability = record.capability,
                    change = ImportReport.GrantDiffRow.ADDED,
                    detail = "new permission on update",
                )
                previous.fingerprint != record.fingerprint -> {
                    val change = classifyScopeChange(previous.scope, record.scope)
                    rows += ImportReport.GrantDiffRow(capability = record.capability, change = change, detail = permission)
                }
            }
        }
        for ((permission, record) in oldByPermission) {
            if (newByPermission[permission] == null) {
                rows += ImportReport.GrantDiffRow(
                    capability = record.capability,
                    change = ImportReport.GrantDiffRow.REMOVED,
                    detail = permission,
                )
            }
        }
        return rows.sortedWith(compareBy<ImportReport.GrantDiffRow> { it.capability }.thenBy { it.change })
    }

    private fun classifyScopeChange(oldScope: JsonObject, newScope: JsonObject): String {
        val oldPackages = packagesOf(oldScope)
        val newPackages = packagesOf(newScope)
        return when {
            oldPackages == null -> ImportReport.GrantDiffRow.NARROWED // was unrestricted; anything else is narrower
            newPackages == null -> ImportReport.GrantDiffRow.WIDENED // became unrestricted
            else -> {
                val widened = newPackages.any { it !in oldPackages }
                when {
                    widened && newPackages.containsAll(oldPackages) -> ImportReport.GrantDiffRow.WIDENED
                    widened -> ImportReport.GrantDiffRow.WIDENED // disjoint or partial: still new surface
                    newPackages.size < oldPackages.size -> ImportReport.GrantDiffRow.NARROWED
                    else -> ImportReport.GrantDiffRow.NARROWED // same size, no additions → narrowed or equal
                }
            }
        }
    }

    private fun packagesOf(scope: JsonObject): Set<String>? {
        val element = scope["packages"] ?: return null
        return when (element) {
            is kotlinx.serialization.json.JsonArray ->
                element.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.toSet()
            is kotlinx.serialization.json.JsonPrimitive ->
                if (element.content == "*") null else setOf(element.content)
            else -> null
        }
    }

    /** True when the Permission Review must be (re)shown before install/run. */
    fun needsReview(old: List<GrantRecord>, new: List<GrantRecord>): Boolean =
        old.isEmpty() || diff(old, new).isNotEmpty()

    /** Serialize grant records for the DB (manifest-side JSON; TaskVersion/Grant rows carry this). */
    fun grantsJson(records: List<GrantRecord>): String =
        AhJson.json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(GrantRecordDto.serializer()),
            records.map { GrantRecordDto(it.permission, it.capability, it.scope, it.fingerprint, it.required) },
        )

    fun grantsFromJson(text: String): List<GrantRecord> = try {
        AhJson.json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(GrantRecordDto.serializer()),
            text,
        ).map { GrantRecord(it.permission, it.capability, it.scope, it.fingerprint, it.required) }
    } catch (e: Exception) {
        emptyList()
    }

    @kotlinx.serialization.Serializable
    private data class GrantRecordDto(
        val permission: String,
        val capability: String,
        val scope: JsonObject,
        val fingerprint: String,
        val required: Boolean,
    )
}
