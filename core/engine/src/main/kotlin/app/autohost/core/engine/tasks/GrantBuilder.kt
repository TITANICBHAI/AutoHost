package app.autohost.core.engine.tasks

import app.autohost.core.broker.PermissionIndex
import app.autohost.core.common.AhLog
import app.autohost.core.model.CapabilityNeed
import app.autohost.core.model.TaskManifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the grant map a run receives (permission id → scope object) from the task manifest's needs
 * (HANDOFF §3). Implicit host grants are always present; dev-mode grant-all stands in for the Phase 2
 * permission review until that exists.
 */
class GrantBuilder(
    private val index: PermissionIndex,
    private val devMode: Boolean = true,
) {

    fun build(manifest: TaskManifest, config: JsonObject, profile: JsonObject): Map<String, JsonObject> {
        val grants = LinkedHashMap<String, JsonObject>()

        fun grant(need: CapabilityNeed) {
            val scope = ScopeTemplating.apply(need.scope ?: buildJsonObject(), config, profile)
            when {
                index.isPermission(need.id) -> grants[need.id] = scope
                index.isCapability(need.id) ->
                    for (permission in index.permissionsOf(need.id)) grants[permission] = scope
                else -> AhLog.w(
                    TAG,
                    "manifest asks for unknown capability or permission; ignored (fail closed)",
                    buildJsonObject { put("id", need.id) },
                )
            }
        }

        manifest.needs.capabilities.forEach(::grant)
        manifest.needs.optional.forEach(::grant)

        // Implicit grants for every run (HANDOFF §3) — never narrower than what the task asked for.
        for (permission in IMPLICIT) grants.putIfAbsent(permission, buildJsonObject())

        // Dev-mode grant-all until the Phase 2 permission review exists. Explicit task scopes stay intact.
        if (devMode) {
            for (permission in index.allPermissions()) grants.putIfAbsent(permission, buildJsonObject())
        }
        return grants
    }

    companion object {
        private const val TAG = "grants"
        val IMPLICIT = listOf("store.profile", "store.task", "state.checkpoint", "secrets.read", "system.info")
    }
}
