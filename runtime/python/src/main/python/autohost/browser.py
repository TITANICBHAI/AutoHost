"""browser.*: browser.webview / browser.a11y / browser.cdp — the browser tier (P10-02..04).

The default link is the visible in-app WebView (Playwright-like subset); browser.a11y drives
the user's real browser through accessibility; browser.cdp talks Chrome DevTools Protocol over
the paired shell tier (draft). Challenge pages are NEVER bypassed: by default open()/navigate()
raise NeedsUser(SECURITY_CHALLENGE) and the run waits for the user — pass challenge="report" to
observe the wall yourself.
"""
from __future__ import annotations

from typing import Any

from ._core import current
from .errors import InvalidArgs, NeedsUser

MAX_TIMEOUT_MS = 120_000
DEFAULT_TIMEOUT_MS = 20_000


def _validate_timeout(timeout_ms: int | None) -> dict[str, Any]:
    if timeout_ms is not None and not 1 <= int(timeout_ms) <= MAX_TIMEOUT_MS:
        raise InvalidArgs(f"timeout_ms must be 1..{MAX_TIMEOUT_MS}")
    return {"timeout_ms": int(timeout_ms)} if timeout_ms is not None else {}


def _page(capability: str, url: str, timeout_ms: int | None, challenge: str, method: str,
          page_id: str | None = None) -> dict[str, Any]:
    if not isinstance(url, str) or not url.strip():
        raise InvalidArgs("url is required")
    if challenge not in ("raise", "report"):
        raise InvalidArgs("challenge must be 'raise' or 'report'")
    args: dict[str, Any] = {"url": url, "challenge": challenge}
    if page_id is not None:
        args["page_id"] = page_id
    args.update(_validate_timeout(timeout_ms))
    result = current().call(capability, method, args)
    # Gate 10-c: challenge pages route to WAITING_USER by default — the user solves them, not us.
    if challenge == "raise" and isinstance(result, dict) and result.get("challenge"):
        kind = result["challenge"].get("kind", "unknown")
        raise NeedsUser(
            "SECURITY_CHALLENGE",
            f"a {kind} challenge page was detected ({result.get('url', '')}); the run waits for the user",
            resume_hint="solve or dismiss the challenge on screen, then resume the run",
        )
    return result


# ---- browser.webview (the default link) -------------------------------------

def open(url: str, *, profile: str | None = None, timeout_ms: int | None = None,
         challenge: str = "raise") -> dict[str, Any]:
    """Open a page in the visible in-app browser.

    url is http(s) (domain-allowlisted by the grant) or ``task:<relpath>`` for a file inside
    this task's own bundle (perfect for local fixture pages — no network grant needed).
    """
    if challenge not in ("raise", "report"):
        raise InvalidArgs("challenge must be 'raise' or 'report'")
    args: dict[str, Any] = {"url": url, "challenge": challenge}
    if profile is not None:
        args["profile"] = profile
    args.update(_validate_timeout(timeout_ms))
    result = current().call("browser.webview", "open", args)
    if challenge == "raise" and isinstance(result, dict) and result.get("challenge"):
        kind = result["challenge"].get("kind", "unknown")
        raise NeedsUser(
            "SECURITY_CHALLENGE",
            f"a {kind} challenge page was detected ({result.get('url', '')}); the run waits for the user",
            resume_hint="solve or dismiss the challenge on screen, then resume the run",
        )
    return result


def navigate(page_id: str, url: str, *, timeout_ms: int | None = None, challenge: str = "raise") -> dict[str, Any]:
    """Navigate an open page to a new URL (same policy as open)."""
    if not isinstance(page_id, str) or not page_id:
        raise InvalidArgs("page_id is required")
    return _page("browser.webview", url, timeout_ms, challenge, "navigate", page_id)


def click(page_id: str, selector: str, *, timeout_ms: int | None = None) -> dict[str, Any]:
    """Click a CSS selector (real injected touch where the WebView allows it)."""
    if not selector:
        raise InvalidArgs("selector is required")
    args = {"page_id": page_id, "selector": selector, **_validate_timeout(timeout_ms)}
    return current().call("browser.webview", "click", args)


def type(page_id: str, selector: str, text: str, *, timeout_ms: int | None = None) -> dict[str, Any]:
    """Click the selector then type text. The text is never echoed back."""
    if not selector:
        raise InvalidArgs("selector is required")
    args = {"page_id": page_id, "selector": selector, "text": text, **_validate_timeout(timeout_ms)}
    return current().call("browser.webview", "type", args)


def extract(page_id: str, selector: str, attribute: str = "text") -> dict[str, Any]:
    """Extract text/attribute values for all elements matching a CSS selector."""
    if not selector:
        raise InvalidArgs("selector is required")
    return current().call("browser.webview", "extract", {"page_id": page_id, "selector": selector, "attribute": attribute})


def cookies(page_id: str) -> dict[str, Any]:
    """Cookies of the page's profile jar (per-profile isolated)."""
    return current().call("browser.webview", "cookies", {"page_id": page_id})


def clear_cookies(page_id: str) -> dict[str, Any]:
    """Clear the page's profile cookie jar."""
    return current().call("browser.webview", "clear_cookies", {"page_id": page_id})


def pages() -> dict[str, Any]:
    """All pages of this run (max 4)."""
    return current().call("browser.webview", "pages", {})


def close(page_id: str) -> dict[str, Any]:
    """Close one page."""
    return current().call("browser.webview", "close", {"page_id": page_id})


# ---- browser.a11y (the user's real browser through accessibility) ------------

def a11y_open(url: str, *, timeout_ms: int | None = None, challenge: str = "raise") -> dict[str, Any]:
    """Open a URL in the user's own browser (ACTION_VIEW + foreground wait)."""
    if not isinstance(url, str) or not url.strip():
        raise InvalidArgs("url is required")
    if challenge not in ("raise", "report"):
        raise InvalidArgs("challenge must be 'raise' or 'report'")
    args: dict[str, Any] = {"url": url, "challenge": challenge, **_validate_timeout(timeout_ms)}
    result = current().call("browser.a11y", "open", args)
    if challenge == "raise" and isinstance(result, dict) and result.get("challenge"):
        kind = result["challenge"].get("kind", "unknown")
        raise NeedsUser(
            "SECURITY_CHALLENGE",
            f"a {kind} challenge page was detected ({url}); the run waits for the user",
            resume_hint="solve or dismiss the challenge on screen, then resume the run",
        )
    return result


def a11y_click(selector: str, *, timeout_ms: int | None = None) -> dict[str, Any]:
    """Click a node whose visible text contains the selector (no CSS on this link)."""
    if not selector:
        raise InvalidArgs("selector is required")
    args = {"selector": selector, **_validate_timeout(timeout_ms)}
    return current().call("browser.a11y", "click", args)


def a11y_type(text: str, *, timeout_ms: int | None = None) -> dict[str, Any]:
    """Set text on the focused field of the user's browser."""
    args = {"text": text, **_validate_timeout(timeout_ms)}
    return current().call("browser.a11y", "type", args)


def a11y_extract() -> dict[str, Any]:
    """Visible text of the user's browser page as lines."""
    return current().call("browser.a11y", "extract", {})


def a11y_page() -> dict[str, Any]:
    """The browser package + visible sample (challenge scan included)."""
    return current().call("browser.a11y", "page", {})


def a11y_close() -> dict[str, Any]:
    """Press back in the user's browser."""
    return current().call("browser.a11y", "close", {})


# ---- browser.cdp (T3, draft until spike S27) ---------------------------------

def cdp_status() -> dict[str, Any]:
    """CDP route status: {shell_live, route, verified_on_hardware, note}."""
    return current().call("browser.cdp", "status", {})


def cdp_navigate(url: str, *, timeout_ms: int | None = None, challenge: str = "raise") -> dict[str, Any]:
    """Navigate the debugged target via CDP (URL allowlist still applies)."""
    return _page("browser.cdp", url, timeout_ms, challenge, "navigate")


def cdp_evaluate(expression: str, *, timeout_ms: int | None = None) -> dict[str, Any]:
    """Runtime.evaluate with returnByValue."""
    if not expression:
        raise InvalidArgs("expression is required")
    args = {"expression": expression, **_validate_timeout(timeout_ms)}
    return current().call("browser.cdp", "evaluate", args)


def cdp_screenshot() -> dict[str, Any]:
    """Page.captureScreenshot → {data_b64, format:'png'}."""
    return current().call("browser.cdp", "screenshot", {})


def cdp_cookies() -> dict[str, Any]:
    """Network.getCookies of the debugged target."""
    return current().call("browser.cdp", "cookies", {})


def cdp_close() -> dict[str, Any]:
    """Page.close + forward teardown."""
    return current().call("browser.cdp", "close", {})
