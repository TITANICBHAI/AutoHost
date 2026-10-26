package app.autohost.core.model

/**
 * P4-01: restricted 5-field cron (plan §11.2), mirroring `autohost_ref.schedule.parse_cron` so
 * both engines produce identical fires (pinned by the shared vectors).
 *
 * Per field: `*`, `a`, `a-b`, with optional `/step`; lists via commas. dow runs 0-7 with both 0
 * and 7 meaning Sunday. Names (`@daily`, `jan`, `mon`), seconds, `?`, `L`, `W`, `#` are refused.
 * A field is *restricted* when it is not exactly `*`: standard cron matches a day when dom OR
 * dow match only if BOTH are restricted; a `*` field is ignored for day matching.
 */
data class CronSpec(
    val minutes: Set<Int>,
    val hours: Set<Int>,
    val dom: Set<Int>,
    val months: Set<Int>,
    val dow: Set<Int>,
    val domRestricted: Boolean,
    val dowRestricted: Boolean,
) {
    /** Standard day match: dom/dow OR only when both fields are restricted. */
    fun dayMatches(domOk: Boolean, dowOk: Boolean): Boolean = when {
        domRestricted && dowRestricted -> domOk || dowOk
        domRestricted -> domOk
        dowRestricted -> dowOk
        else -> true
    }

    companion object {
        private val FIELD = Regex("^(?:(\\d+)|(\\*)|(?:(-?\\d+)-(-?\\d+)))(?:/(\\d+))?$")
        private val RANGES = listOf(0..59, 0..23, 1..31, 1..12, 0..7)

        fun parse(expr: String): CronSpec {
            val fields = expr.trim().split(Regex("\\s+"))
            require(fields.size == 5) { "cron must have exactly 5 fields, got ${fields.size}" }
            val values = mutableListOf<Set<Int>>()
            val restricted = mutableListOf<Boolean>()
            for ((index, field) in fields.withIndex()) {
                val range = RANGES[index]
                val parsed = mutableSetOf<Int>()
                for (part in field.split(",")) {
                    val m = FIELD.matchEntire(part) ?: throw IllegalArgumentException("bad cron field part '$part'")
                    val (single, star, from, to, stepS) = m.destructured
                    val step = (stepS.toIntOrNull() ?: 1).also { require(it >= 1) { "cron step must be >= 1 in '$part'" } }
                    val startV: Int
                    val endV: Int
                    if (star.isNotEmpty()) {
                        startV = range.first; endV = range.last
                    } else if (single.isNotEmpty()) {
                        startV = single.toInt(); endV = startV
                    } else {
                        startV = from.toInt(); endV = to.toInt()
                    }
                    require(startV in range && endV in range && startV <= endV) {
                        "cron range '$part' outside ${range.first}-${range.last}"
                    }
                    var v = startV
                    while (v <= endV) {
                        parsed += v
                        v += step
                    }
                }
                values += parsed
                restricted += (field != "*")
            }
            val dow = values[4].let { if (7 in it) it - 7 + 0 else it }
            return CronSpec(
                minutes = values[0], hours = values[1], dom = values[2], months = values[3], dow = dow,
                domRestricted = restricted[2], dowRestricted = restricted[4],
            )
        }
    }
}
