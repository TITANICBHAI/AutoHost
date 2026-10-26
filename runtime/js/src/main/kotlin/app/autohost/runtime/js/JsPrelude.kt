package app.autohost.runtime.js

/**
 * P11-01: loads the prelude resource (the client-side JS surface, ADR-076). The canonical source
 * is `src/main/resources/autohost/js/prelude.js`; the simulator keeps a byte-identical copy at
 * `sdk-python/autohost_sim/js/prelude.js` and a Python drift test pins the two together.
 */
object JsPrelude {

    const val RESOURCE_PATH = "/autohost/js/prelude.js"

    @Volatile
    private var cached: String? = null

    fun source(): String {
        cached?.let { return it }
        val text = JsPrelude::class.java.getResourceAsStream(RESOURCE_PATH)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw JsSourceException("prelude resource missing: $RESOURCE_PATH")
        cached = text
        return text
    }
}
