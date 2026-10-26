"""Press Button — the archetype-A reference task (plan §1.3/§18 P4-15).

Opens an app, waits for a labelled button, presses it, verifies, and leaves honest state behind:
evidence on the failure path, a cooldown via the profile store, and a `ctx.next_run` proposal
(Phase 4) so a flaky screen is retried sooner without tightening the fixed schedule.

Scheduled runs surface as `trigger.type == "schedule"`; the script stays manual-friendly.
"""
from autohost import ctx, device, sel, log
from autohost.errors import AutoHostError

RETRY_SOONER_MINUTES = 5


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

    try:
        device.app.launch(pkg)
        device.app.wait_foreground(pkg, timeout_ms=20000)
    except AutoHostError as exc:
        ctx.evidence.note("launch_failed", f"{exc.code}: {exc}")
        propose_sooner()
        raise

    node = device.ui.wait_for(sel.text(label, *variants), timeout_ms=wait_ms)
    if node is None:
        # Button absent: keep evidence, retry sooner than the fixed cadence, and fail honestly.
        ctx.evidence.ui_dump("button_missing")
        propose_sooner()
        return ctx.result("nothing_to_do")

    res = device.ui.click(node, retry_if_no_change=True)
    log.info("button pressed", method=res["method"], verified=res["verified"])
    ctx.profile_store["last_pressed_at"] = ctx.now()
    return ctx.result("pressed", {"method": res["method"], "verified": res["verified"]})


def propose_sooner():
    """P4-11 (plan §11.7): propose a sooner retry; the host clamps it to the declared bounds."""
    try:
        ctx.next_run(after_minutes=RETRY_SOONER_MINUTES)
    except AutoHostError:
        pass  # proposal is best effort; the fixed schedule still applies
