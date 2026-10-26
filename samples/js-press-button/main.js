/**
 * Press Button (JS) — the archetype-A reference task, JavaScript edition (P11-04, plan §18 P11).
 *
 * Same semantics as `samples/press-button` (Python), written against the JS v1 surface
 * (ADR-076): the global `autohost` from the runtime prelude, `autohost.call` as the universal
 * broker verb, `sel` builders, `ctx` helpers — so the third runtime proves Gate 11 F2 (Python,
 * flow and JS tasks coexist) and F5 (all three runtimes through the same broker) without any
 * Kotlin changes.
 *
 * JS task contract: define `main` with `export function main()` (ESM). Imports from `lib/`
 * work on the device (QuickJS module loader) and in the simulator (node) alike. Blocking
 * semantics match the Python SDK: every `autohost.*` call parks while paused and raises
 * `autohost.errors.Cancelled` when the run is cancelled.
 */
const RETRY_SOONER_MINUTES = 5;

export function main() {
  const cfg = autohost.ctx.config;
  const pkg = cfg["target_package"];
  const label = cfg["button_label"];
  const variants = cfg["label_variants"] || [];
  const waitMs = Math.floor(Number(cfg["wait_seconds"] || 20)) * 1000;
  const minGap = Math.floor(Number(cfg["min_interval_minutes"] || 0));

  const last = autohost.ctx.profileStore.get("last_pressed_at");
  if (last && minGap && autohost.ctx.minutesSince(last) < minGap) {
    autohost.log.info("cool-down active; skipping", { minutes_since: Math.round(autohost.ctx.minutesSince(last) * 10) / 10 });
    return autohost.ctx.result("skipped_cooldown");
  }

  try {
    autohost.call("device.app", "launch", { package: pkg });
    autohost.call("device.app", "wait_foreground", { package: pkg, timeout_ms: 20000 });
  } catch (e) {
    // Honest failure path: evidence first, then a sooner-retry proposal, then rethrow (FAILED).
    autohost.ctx.evidence.note("launch_failed", `${e.code || "INTERNAL"}: ${e.message || e}`);
    proposeSooner();
    throw e;
  }

  const res = autohost.call("device.ui", "wait_for", {
    selector: autohost.sel.text(label, ...variants).json,
    timeout_ms: waitMs,
    visible_only: true,
  });
  const node = res && res.node ? res.node : null;
  if (!node) {
    // Button absent: keep evidence, retry sooner than the fixed cadence, fail honestly.
    autohost.ctx.evidence.uiDump("button_missing");
    proposeSooner();
    return autohost.ctx.result("nothing_to_do");
  }

  const click = autohost.call("device.ui", "click", {
    target: { ref: node.ref },
    strategy: "auto",
    retry_if_no_change: true,
  });
  autohost.log.info("button pressed", { method: click.method, verified: click.verified });
  autohost.ctx.profileStore.set("last_pressed_at", autohost.ctx.now());
  return autohost.ctx.result("pressed", { method: click.method, verified: click.verified });
}

function proposeSooner() {
  /** P4-11 (plan §11.7): propose a sooner retry; the host clamps it to the declared bounds. */
  try {
    autohost.ctx.nextRun({ afterMinutes: RETRY_SOONER_MINUTES });
  } catch (e) {
    // proposal is best effort; the fixed schedule still applies
  }
}
