#!/usr/bin/env python3
"""Generate testing/golden/schedule_vectors.json from the Python reference (autohost_ref.schedule).

The committed file is the shared contract: sdk-python tests and the Kotlin :core:engine
ScheduleEngine test both assert against it. Regenerate only when the contract changes,
and record why in docs/DECISIONS.md.
"""
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "sdk-python"))

from autohost_ref.schedule import (  # noqa: E402
    evaluate_conditions,
    fan_out,
    next_fire,
    next_fires,
    parse_rule,
    resolve_misfire,
)

# 2026-10-25 is a Sunday; anchor times are chosen so boundaries are easy to hand-check.
T0 = 1792940400000  # 2026-10-25T15:00:00Z (Sunday)
ISO_T0 = "2026-10-25T15:00:00Z"
M = 60_000
H = 3_600_000
D = 86_400_000

CASES = []


def next_case(name, rule, after, state=None, next5=False):
    rule = dict(rule)
    rule.setdefault("id", f"sch_{name}")
    rule.setdefault("task", "app.autohost.sample.press-button")
    parsed = parse_rule(rule)
    case = {
        "kind": "next",
        "name": name,
        "rule": rule,
        "after_ms": after,
        "state": state or {},
        "expected": next_fire(parsed, after, state),
    }
    if next5:
        case["expected_next5"] = next_fires(parsed, after, state, 5)
    CASES.append(case)


def misfire_case(name, policy, missed):
    CASES.append({
        "kind": "misfire", "name": name, "policy": policy,
        "missed": missed, "expected": resolve_misfire(policy, missed),
    })


def fanout_case(name, profile_ids, mode, stagger, due):
    CASES.append({
        "kind": "fanout", "name": name, "profile_ids": profile_ids, "mode": mode,
        "stagger_seconds": stagger, "due_ms": due,
        "expected": [list(p) for p in fan_out(profile_ids, mode, stagger, due)],
    })


def conditions_case(name, conditions, env):
    ok, reason = evaluate_conditions(conditions, env)
    CASES.append({
        "kind": "conditions", "name": name, "conditions": conditions,
        "env": env, "expected_ok": ok, "expected_reason": reason,
    })


# --- interval -------------------------------------------------------------
anchor = {"anchor": "2026-10-25T00:00:00Z"}  # 1792886400000
next_case("interval_fixed_grid", {"type": "interval", "every_minutes": 30, **anchor}, T0, next5=True)
next_case("interval_fixed_boundary", {"type": "interval", "every_minutes": 30, **anchor},
          1792886400000 + 90 * M)  # exactly on the third grid point -> next is +30min
next_case("interval_no_anchor_starts_after", {"type": "interval", "every_minutes": 15}, T0)
next_case("interval_previous_end", {"type": "interval", "every_minutes": 10, "from": "previous_end", **anchor},
          T0, {"last_run_end_ms": T0 - 2 * M})
next_case("interval_previous_end_stale", {"type": "interval", "every_minutes": 10, "from": "previous_end", **anchor},
          T0, {"last_run_end_ms": T0 - 3 * H})
next_case("interval_end_at", {"type": "interval", "every_minutes": 30, **anchor,
                              "limits": {"end_at": "2026-10-25T15:15:00Z"}}, T0)
next_case("interval_max_total", {"type": "interval", "every_minutes": 30, **anchor,
                                 "limits": {"max_total_runs": 5}}, T0, {"runs_total": 5})
next_case("interval_max_total_under", {"type": "interval", "every_minutes": 30, **anchor,
                                       "limits": {"max_total_runs": 5}}, T0, {"runs_total": 4})
next_case("interval_consecutive_failures", {"type": "interval", "every_minutes": 30, **anchor,
                                            "limits": {"stop_after_consecutive_failures": 3}},
          T0, {"consecutive_failures": 3})
next_case("interval_per_day_blocks_rest_of_utc_day", {"type": "interval", "every_minutes": 30, **anchor,
                                                      "limits": {"max_runs_per_day": 4}},
          T0, {"runs_today": 4, "day_key": "2026-10-25"}, next5=True)
next_case("interval_per_day_next_day_fresh", {"type": "interval", "every_minutes": 30, **anchor,
                                              "limits": {"max_runs_per_day": 4}},
          T0, {"runs_today": 4, "day_key": "2026-10-24"}, next5=True)

# --- daily / weekly -------------------------------------------------------
next_case("daily_two_times_kolkata", {"type": "daily", "times": ["09:00", "18:30"], "timezone": "Asia/Kolkata"},
          T0, next5=True)
next_case("daily_before_all_times_utc", {"type": "daily", "times": ["09:00", "18:30"], "timezone": "UTC"},
          1792886400000, next5=True)
next_case("weekly_subset", {"type": "weekly", "days": [1, 3, 5], "times": ["08:15"], "timezone": "UTC"},
          T0, next5=True)  # Sunday -> Monday
next_case("weekly_all_days_from_list", {"type": "weekly", "days": [7, 1], "times": ["23:00"], "timezone": "UTC"},
          T0, next5=True)

# --- cron -----------------------------------------------------------------
next_case("cron_every_10_minutes", {"type": "cron", "cron": "*/10 * * * *", "timezone": "UTC"}, T0, next5=True)
next_case("cron_hour_range_weekdays", {"type": "cron", "cron": "0 9-17 * * 1-5", "timezone": "UTC"},
          T0, next5=True)  # Sunday 15:00 -> Monday 09:00
next_case("cron_dom_or_dow", {"type": "cron", "cron": "0 12 13 * 1", "timezone": "UTC"},
          1792886400000, next5=True)  # 13th OR Monday
next_case("cron_month_window", {"type": "cron", "cron": "0 0 1 11,12 *", "timezone": "UTC"},
          T0, next5=True)  # next: Nov 1
next_case("cron_dow_sunday_seven", {"type": "cron", "cron": "30 4 * * 7", "timezone": "UTC"},
          T0, next5=True)  # 7 == Sunday; today is Sunday but 04:30 passed
next_case("cron_list_minutes", {"type": "cron", "cron": "5,35 2 * * *", "timezone": "UTC"}, T0)

# --- once / window --------------------------------------------------------
next_case("once_future", {"type": "once", "at": "2026-11-01T09:00:00+05:30"}, T0)
next_case("once_past_is_done", {"type": "once", "at": "2026-10-20T09:00:00+05:30"}, T0)
next_case("window_grid_inside", {"type": "window", "start": "09:00", "end": "17:00", "every_minutes": 120,
                                 "timezone": "UTC"}, T0, next5=True)  # Sunday: next window Monday
next_case("window_inside_same_day", {"type": "window", "start": "09:00", "end": "17:00", "every_minutes": 60,
                                     "timezone": "UTC"}, 1792886400000 + 9 * H + 5 * M, next5=True)
next_case("window_days_filter", {"type": "window", "start": "09:00", "end": "10:00", "every_minutes": 20,
                                 "days": [1], "timezone": "UTC"}, T0, next5=True)

# --- misfire / fan-out / conditions ---------------------------------------
misfire_case("misfire_skip", "skip", [T0 - 3 * H, T0 - 2 * H, T0 - H])
misfire_case("misfire_run_once", "run_once", [T0 - 3 * H, T0 - 2 * H, T0 - H])
misfire_case("misfire_catch_up_two", {"catch_up": 2}, [T0 - 3 * H, T0 - 2 * H, T0 - H])
misfire_case("misfire_catch_up_beyond", {"catch_up": 5}, [T0 - 2 * H, T0 - H])
misfire_case("misfire_empty", "run_once", [])

fanout_case("fanout_sequential", ["p1", "p2", "p3"], "sequential", 0, T0)
fanout_case("fanout_staggered", ["p1", "p2", "p3"], "staggered", 30, T0)
fanout_case("fanout_parallel", ["p1", "p2", "p3"], "parallel", 30, T0)

conditions_case("conditions_pass_all", {"charging": True, "battery_min": 30, "unmetered": True},
                {"charging": True, "battery_pct": 55, "unmetered": True})
conditions_case("conditions_fail_charging", {"charging": True}, {"charging": False, "battery_pct": 55, "unmetered": True})
conditions_case("conditions_fail_battery", {"battery_min": 30}, {"charging": False, "battery_pct": 12, "unmetered": True})
conditions_case("conditions_fail_unmetered", {"unmetered": True}, {"charging": True, "battery_pct": 90, "unmetered": False})
conditions_case("conditions_fail_screen_awake", {"screen_awake": True}, {"screen_awake": False})
conditions_case("conditions_fail_screen_unlocked", {"screen_unlocked": True}, {"screen_unlocked": False})
conditions_case("conditions_window_inside", {"window": {"start": "09:00", "end": "17:00", "timezone": "UTC"}},
                {"now_ms": 1792886400000 + 10 * H})
conditions_case("conditions_window_day_fail", {"window": {"start": "09:00", "end": "17:00", "days": [1], "timezone": "UTC"}},
                {"now_ms": 1792886400000 + 10 * H})  # Sunday vs Monday-only
conditions_case("conditions_empty_is_true", {}, {"now_ms": T0})


def main():
    doc = {
        "description": "Shared schedule-engine vectors: Python reference (autohost_ref/schedule.py) and the "
                       "Kotlin :core:engine ScheduleEngine must agree on every case. Generated by "
                       "tools/gen_schedule_vectors.py; regenerate only with a recorded decision.",
        "epoch": "2026-10-25",
        "cases": CASES,
    }
    out = pathlib.Path(__file__).resolve().parents[1] / "testing" / "golden" / "schedule_vectors.json"
    out.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out} with {len(CASES)} cases")


if __name__ == "__main__":
    main()
