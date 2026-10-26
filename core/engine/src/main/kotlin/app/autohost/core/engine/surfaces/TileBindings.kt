package app.autohost.core.engine.surfaces

/**
 * P7-07: bindings for the five generic Quick Settings tiles ("AutoHost 1–5", plan §10.3). A binding
 * points a tile at a task and optionally a profile; the tile starts that run with the `surface`
 * trigger. Storage format is a small JSON object per tile slot, kept in `SurfacesPrefs` (app side);
 * parsing/validation lives here so it is unit-testable without Android.
 *
 * A binding may also carry an optional `stage` (run_stage) for tasks that expose stages.
 */
object TileBindings {

    const val TILE_COUNT = 5

    data class Binding(
        val taskId: String,
        val profileId: String? = null,
        val stage: String? = null,
    )

    data class Parsed(val binding: Binding?, val error: String? = null)

    fun parse(slot: Int, json: String?): Parsed {
        if (json.isNullOrBlank()) return Parsed(null)
        val element = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(json)
        }.getOrElse { return Parsed(null, "tile $slot: not JSON") }
        if (element !is kotlinx.serialization.json.JsonObject) return Parsed(null, "tile $slot: not an object")
        val taskId = (element["taskId"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
        if (taskId.isEmpty()) return Parsed(null, "tile $slot: missing taskId")
        if (!taskId.matches(TASK_ID_PATTERN)) return Parsed(null, "tile $slot: taskId not identifier-shaped")
        val profileId = (element["profileId"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()
            ?.takeIf { it.isNotEmpty() }
        val stage = (element["stage"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (stage != null && !stage.matches(TASK_ID_PATTERN)) return Parsed(null, "tile $slot: stage not identifier-shaped")
        return Parsed(Binding(taskId, profileId, stage))
    }

    fun format(binding: Binding): String = buildString {
        append("{\"taskId\":\"")
        append(binding.taskId.replace("\"", ""))
        append("\"")
        if (!binding.profileId.isNullOrEmpty()) append(",\"profileId\":\"").append(binding.profileId.replace("\"", "")).append("\"")
        if (!binding.stage.isNullOrEmpty()) append(",\"stage\":\"").append(binding.stage.replace("\"", "")).append("\"")
        append("}")
    }

    /** Key in `SurfacesPrefs` for one tile slot (1-based). */
    fun prefKey(slot: Int): String {
        require(slot in 1..TILE_COUNT) { "tile slot out of range: $slot" }
        return "tile.$slot.binding"
    }

    private val TASK_ID_PATTERN = Regex("[a-zA-Z][a-zA-Z0-9._-]*")
}
