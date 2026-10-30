package app.autohost.core.engine.tasks

import app.autohost.core.common.AhJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * P2-12: resolves manifest requirements against this device and produces the CompatReport (plan §15):
 * per requirement — capabilities (AVAILABLE / NEEDS_SETUP / UNSUPPORTED / DENIED), required apps
 * (installed? version?), API level — plus the active provider in each fallback chain when the catalog
 * names one. A task with an unmet required item cannot start; the UI shows the fix or the reason.
 *
 * Android wiring (`:app`) provides the [Probe]; the resolver itself is pure and unit-testable.
 */
fun interface CompatChecker {
    fun check(manifest: app.autohost.core.model.TaskManifest, configSchema: JsonObject?): List<ImportReport.CompatRow>
}

/** What the resolver needs to know about the live device. */
interface DeviceProbe {
    val sdkInt: Int

    /** null = not installed. */
    fun appInfo(packageName: String): InstalledApp?

    /** Live availability of a capability id (null if the host has no provider for it at all). */
    fun availabilityOf(capabilityId: String): app.autohost.core.model.Availability?

    /** Enabled insecure-feature switch for the capability family, if one gates it (plan §14.5). */
    fun switchEnabled(family: String): Boolean?

    data class InstalledApp(val packageName: String, val versionCode: Long?, val versionName: String?)
}

class CapabilityResolver(
    private val catalog: CapabilityCatalog,
    private val probe: DeviceProbe,
) : CompatChecker {

    override fun check(
        manifest: app.autohost.core.model.TaskManifest,
        configSchema: JsonObject?,
    ): List<ImportReport.CompatRow> {
        val rows = ArrayList<ImportReport.CompatRow>()
        val config = manifestConfig(configSchema)

        fun capabilityRow(id: String, required: Boolean) {
            val spec = catalog.capability(id)
            when {
                spec == null -> rows += ImportReport.CompatRow(
                    kind = "capability",
                    id = id,
                    status = "UNSUPPORTED",
                    required = required,
                    detail = "host has no such capability",
                )
                spec.minSdk > probe.sdkInt -> rows += ImportReport.CompatRow(
                    kind = "capability",
                    id = id,
                    status = "UNSUPPORTED",
                    required = required,
                    detail = "requires API ${spec.minSdk}, device is ${probe.sdkInt}",
                )
                else -> {
                    // P8-13: the switch row uses the FEATURE id (plan §14.5), not the capability
                    // family — device.ui and device.shell share the "device" prefix but only
                    // device.shell has a switch. The mapping mirrors InsecureFeatures.featureFor.
                    val feature = switchFeatureFor(id)
                    val switch = feature?.let { probe.switchEnabled(it) }
                    if (switch == false) {
                        rows += ImportReport.CompatRow(
                            kind = "capability",
                            id = id,
                            status = "DENIED",
                            required = required,
                            detail = "disabled by the global \"$feature\" switch (Settings → Developer & security)",
                            fixAction = "OPEN_INSECURE_SWITCH",
                        )
                        return
                    }
                    when (val availability = probe.availabilityOf(id)) {
                        null -> rows += ImportReport.CompatRow(
                            kind = "capability",
                            id = id,
                            status = "UNSUPPORTED",
                            required = required,
                            detail = "no provider is registered for it on this device",
                        )
                        is app.autohost.core.model.Availability.Available -> rows += ImportReport.CompatRow(
                            kind = "capability",
                            id = id,
                            status = "AVAILABLE",
                            required = required,
                            detail = scopeSummary(id, manifest, config),
                        )
                        is app.autohost.core.model.Availability.NeedsSetup -> rows += ImportReport.CompatRow(
                            kind = "capability",
                            id = id,
                            status = "NEEDS_SETUP",
                            required = required,
                            detail = availability.reason,
                            fixAction = availability.fixAction,
                        )
                        is app.autohost.core.model.Availability.Unsupported -> rows += ImportReport.CompatRow(
                            kind = "capability",
                            id = id,
                            status = "UNSUPPORTED",
                            required = required,
                            detail = availability.reason,
                        )
                    }
                }
            }
        }

        manifest.needs.capabilities.forEach { capabilityRow(it.id, required = true) }
        manifest.needs.optional.forEach { capabilityRow(it.id, required = false) }

        for (app in manifest.needs.apps) {
            // package may be a ${config.*} placeholder; unresolved stays literal and simply won't be installed
            val packageName = resolvePackage(app.packageName, config)
            val info = probe.appInfo(packageName)
            val minVersionCode = app.minVersionCode?.toLong()
            rows += when {
                info == null -> ImportReport.CompatRow(
                    kind = "app",
                    id = packageName,
                    status = "MISSING",
                    required = true,
                    detail = "not installed",
                    fixAction = "OPEN_STORE",
                )
                minVersionCode != null && (info.versionCode ?: 0L) < minVersionCode -> ImportReport.CompatRow(
                    kind = "app",
                    id = packageName,
                    status = "NEEDS_SETUP",
                    required = true,
                    detail = "installed version ${info.versionCode} is below required $minVersionCode",
                    fixAction = "OPEN_STORE",
                )
                else -> ImportReport.CompatRow(
                    kind = "app",
                    id = packageName,
                    status = "AVAILABLE",
                    required = true,
                    detail = info.versionName?.let { "installed $it" } ?: "installed",
                )
            }
        }
        return rows
    }

    /** Capability id → insecure-feature switch id (plan §14.5); null = no switch gates it. */
    private fun switchFeatureFor(capabilityId: String): String? = when (capabilityId) {
        "device.shell" -> "shell"
        "android.api.direct" -> "raw_api"
        "files.all" -> "all_files"
        "device.sms" -> "sms"
        "device.telephony" -> "telephony"
        "device.logcat" -> "logcat"
        "device.location.mock" -> "mock_location"
        else -> null
    }

    /** True when every required row is satisfied — the orchestrator consults this before enqueueing a run. */
    fun canRun(rows: List<ImportReport.CompatRow>): Boolean =
        rows.none { it.required && it.status !in setOf("AVAILABLE") }

    private fun scopeSummary(capabilityId: String, manifest: app.autohost.core.model.TaskManifest, config: JsonObject): String {
        val need = (manifest.needs.capabilities + manifest.needs.optional).firstOrNull { it.id == capabilityId }
            ?: return "no scope"
        val scope = ScopeTemplating.apply(need.scope ?: JsonObject(emptyMap()), config, JsonObject(emptyMap()))
        val packages = (scope["packages"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
        return when {
            packages == null -> "unrestricted scope"
            packages.isEmpty() -> "no packages granted"
            else -> "only in ${packages.joinToString(", ")}"
        }
    }

    private fun resolvePackage(spec: String, config: JsonObject): String {
        val match = Regex("^\\$\\{config\\.([A-Za-z0-9_.]+)}$").find(spec) ?: return spec
        val path = match.groupValues[1]
        var current: kotlinx.serialization.json.JsonElement = config
        for (segment in path.split('.')) {
            current = (current as? JsonObject)?.get(segment) ?: return spec
        }
        return (current as? JsonPrimitive)?.content ?: spec
    }

    /** Merge schema defaults into a config object so `${config.x}` resolves for compat display. */
    private fun manifestConfig(configSchema: JsonObject?): JsonObject {
        val properties = (configSchema?.get("properties") as? JsonObject) ?: return JsonObject(emptyMap())
        val values = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for ((name, spec) in properties) {
            val default = (spec as? JsonObject)?.get("default")
            if (default != null) values[name] = default
        }
        return JsonObject(values)
    }
}

/** Minimal capability catalogue parsed from `schemas/capability-specs.w1.json` (bundled as a JVM resource). */
class CapabilityCatalog private constructor(
    private val byId: Map<String, Spec>,
) {
    data class Spec(
        val id: String,
        val minSdk: Int,
        val summary: String,
        val permissions: List<String>,
        val androidAccess: List<String>,
        val methods: List<String>,
    )

    fun capability(id: String): Spec? = byId[id]
    fun all(): Collection<Spec> = byId.values

    companion object {
        fun parse(jsonText: String): CapabilityCatalog {
            val root = AhJson.json.parseToJsonElement(jsonText).jsonObject
            val capabilities = root["capabilities"] as? JsonArray ?: JsonArray(emptyList())
            val specs = LinkedHashMap<String, Spec>()
            for (element in capabilities) {
                val cap = element as? JsonObject ?: continue
                val id = (cap["id"] as? JsonPrimitive)?.content ?: continue
                specs[id] = Spec(
                    id = id,
                    minSdk = (cap["minSdk"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 29,
                    summary = (cap["summary"] as? JsonPrimitive)?.content ?: "",
                    permissions = (cap["permissions"] as? JsonArray)
                        ?.mapNotNull { entry -> ((entry as? JsonObject)?.get("id") as? JsonPrimitive)?.content }
                        ?: emptyList(),
                    androidAccess = (cap["androidAccess"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                    methods = (cap["methods"] as? JsonArray)
                        ?.mapNotNull { entry -> ((entry as? JsonObject)?.get("name") as? JsonPrimitive)?.content }
                        ?: emptyList(),
                )
            }
            return CapabilityCatalog(specs)
        }

        fun loadFromResources(classLoader: ClassLoader): CapabilityCatalog {
            val stream = classLoader.getResourceAsStream(RESOURCE_PATH)
                ?: error("Missing bundled capability specs resource: $RESOURCE_PATH")
            return parse(stream.use { it.readBytes().decodeToString() })
        }

        const val RESOURCE_PATH = "autohost/capability-specs-w1.json"
    }
}
