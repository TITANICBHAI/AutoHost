from autohost import ctx, http, notify, log
from autohost.errors import Timeout


def main():
    domain = ctx.config["domain"]
    path = ctx.config.get("path", "/")
    expected = int(ctx.config.get("expect_status", 200))
    url = f"https://{domain}{path}"
    try:
        r = http.get(url, timeout=10)
        status = r.status
    except Timeout:
        status = 0
    if status != expected:
        log.warn("check failed", url=url, status=status, expected=expected)
        notify.post("Check failed", f"{domain}{path} returned {status}")
        ctx.next_run(after_minutes=int(ctx.config.get("retry_minutes", 5)))
        return ctx.result("failed", {"status": status})
    return ctx.result("ok", {"status": status})
