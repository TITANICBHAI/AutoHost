package app.autohost.runtime.agent

import app.autohost.core.common.AhJson
import app.autohost.core.model.Match
import app.autohost.core.model.Selector
import app.autohost.core.model.SelectorTerm
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * INT-A5: how the model names an element by what it shows.
 *
 * The model is told that `selector` (and the click's `expect`) is plain text: the words the element shows. The phone
 * service does not take words; it takes a structured selector (and refuses a bare text: "must be a selector object"),
 * so before this change `ui_find`, `ui_wait_for` and `ui_scroll_until` could never match anything and a click with an
 * `expect` failed AFTER the click had already happened. The pipeline converts one into the other just before the call:
 * an element whose text OR description contains the words, case and extra spaces do not matter.
 */
object SelectorText {

    /** The argument names that hold such words. */
    val KEYS: Set<String> = setOf("selector", "expect")

    /** The structured selector for "an element whose text or description contains [words]". */
    fun toSelector(words: String): Selector {
        val match = Match(labels = listOf(words.trim()))
        return Selector(op = "or", terms = listOf(SelectorTerm(text = match), SelectorTerm(desc = match)))
    }

    /**
     * [args] with every `selector` / `expect` given in words turned into the structured form. Empty words are left out
     * (nothing to look for), a value that is already structured stays as it is, every other argument is untouched.
     */
    fun forPhoneService(args: JsonObject): JsonObject {
        if (KEYS.none { args.containsKey(it) }) return args
        return buildJsonObject {
            for ((key, value) in args) {
                if (key !in KEYS) {
                    put(key, value)
                    continue
                }
                when (value) {
                    is JsonNull -> {}
                    is JsonPrimitive -> {
                        val words = value.content.trim()
                        if (words.isNotEmpty()) {
                            put(key, AhJson.json.encodeToJsonElement(Selector.serializer(), toSelector(words)))
                        }
                    }
                    else -> put(key, value)
                }
            }
        }
    }
}
