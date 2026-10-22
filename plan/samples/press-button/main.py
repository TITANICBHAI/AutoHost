from autohost import ctx, device, sel, log


def main():
    pkg = ctx.config["target_package"]
    label = ctx.config["button_label"]
    variants = list(ctx.config.get("label_variants", []))
    wait_ms = int(ctx.config.get("wait_seconds", 20)) * 1000
    min_gap = int(ctx.config.get("min_interval_minutes", 0))

    last = ctx.profile_store.get("last_pressed_at")
    if last and min_gap and ctx.minutes_since(last) < min_gap:
        log.info("cool-down active; skipping", minutes_since=round(ctx.minutes_since(last), 1))
        return ctx.result("skipped_cooldown")

    device.app.launch(pkg)
    device.app.wait_foreground(pkg, timeout_ms=20000)

    node = device.ui.wait_for(sel.text(label, *variants), timeout_ms=wait_ms)
    if node is None:
        log.warn("button not present; nothing to do")
        ctx.evidence.ui_dump("button_missing")
        return ctx.result("nothing_to_do")

    res = device.ui.click(node, retry_if_no_change=True)
    log.info("button pressed", method=res["method"], verified=res["verified"])
    ctx.profile_store["last_pressed_at"] = ctx.now()
    return ctx.result("pressed", {"method": res["method"]})
