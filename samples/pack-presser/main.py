"""Press Button (app pack edition) — P11-04 (ADR-080) reference task.

The brittle knowledge (the button's selector, its locale labels) lives in the APP PACK
(`app.autohost.sample.presser-pack`); the task only names them. Install the pack once (Settings
→ Backup & packs → Install pack), reference it via `uses_packs`, and a selector fix ships to
every task that uses the pack — no task edits, no re-imports.

The pack payload arrives in the run config (`ctx.config["packs"]`); `autohost.packs` is the
readable way in. A missing pack raises PackMissing — the honest failure, never a silent
fallback to a hardcoded selector.
"""
from autohost import ctx, device, log
import autohost.packs as packs

PACK_ID = "app.autohost.sample.presser-pack"


def main():
    target = ctx.config["target_package"]
    locale = ctx.config.get("locale")
    wait_seconds = int(ctx.config.get("wait_seconds") or 20)

    # The pack (and its version) rode the run config — log it so the evidence names its source.
    pack = packs.pack(ctx, PACK_ID)
    log.info("using app pack", pack=PACK_ID, version=pack.get("version"), target=pack.get("target"))

    device.app.launch(target)
    device.app.wait_foreground(target, timeout_ms=20_000)

    selector = packs.selector(ctx, PACK_ID, "login_button")
    label = packs.label(ctx, PACK_ID, "login_button", locale)
    log.info("pressing", selector=selector.to_json(), label=label)

    node = device.ui.wait_for(selector, timeout_ms=wait_seconds * 1000)
    if node is None:
        ctx.evidence.ui_dump("button_missing")
        return ctx.result("nothing_to_do")

    res = device.ui.click(node, retry_if_no_change=True)
    log.info("button pressed", method=res["method"], verified=res["verified"])
    ctx.evidence.note("pressed", f"pressed the pack's '{label}' button")
    return ctx.result("pressed", {"pack_version": pack.get("version"), "label": label})
