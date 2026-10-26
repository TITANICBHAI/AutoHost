import autohost
from autohost import ctx, device, sel, log
from autohost.errors import NotFound

PKG = "com.android.settings"
ABOUT = ("About phone", "About device", "About tablet", "About")


def main():
    device.app.launch(PKG)
    device.app.wait_foreground(PKG, timeout_ms=15000)

    row = device.ui.wait_for(sel.text(*ABOUT), timeout_ms=8000)
    if row is None:
        row = device.ui.scroll_until(sel.text(*ABOUT), "down", max_swipes=10)
    if row is None:
        ctx.evidence.ui_dump("about_missing")
        raise NotFound("About entry not found on the Settings screen")

    device.ui.click(row, retry_if_no_change=True)
    device.ui.wait_for_settle()
    dump = device.ui.dump(compact=True)
    log.info("about screen read", lines=len(dump.splitlines()))
    ctx.evidence.note("about_dump", dump[:4000])

    if autohost.capabilities().get("device.screenshot", {}).get("available"):
        path = ctx.evidence.screenshot("about")
        log.info("screenshot saved", path=path)
    else:
        log.info("screenshot unavailable on this device/provider chain")

    device.ui.press("back")
    return ctx.result("ok", {"lines": len(dump.splitlines())})
