package app.autohost.core.model

import java.text.Normalizer
import java.util.Locale

/** Text comparison form used by selectors: Unicode NFKC, typographic quotes to ASCII, collapsed whitespace, lower case. */
object TextNormalizer {
    private val whitespace = Regex("\\s+")

    fun collapse(value: String): String {
        var s = Normalizer.normalize(value, Normalizer.Form.NFKC)
        s = s.replace('\u2018', '\'').replace('\u2019', '\'').replace('\u201C', '"').replace('\u201D', '"')
        return whitespace.replace(s, " ").trim()
    }

    fun normalize(value: String): String = collapse(value).lowercase(Locale.ROOT)
}
