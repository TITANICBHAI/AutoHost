"""Web Login Scrape — the Gate 10-a reference task (plan §18 P10).

Logs into a fully LOCAL page (`task:web/login.html` — a file inside this task's own bundle, so
the run needs no network grant at all), then scrapes a value from the signed-in page. Challenge
pages route to WAITING_USER by default: the browser SDK raises NeedsUser(SECURITY_CHALLENGE)
and the run pauses for the user — we never solve, retry or bypass a wall.

Everything happens in the visible in-app WebView; the user can Close or press Back at any time.
"""
from autohost import browser, ctx, log
from autohost.errors import NeedsUser

LOGIN_URL = "task:web/login.html"
CHALLENGE_URL = "task:web/challenge.html"


def main():
    username = ctx.config["username"]
    password = ctx.secret("demo_password")

    # Demo switch: open the challenge fixture instead of the login page to show the Gate 10-c
    # behaviour — the SDK raises NeedsUser(SECURITY_CHALLENGE) and the run pauses for the user.
    if ctx.config.get("open_challenge"):
        browser.open(CHALLENGE_URL, profile=f"login-{ctx.run.id[:8]}")
        return ctx.result("cleared", {})  # only reached with challenge="report"; default pauses above

    # 1. Open the local login page (per-profile cookie jar; profile = this run).
    page = browser.open(LOGIN_URL, profile=f"login-{ctx.run.id[:8]}")
    log.info("login page open", page=page["page_id"], title=page["title"], input_mode=page["input_mode"])

    # 2. Fill the form. Text is never echoed back by the SDK; credentials go in via the page's
    #    own fields with the honest input_mode recorded in the result.
    browser.type(page["page_id"], "#username", username)
    browser.type(page["page_id"], "#password", password)

    # 3. Submit with a real click (the click result carries input_mode real|synthetic — S25
    #    measures which path the phone's WebView takes; both are honest).
    click = browser.click(page["page_id"], "#submit")
    log.info("submitted", clicked=click["clicked"], input_mode=click["input_mode"])

    # 4. Extract the signed-in status. On a challenge the SDK raises NeedsUser — that is the
    #    Gate 10-c contract; catching it here would defeat the pause, so we let it propagate.
    values = browser.extract(page["page_id"], "#status")
    status = values["values"][0] if values["values"] else ""

    # 5. The session cookie proves the login on this profile's jar (S26 isolation at the phone).
    jar = browser.cookies(page["page_id"])
    has_session = any(c["name"] == "session" for c in jar["cookies"])

    browser.close(page["page_id"])
    if status == "signed in" and has_session:
        return ctx.result("ok", {"status": status, "session_cookie": True, "input_mode": click["input_mode"]})
    return ctx.result("failed", {"status": status, "session_cookie": has_session})
