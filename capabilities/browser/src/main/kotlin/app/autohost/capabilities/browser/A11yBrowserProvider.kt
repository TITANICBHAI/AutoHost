package app.autohost.capabilities.browser

import app.autohost.core.broker.CallContext
import app.autohost.core.broker.CapabilityProvider
import app.autohost.core.broker.DeviceEnv
import app.autohost.core.model.Availability
import app.autohost.core.model.Envelope
import app.autohost.core.model.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * P10-03: the `browser.a11y` capability (ADR-070) — drives the **user's own browser app** through
 * the T1 accessibility layer. Same method family as `browser.webview` where it is meaningful:
 * open (ACTION_VIEW + foreground wait), click (a11y tap by visible text), type (into the focused
 * field), extract (the visible text of the page), page (package + visible title/sample), close
 * (back). Registered AFTER the webview provider — the first-available rule prefers the in-app
 * browser; this link exists for tasks that must act inside the user's real browser session.
 *
 * Honest limitations, enforced in code:
 *  - **No cookies** — the a11y layer sees pixels and nodes, not browser storage. `cookies` and
 *    `clear_cookies` answer CAPABILITY_UNAVAILABLE (COOKIES_UNSUPPORTED_LINK).
 *  - **No CSS selectors** — the web's DOM is not exposed; selectors are visible-text matches.
 *    A `selector` here is matched against node text (case-insensitive `contains`).
 *  - **No URL read claim** — the address bar is ordinary UI we do not mine for identity; `page`
 *    reports the browser package and the visible page sample instead.
 *  - Inputs go through the accessibility gesture/action pipeline: `input_mode:"a11y"` always.
 *
 * All Android work happens behind the two seams; this file is pure JVM.
 */
class A11yBrowserProvider(
    private val launcher: UrlLauncher,
    private val link: Link,
) : CapabilityProvider {

    /** App-side seam: start a VIEW intent for the URL (BrowserPolicy still gates the URL). */
    fun interface UrlLauncher {
        fun launch(url: String): Boolean
    }

    /** App-side seam over the a11y provider — bound in AppGraph (ADR-035 lambda pattern). */
    interface Link {
        fun waitForeground(browserPackage: String?, timeoutMs: Long): Boolean
        fun clickText(text: String, timeoutMs: Long): HostAct
        fun typeIntoFocused(text: String, timeoutMs: Long): HostAct
        fun visibleText(maxChars: Int): String?
        fun currentPackage(): String?
        fun pressBack(): Boolean
    }

    override val capabilityId: String = "browser.a11y"
    override val providerId: String = "browser.a11y"
    override val tier: String = "T1"

    override fun availability(env: DeviceEnv): Availability = linkAvailability()

    @Volatile
    private var linkReady: Boolean = false

    /** The app wiring flips this when the accessibility service connects/disconnects. */
    fun setLinkReady(ready: Boolean) {
        linkReady = ready
    }

    private fun linkAvailability(): Availability = when {
        linkReady -> Availability.Available
        else -> Availability.NeedsSetup(reason = "ACCESSIBILITY_SERVICE_OFF", fixAction = "OPEN_ACCESSIBILITY_SETTINGS")
    }

    override fun permissionFor(method: String, args: JsonObject): String? = BrowserPolicy.permissionFor(method)

    override suspend fun invoke(ctx: CallContext, method: String, args: JsonObject): Envelope = when (method) {
        "open" -> open(ctx, args)
        "click" -> click(ctx, args)
        "type" -> type(ctx, args)
        "extract" -> extract(ctx)
        "page" -> page(ctx)
        "close" -> close(ctx)
        "cookies", "clear_cookies" -> Envelope.err(
            ErrorCode.CAPABILITY_UNAVAILABLE,
            "the a11y link cannot read browser storage; use browser.webview for cookies",
            reason = "COOKIES_UNSUPPORTED_LINK",
        )
        else -> Envelope.err(ErrorCode.INVALID_ARGS, "unknown browser.a11y method '$method'")
    }

    private fun sampleBody(maxChars: Int = 4096): String? = link.visibleText(maxChars)

    private fun challengeIn(url: String, sample: String?): Envelope? {
        val challenge = ChallengeDetector.detect(url, null, sample) ?: return null
        return Envelope.ok(
            buildJsonObject {
                put("package", link.currentPackage())
                put("challenge", buildJsonObject {
                    put("kind", challenge.kind.name.lowercase())
                    put("matched", challenge.matched)
                })
            },
        )
    }

    private fun open(ctx: CallContext, args: JsonObject): Envelope {
        val url = (args["url"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"url\" is required")
        val grant = ctx.grants["browser.open"]
        when (val verdict = BrowserPolicy.urlVerdict(grant, url)) {
            is BrowserPolicy.UrlVerdict.Allowed -> if (verdict.kind == BrowserPolicy.UrlVerdict.Allowed.Kind.TASK_FILE) {
                return Envelope.err(
                    ErrorCode.SCOPE_VIOLATION,
                    "task: URLs only resolve inside the in-app browser; open a real http(s) URL here",
                )
            }
            is BrowserPolicy.UrlVerdict.Denied -> return Envelope.err(
                ErrorCode.SCOPE_VIOLATION,
                verdict.detail,
                fixAction = "OPEN_PERMISSION_REVIEW",
            )
            BrowserPolicy.UrlVerdict.NoGrant -> return Envelope.err(
                ErrorCode.SCOPE_VIOLATION,
                "the browser.open grant carries no domains scope object",
                fixAction = "OPEN_PERMISSION_REVIEW",
            )
        }
        val timeoutMs = BrowserPolicy.timeoutMs((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull())
        if (!launcher.launch(url)) {
            return Envelope.err(ErrorCode.CAPABILITY_UNAVAILABLE, "no browser app accepted the VIEW intent")
        }
        if (!link.waitForeground(null, timeoutMs)) {
            return Envelope.err(ErrorCode.TIMEOUT, "no browser came to the foreground within ${timeoutMs}ms")
        }
        val sample = sampleBody()
        return challengeIn(url, sample)
            ?: Envelope.ok(
                buildJsonObject {
                    put("package", link.currentPackage())
                    put("page", buildJsonObject {
                        put("url", url)
                        put("load_state", "foreground")
                        put("input_mode", "a11y")
                    })
                },
            )
    }

    private fun click(ctx: CallContext, args: JsonObject): Envelope {
        val selector = (args["selector"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"selector\" is required (visible text to match)")
        val timeoutMs = BrowserPolicy.timeoutMs((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull())
        val act = link.clickText(selector, timeoutMs)
        return Envelope.ok(
            buildJsonObject {
                put("clicked", act.performed)
                put("found", act.found)
                put("input_mode", act.inputMode)
            },
        )
    }

    private fun type(ctx: CallContext, args: JsonObject): Envelope {
        val text = (args["text"] as? JsonPrimitive)?.content ?: return Envelope.err(ErrorCode.INVALID_ARGS, "\"text\" is required")
        val timeoutMs = BrowserPolicy.timeoutMs((args["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull())
        val act = link.typeIntoFocused(text, timeoutMs)
        return Envelope.ok(
            buildJsonObject {
                put("typed", act.performed)
                put("found", act.found)
                put("input_mode", act.inputMode)
            },
        )
    }

    private fun extract(ctx: CallContext): Envelope {
        val sample = sampleBody(BrowserPolicy.MAX_EXTRACT_BYTES)
        return Envelope.ok(
            buildJsonObject {
                putJsonArray("values") {
                    sample?.split('\n')?.filter { it.isNotBlank() }?.forEach { add(JsonPrimitive(it.trim())) }
                }
                put("count", sample?.split('\n')?.count { it.isNotBlank() } ?: 0)
                put("truncated", false)
                put("input_mode", "a11y")
            },
        )
    }

    private fun page(ctx: CallContext): Envelope {
        val sample = sampleBody()
        return challengeIn("", sample)
            ?: Envelope.ok(
                buildJsonObject {
                    put("package", link.currentPackage())
                    put("visible_sample_chars", sample?.length ?: 0)
                },
            )
    }

    private fun close(ctx: CallContext): Envelope {
        val performed = link.pressBack()
        return Envelope.ok(buildJsonObject { put("closed", performed); put("input_mode", "a11y") })
    }
}

