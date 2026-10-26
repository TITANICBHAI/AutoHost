package app.autohost.capabilities.browser

/** P10-02: page models shared by the provider and its host seam (pure, no Android imports). */

/** One hosted browser page (in-app WebView, user's real browser or a CDP target). */
data class WebPage(
    val pageId: String,
    val url: String,
    val title: String?,
    val loadState: String,
    val profile: String,
    val provider: String,
    val inputMode: String? = null,
)

/** Result of a host-side click/type action — honest about how the input was produced. */
data class HostAct(
    val performed: Boolean,
    val found: Boolean,
    val inputMode: String,
)

data class HostExtract(
    val values: List<String?>,
    val truncated: Boolean,
)

data class HostCookie(
    val name: String,
    val value: String,
    val domain: String?,
    val path: String?,
    val expiresAtMs: Long?,
)
