package app.autohost.capabilities.data

/**
 * P10-06..08: pure data/hardware policy (ADR-073) — argument validation and caps shared by
 * the contacts/calendar/media, location/camera/mic/sensors and sms/telephony providers so no
 * Android-bound provider invents its own numbers.
 *
 * Privacy stance (plan §6.1): personal-data reads are row-capped and field-capped; sends
 * (SMS, calls) are dangerous and fail closed on anything that is not a dialable, non-premium
 * destination; nothing here can run invisibly — every provider reports honest availability
 * through the lazy-permission flow (P8-14 FixHub).
 */
object DataPolicy {

    const val MAX_ROWS = 500
    const val MAX_ROW_BYTES = 64 * 1024        // per serialized row cap (defensive)
    const val MAX_READ_BYTES = 10 * 1024 * 1024
    const val MAX_INSERT_BYTES = 10 * 1024 * 1024
    const val DEFAULT_ROWS = 100

    const val MAX_SMS_BODY = 480               // ~3 GSM-7 parts; honest multipart refusal
    const val MAX_SMS_SENDS_PER_RUN = 10
    const val MAX_RECORD_MS = 60_000
    const val DEFAULT_RECORD_MS = 10_000
    const val MAX_CAPTURE_BYTES = 15 * 1024 * 1024

    /** Row-limit clamp shared by every query method. */
    fun rows(limit: Int?): Int = (limit ?: DEFAULT_ROWS).coerceIn(1, MAX_ROWS)

    /** Encrypted-confusable record windows: [fromMs, toMs] with a sane span cap (31 days). */
    const val MAX_WINDOW_MS = 31L * 24 * 60 * 60 * 1000

    fun windowVerdict(fromMs: Long, toMs: Long): Boolean =
        toMs >= fromMs && (toMs - fromMs) <= MAX_WINDOW_MS

    // ---- dialable destinations -------------------------------------------------

    sealed class NumberVerdict {
        data class Ok(val normalized: String) : NumberVerdict()
        data class Bad(val detail: String) : NumberVerdict()
    }

    /**
     * Dial/SMS destination check: digits, `+`-prefix and separators only; E.164-ish length
     * bounds; explicit premium-rate and short-code refusals (a task must never silently cost
     * the user money — plan §0.4 boundaries).
     */
    fun numberVerdict(raw: String, allowShort: Boolean = false): NumberVerdict {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return NumberVerdict.Bad("number is empty")
        if (trimmed.any { !it.isDigit() && it !in "+-() ." }) {
            return NumberVerdict.Bad("number contains characters that are not digits or separators")
        }
        val digits = trimmed.filter { it.isDigit() }
        if (trimmed.startsWith("+")) {
            if (digits.length !in 8..15) return NumberVerdict.Bad("E.164 number must be 8..15 digits")
        } else {
            if (digits.length < 3) return NumberVerdict.Bad("number is too short")
            if (digits.length > 15) return NumberVerdict.Bad("number is too long")
        }
        // Premium-rate refuse list: international premium (+9xx services) and common national
        // premium prefixes (1-9xx US, 09xx DE, 0899xx UK). Short codes are refused unless the
        // caller explicitly allows them (some carriers block them anyway — honest either way).
        if (!allowShort && digits.length <= 6) {
            return NumberVerdict.Bad("short codes are refused by default (allow_short=true to override)")
        }
        if (digits.startsWith("1900") || digits.startsWith("190") || digits.startsWith("09") && digits.length in 3..11 && digits.startsWith("0900")) {
            return NumberVerdict.Bad("premium-rate destinations are refused")
        }
        return NumberVerdict.Ok(digits)
    }

    fun smsBodyVerdict(body: String): String? = when {
        body.isEmpty() -> "body is empty"
        body.length > MAX_SMS_BODY -> "body exceeds $MAX_SMS_BODY characters (send parts explicitly instead)"
        else -> null
    }

    /** Camera/mic capture argument windows. */
    fun recordMs(requested: Int?): Int = (requested ?: DEFAULT_RECORD_MS).coerceIn(500, MAX_RECORD_MS)
}
