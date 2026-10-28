package app.autohost.core.broker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Index of the wave-1 capability specs (`schemas/capability-specs.w1.json`, bundled as a JVM resource so the
 * broker can map task needs → permissions without Android resources). Knows:
 *  - which ids are permissions vs capability ids,
 *  - which permissions belong to which capability,
 *  - which permission each method requires is *not* decided here (providers decide per call).
 */
class PermissionIndex private constructor(
    private val permissionToCapability: Map<String, String>,
    private val capabilityToPermissions: Map<String, List<String>>,
) {
    fun isPermission(id: String): Boolean = permissionToCapability.containsKey(id)

    fun isCapability(id: String): Boolean = capabilityToPermissions.containsKey(id)

    fun permissionsOf(capabilityId: String): List<String> = capabilityToPermissions[capabilityId] ?: emptyList()

    fun capabilityOf(permissionId: String): String? = permissionToCapability[permissionId]

    fun allPermissions(): Set<String> = permissionToCapability.keys

    /**
     * P11-02 (ADR-079): a new immutable index with the plugin fragment layered on top. Existing
     * host mappings always win (a plugin can never shadow a host capability or permission);
     * collisions inside the fragment keep the FIRST entry, exactly like index parsing.
     */
    fun mergedWith(fragment: Map<String, List<String>>): PermissionIndex {
        if (fragment.isEmpty()) return this
        val capabilityToPermissions = LinkedHashMap(capabilityToPermissions)
        val permissionToCapability = LinkedHashMap(permissionToCapability)
        for ((capabilityId, permissions) in fragment) {
            if (capabilityToPermissions.containsKey(capabilityId)) continue // host wins
            capabilityToPermissions[capabilityId] = permissions
            for (permission in permissions) {
                permissionToCapability.putIfAbsent(permission, capabilityId)
            }
        }
        return PermissionIndex(permissionToCapability, capabilityToPermissions)
    }

    companion object {
        fun parse(jsonText: String): PermissionIndex {
            val root = try {
                kotlinx.serialization.json.Json.parseToJsonElement(jsonText) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: throw IllegalArgumentException("capability specs JSON is malformed")
            val capabilities = root["capabilities"] as? JsonArray ?: JsonArray(emptyList())
            val permissionToCapability = LinkedHashMap<String, String>()
            val capabilityToPermissions = LinkedHashMap<String, List<String>>()
            for (element in capabilities) {
                val cap = element as? JsonObject ?: continue
                val capId = (cap["id"] as? JsonPrimitive)?.content ?: continue
                val perms = (cap["permissions"] as? JsonArray).orEmpty()
                    .mapNotNull { entry -> ((entry as? JsonObject)?.get("id") as? JsonPrimitive)?.content }
                capabilityToPermissions[capId] = perms
                for (perm in perms) permissionToCapability[perm] = capId
            }
            return PermissionIndex(permissionToCapability, capabilityToPermissions)
        }

        fun loadFromResources(classLoader: ClassLoader): PermissionIndex {
            val resource = classLoader.getResourceAsStream(RESOURCE_PATH)
                ?: error("Missing bundled capability specs resource: $RESOURCE_PATH")
            return resource.use { parse(it.readBytes().decodeToString()) }
        }

        const val RESOURCE_PATH = "autohost/capability-specs-w1.json"
    }
}
