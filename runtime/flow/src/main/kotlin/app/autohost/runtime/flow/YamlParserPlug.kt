package app.autohost.runtime.flow

import kotlinx.serialization.json.JsonObject

/**
 * ADR-052: flows are JSON in v1; YAML becomes a *plug*, never a hand-rolled parser. The plan
 * (default 15) requires "a vetted parser whose licence fits the app licence" — snakeyaml /
 * yamlkt are both Apache-2.0, but resolving them needs a machine with Gradle + network. Until a
 * build machine installs the dependency and calls [install] (one line in `AppGraph`), `.yaml`
 * flow entries fail closed with a precise message instead of silently mis-parsing.
 */
interface YamlParserPlug {
    /** Parses YAML text into the JSON object tree the [FlowParser] consumes. Throws on garbage. */
    fun parseToJsonObject(text: String): JsonObject

    companion object {
        @Volatile
        private var plug: YamlParserPlug? = null

        /** Called once from `:app` wiring when the yamlkt dependency is actually resolvable. */
        fun install(parser: YamlParserPlug) {
            plug = parser
        }

        val installed: YamlParserPlug? get() = plug

        /** Test helper: forget the installed plug (tests must restore whatever they replace). */
        fun resetForTests() {
            plug = null
        }
    }
}

/** Typed start failure: the flow file could not be loaded/parsed (provider maps it to START_FAILED). */
class FlowSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)
