package app.autohost.runtime.agent

import app.autohost.core.llm.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * P13-09 (ADR-086 (2)): the model's tool surface — the GENERATED allowlist from
 * `tools/gen_tool_catalog.py` (byte-stable resource; `--check` gates drift). A tool not in the
 * catalog does not exist for the model; a method the broker knows but the catalog omits
 * (ui_tap, app_list, shell, sms, …) is unreachable by construction. Argument schemas are
 * whitelists too — the model never sees `strategy`, `retry_if_no_change` or `secret`.
 */
class ToolCatalog private constructor(private val tools: Map<String, Tool>) {

    data class Tool(
        val name: String,
        val description: String,
        /** null = a host tool (finish/todo_write/wait) — never dispatched to the broker. */
        val capability: String?,
        val method: String?,
        val permission: String?,
        val args: JsonObject,
        val specRisk: String?,
    )

    fun names(): Set<String> = tools.keys

    fun find(name: String): Tool? = tools[name]

    /** The typed tool list for `ChatRequest.tools` (P13-10's loop builds the request from this). */
    fun specs(): List<ToolSpec> = tools.values.sortedBy { it.name }.map { tool ->
        ToolSpec(name = tool.name, description = tool.description, parameters = tool.args)
    }

    /** The OpenAI `tools[]` array for the request (function specs with JSON-schema parameters). */
    fun wireSpecs(): JsonArray = JsonArray(
        tools.values.sortedBy { it.name }.map { tool ->
            kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("function"))
                put("function", kotlinx.serialization.json.buildJsonObject {
                    put("name", kotlinx.serialization.json.JsonPrimitive(tool.name))
                    put("description", kotlinx.serialization.json.JsonPrimitive(tool.description))
                    put("parameters", kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                        put("properties", tool.args)
                        put("additionalProperties", kotlinx.serialization.json.JsonPrimitive(false))
                    })
                })
            }
        },
    )

    companion object {
        const val RESOURCE = "autohost/tool_catalog.json"

        fun load(): ToolCatalog {
            val text = javaClass.classLoader!!.getResourceAsStream(RESOURCE)!!
                .readBytes().decodeToString()
            val root = Json.parseToJsonElement(text).jsonObject
            val entries = root["tools"]!!.jsonArray
            val map = LinkedHashMap<String, Tool>()
            for (e in entries) {
                val obj = e.jsonObject
                val name = obj["name"]!!.jsonPrimitive.content
                map[name] = Tool(
                    name = name,
                    description = obj["description"]!!.jsonPrimitive.content,
                    capability = optionalString(obj["capability"]),
                    method = optionalString(obj["method"]),
                    permission = optionalString(obj["permission"]),
                    args = obj["args"]?.jsonObject ?: JsonObject(emptyMap()),
                    specRisk = optionalString(obj["spec_risk"]),
                )
            }
            return ToolCatalog(map)
        }

        /**
         * A JSON `null` is a [JsonNull] primitive whose `.content` is the STRING "null"; reading it that way gave host
         * tools (finish/todo_write/wait, `"capability": null`) the capability "null" instead of none.
         */
        private fun optionalString(element: kotlinx.serialization.json.JsonElement?): String? =
            if (element == null || element is kotlinx.serialization.json.JsonNull) null else element.jsonPrimitive.content
    }
}
