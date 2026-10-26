package app.autohost.capabilities.browser

/**
 * P10-02 (ADR-070): pure challenge detection — plan §6.1 "challenges ⇒ `WAITING_USER`" and the
 * Gate 10-c rule ("challenge pages are detected and routed to `WAITING_USER`; never bypassed").
 *
 * This is an *advisory signal engine over observable page facts* (URL, title, visible-text sample).
 * It never mutates page state, never retries, never solves anything — the provider reports the
 * verdict and the run pauses for the user. Detection errs toward false positives on interstitial
 * walls (the user just resumes) and never toward false negatives for the hard markers
 * (captcha / recaptcha / hcaptcha / turnstile endpoints).
 *
 * Login pages are deliberately NOT challenges: a task may legitimately fill credentials (that is
 * the Gate 10-a scenario). Only anti-bot walls, rate-limit interstitials and explicit denial
 * pages route to `WAITING_USER`.
 */
object ChallengeDetector {

    enum class Kind { CAPTCHA, ANTI_BOT, RATE_LIMIT, DENIED }

    data class Challenge(val kind: Kind, val matched: String)

    private val URL_MARKERS: Map<Regex, Kind> = mapOf(
        Regex("recaptcha|hcaptcha|turnstile|/captcha", RegexOption.IGNORE_CASE) to Kind.CAPTCHA,
        Regex("challenge|doubleclick[/?]|/show\\?fmt=", RegexOption.IGNORE_CASE) to Kind.ANTI_BOT,
    )

    private val TITLE_MARKERS: Map<Regex, Kind> = mapOf(
        Regex("captcha|are you a robot|verify you are (a )?human|attention required", RegexOption.IGNORE_CASE) to Kind.ANTI_BOT,
    )

    private val TEXT_MARKERS: Map<Regex, Kind> = mapOf(
        Regex("verify you are (a )?human|complete the (security )?check to continue|press ?&? ?hold to confirm", RegexOption.IGNORE_CASE) to Kind.ANTI_BOT,
        Regex("checking your browser before accessing|enable javascript and cookies to continue|just a moment\\.\\.\\.", RegexOption.IGNORE_CASE) to Kind.ANTI_BOT,
        Regex("unusual traffic from your computer|queries per second|automated queries", RegexOption.IGNORE_CASE) to Kind.RATE_LIMIT,
        Regex("too many requests|rate limit( exceeded| reached)", RegexOption.IGNORE_CASE) to Kind.RATE_LIMIT,
        Regex("access denied|request blocked|403 forbidden", RegexOption.IGNORE_CASE) to Kind.DENIED,
    )

    /**
     * @param bodySample the first ~4 KB of the page's visible text (host-side extraction cap);
     *   may be null when the page exposes no readable text yet.
     */
    fun detect(url: String, title: String?, bodySample: String?): Challenge? {
        for ((pattern, kind) in URL_MARKERS) {
            if (pattern.containsMatchIn(url)) return Challenge(kind, "url:/$pattern/")
        }
        for ((pattern, kind) in TITLE_MARKERS) {
            if (title != null && pattern.containsMatchIn(title)) {
                return Challenge(kind, "title:/$pattern/")
            }
        }
        for ((pattern, kind) in TEXT_MARKERS) {
            if (bodySample != null && pattern.containsMatchIn(bodySample)) {
                return Challenge(kind, "text:/$pattern/")
            }
        }
        return null
    }
}
