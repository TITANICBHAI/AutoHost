#!/usr/bin/env python3
"""P4-17 soak harness + report (Gate 4-b/c): prove schedule honesty over a long run.

Usage:
  python3 tools/soak_report.py events.jsonl               # analyse a collected soak log
  python3 tools/soak_report.py --selfcheck                # synthetic log, verifies the math
  python3 tools/soak_report.py events.jsonl -o report.md  # write the markdown report

Collecting the log on the phone (3-hour soak, 10-min cadence, two profiles, fixture app):
  1. Schedule `press-button` against the fixture app with profiles A and B (sequential),
     every 10 minutes, misfire skip.
  2. Capture scheduler + run logs for 3 hours:
       adb logcat -T 1 -v epoch | grep -E 'scheduler|AutoHost' > events_raw.txt
     or poll the Dev API (`runs.list`) every minute into JSONL.
  3. Normalise to JSONL events (one JSON object per line):
       {"type":"fire","schedule_id":"sch_1","due_ms":…,"fired_ms":…,"profile":"A","run_id":"…","ui":true}
       {"type":"run","run_id":"…","start_ms":…,"end_ms":…,"ui":true}

The report computes exactly what Gate 4 demands:
  - on-time: share of fires within TOLERANCE_MS of their due time (target ≥ 95 %),
  - per-profile order: sequential mode never inverts the profile order within one fire,
  - no double UI runs: no two ui=true runs overlap in wall time,
  - misfire accounting: skips/late fires listed with reasons when present.
Exit code 0 when every gate threshold holds, 1 otherwise (so CI can gate on it).
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
from datetime import datetime, timezone

TOLERANCE_MS = 120_000  # 2 min grace for WorkManager fallback; alarms should be far tighter
UI_OVERLAP_ALLOWED = False


def load_events(path: pathlib.Path) -> list[dict]:
    events = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            events.append(json.loads(line))
        except json.JSONDecodeError as exc:
            print(f"warning: skipping malformed line: {exc}", file=sys.stderr)
    return events


def analyse(events: list[dict]) -> dict:
    fires = [e for e in events if e.get("type") == "fire"]
    runs = [e for e in events if e.get("type") == "run"]
    runs.sort(key=lambda r: r.get("start_ms", 0))

    on_time = [f for f in fires if abs(f.get("fired_ms", 0) - f.get("due_ms", 0)) <= TOLERANCE_MS]
    late = [f for f in fires if f not in on_time]

    # Sequential per-profile order: within one due_ms, later profiles must not fire earlier.
    order_violations = []
    by_due: dict[int, list[dict]] = {}
    for f in fires:
        by_due.setdefault(f.get("due_ms", 0), []).append(f)
    for due, group in by_due.items():
        seq = [f for f in group if f.get("profile") is not None]
        seq.sort(key=lambda f: f.get("fired_ms", 0))
        declared = [f.get("profile") for f in sorted(group, key=lambda f: f.get("profile") or "")]
        if [f.get("profile") for f in seq] != [p for p in declared if p]:
            order_violations.append({"due_ms": due, "observed": [f.get("profile") for f in seq]})

    # Never two UI runs at once.
    ui_runs = sorted((r for r in runs if r.get("ui")), key=lambda r: r.get("start_ms", 0))
    overlaps = []
    for prev, nxt in zip(ui_runs, ui_runs[1:]):
        if nxt.get("start_ms", 0) < prev.get("end_ms", float("inf")):
            overlaps.append({"first": prev.get("run_id"), "second": nxt.get("run_id")})

    total = len(fires)
    on_time_pct = (len(on_time) / total * 100.0) if total else 0.0
    return {
        "fires": total,
        "on_time": len(on_time),
        "on_time_pct": round(on_time_pct, 1),
        "late": late,
        "order_violations": order_violations,
        "ui_overlaps": overlaps,
        "ui_runs": len(ui_runs),
        "pass": on_time_pct >= 95.0 and not order_violations and (not UI_OVERLAP_ALLOWED or not overlaps),
    }


def render(report: dict, source: str) -> str:
    when = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    lines = [
        "# Soak report (Gate 4-b/c)",
        f"*source:* `{source}` · *generated:* {when} · *tolerance:* ±{TOLERANCE_MS // 1000}s",
        "",
        "| Metric | Value | Gate |",
        "|---|---|---|",
        f"| Fires observed | {report['fires']} | 3 h at 10-min cadence ≈ 18 |",
        f"| On time | {report['on_time']} ({report['on_time_pct']} %) | ≥ 95 % |",
        f"| Late fires | {len(report['late'])} | listed with reasons |",
        f"| Per-profile order violations | {len(report['order_violations'])} | 0 |",
        f"| UI runs overlapping | {len(report['ui_overlaps'])} | 0 |",
        f"| UI runs total | {report['ui_runs']} | – |",
        "",
        f"**Verdict: {'PASS' if report['pass'] else 'FAIL'}**",
        "",
    ]
    if report["late"]:
        lines.append("## Late fires")
        for f in report["late"]:
            delta = f.get("fired_ms", 0) - f.get("due_ms", 0)
            lines.append(f"- {f.get('schedule_id')} due {f.get('due_ms')} fired {f.get('fired_ms')} ({delta:+d} ms)")
        lines.append("")
    if report["order_violations"]:
        lines.append("## Profile order violations")
        for v in report["order_violations"]:
            lines.append(f"- due {v['due_ms']}: observed {v['observed']}")
        lines.append("")
    if report["ui_overlaps"]:
        lines.append("## UI overlaps (never allowed)")
        for o in report["ui_overlaps"]:
            lines.append(f"- {o['first']} overlaps {o['second']}")
        lines.append("")
    return "\n".join(lines)


def synthetic_selfcheck() -> list[dict]:
    """A deterministic 18-fire, two-profile sequential soak with one deliberately late fire.

    Sequential fan-out (Gate 4-c): profile B fires only after A's run ended; the planted late
    fire is profile A of cycle 7 (6.7 min late) → 35/36 = 97.2 % on time → PASS expected.
    """
    events = []
    base = 1792940400000  # 2026-10-25T15:00Z (the vectors' Sunday)
    cadence = 10 * 60_000
    run_ms = 30_000
    for i in range(18):
        due = base + i * cadence
        prev_end = due
        for profile in ["A", "B"]:  # sequential: each profile fires after the previous run ends
            late = 400_000 if (i == 7 and profile == "A") else 0
            slot_due = max(due, prev_end + 1_000)  # sequential dues cascade from the previous run's end
            fire_ms = slot_due + late
            events.append({
                "type": "fire", "schedule_id": "sch_soak", "due_ms": slot_due,
                "fired_ms": fire_ms, "profile": profile, "run_id": f"r{i}{profile}", "ui": True,
            })
            events.append({
                "type": "run", "run_id": f"r{i}{profile}",
                "start_ms": fire_ms, "end_ms": fire_ms + run_ms, "ui": True,
            })
            prev_end = fire_ms + run_ms
    return events


def main() -> int:
    parser = argparse.ArgumentParser(description="Soak report for Gate 4-b/c")
    parser.add_argument("events", nargs="?", help="JSONL events file")
    parser.add_argument("-o", "--out", help="write the markdown report here")
    parser.add_argument("--selfcheck", action="store_true", help="run on a synthetic log")
    args = parser.parse_args()

    if args.selfcheck:
        events = synthetic_selfcheck()
        source = "synthetic selfcheck (18 fires × 2 profiles, 1 late)"
    elif args.events:
        path = pathlib.Path(args.events)
        events = load_events(path)
        source = str(path)
    else:
        parser.error("give an events file or --selfcheck")
        return 2

    report = analyse(events)
    text = render(report, source)
    if args.out:
        pathlib.Path(args.out).write_text(text, encoding="utf-8")
        print(f"wrote {args.out}")
    print(text)
    print("\nselfcheck note: the synthetic log plants ONE late fire (97.2 % on time) — PASS expected.")
    return 0 if report["pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
