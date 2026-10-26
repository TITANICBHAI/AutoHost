"""P4 tests: the schedule reference (autohost_ref.schedule) — semantics + golden vectors.

Hand-computed assertions pin the documented semantics; the committed golden file
(testing/golden/schedule_vectors.json) pins the exact numbers shared with the Kotlin engine.
"""
from __future__ import annotations

import datetime as _dt
import json
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2]))

from autohost_ref.schedule import (  # noqa: E402
    evaluate_conditions,
    fan_out,
    next_fire,
    next_fires,
    parse_cron,
    parse_rule,
    resolve_misfire,
)

UTC = _dt.timezone.utc
M = 60_000
H = 3_600_000
D = 86_400_000
T0 = 1792940400000  # 2026-10-25T15:00:00Z, a Sunday
ANCHOR = 1792886400000  # 2026-10-25T00:00:00Z, same Sunday at midnight

REPO = pathlib.Path(__file__).resolve().parents[2]


def rule(**kw):
    base = {"id": "sch_test", "task": "t", "timezone": "UTC"}
    base.update(kw)
    return parse_rule(base)


def utc(ms: int) -> _dt.datetime:
    return _dt.datetime.fromtimestamp(ms / 1000, tz=UTC)


class IntervalTests(unittest.TestCase):
    def test_fixed_grid_uses_anchor(self):
        r = rule(type="interval", every_minutes=30, anchor="2026-10-25T00:00:00Z")
        # T0 (15:00Z) sits exactly on the grid -> strictly-after wins -> 15:30Z
        self.assertEqual(T0 + 30 * M, next_fire(r, T0))
        # exactly on a grid point -> strictly-after wins
        self.assertEqual(ANCHOR + 60 * M, next_fire(r, ANCHOR + 30 * M))

    def test_without_anchor_grid_starts_after(self):
        r = rule(type="interval", every_minutes=15)
        self.assertEqual(T0 + 15 * M, next_fire(r, T0))

    def test_previous_end_mode(self):
        r = rule(type="interval", every_minutes=10, **{"from": "previous_end"},
                 anchor="2026-10-25T00:00:00Z")
        st = {"last_run_end_ms": T0 - 2 * M}
        self.assertEqual(T0 + 8 * M, next_fire(r, T0, st))
        # stale end falls back to the anchor grid
        st = {"last_run_end_ms": T0 - 3 * H}
        self.assertEqual(ANCHOR + ((T0 - ANCHOR) // (10 * M) + 1) * 10 * M, next_fire(r, T0, st))

    def test_end_at_ends_the_rule(self):
        r = rule(type="interval", every_minutes=30, anchor="2026-10-25T00:00:00Z",
                 limits={"end_at": "2026-10-25T15:15:00Z"})
        self.assertIsNone(next_fire(r, T0))

    def test_max_total_runs(self):
        r = rule(type="interval", every_minutes=30, anchor="2026-10-25T00:00:00Z",
                 limits={"max_total_runs": 5})
        self.assertIsNone(next_fire(r, T0, {"runs_total": 5}))
        self.assertIsNotNone(next_fire(r, T0, {"runs_total": 4}))

    def test_consecutive_failures_stop(self):
        r = rule(type="interval", every_minutes=30, anchor="2026-10-25T00:00:00Z",
                 limits={"stop_after_consecutive_failures": 3})
        self.assertIsNone(next_fire(r, T0, {"consecutive_failures": 3}))
        self.assertIsNotNone(next_fire(r, T0, {"consecutive_failures": 2}))

    def test_per_day_limit_blocks_rest_of_that_day_only(self):
        r = rule(type="interval", every_minutes=30, anchor="2026-10-25T00:00:00Z",
                 limits={"max_runs_per_day": 4})
        st = {"runs_today": 4, "day_key": "2026-10-25"}
        nxt = next_fire(r, T0, st)
        self.assertEqual(ANCHOR + D, nxt)  # next UTC midnight
        # a fresh counter day is not blocked
        st2 = {"runs_today": 4, "day_key": "2026-10-24"}
        self.assertEqual(T0 + 30 * M, next_fire(r, T0, st2))


class DailyWeeklyTests(unittest.TestCase):
    def test_daily_picks_next_time_in_tz(self):
        r = rule(type="daily", times=["09:00", "18:30"], timezone="Asia/Kolkata")
        nxt = next_fire(r, T0)
        local = utc(nxt).astimezone(_dt.timezone(_dt.timedelta(hours=5, minutes=30)))
        # T0 is 20:30 IST -> both of today's times passed -> tomorrow 09:00 IST
        self.assertEqual((9, 0), (local.hour, local.minute))
        self.assertEqual("2026-10-26", local.strftime("%Y-%m-%d"))

    def test_daily_rolls_to_next_day(self):
        r = rule(type="daily", times=["09:00"], timezone="UTC")
        nxt = next_fire(r, T0)
        self.assertEqual("2026-10-26", utc(nxt).strftime("%Y-%m-%d"))
        self.assertEqual((9, 0), (utc(nxt).hour, utc(nxt).minute))

    def test_weekly_subset_skips_days(self):
        r = rule(type="weekly", days=[1, 3, 5], times=["08:15"], timezone="UTC")
        nxt = next_fire(r, T0)  # Sunday -> Monday
        self.assertEqual(1, utc(nxt).isoweekday())
        self.assertEqual((8, 15), (utc(nxt).hour, utc(nxt).minute))

    def test_preview_respects_per_day(self):
        r = rule(type="daily", times=["09:00", "10:00", "11:00"], timezone="UTC",
                 limits={"max_runs_per_day": 2})
        fires = next_fires(r, ANCHOR, {"runs_today": 0, "day_key": "2026-10-25"}, 5)
        days = [utc(f).strftime("%Y-%m-%d") for f in fires]
        self.assertEqual(["2026-10-25"] * 2 + ["2026-10-26"] * 2 + ["2026-10-27"], days)


class CronTests(unittest.TestCase):
    def test_parse_rejects_names_and_wrong_counts(self):
        with self.assertRaises(ValueError):
            parse_cron("0 9 * * MON")
        with self.assertRaises(ValueError):
            parse_cron("*/10 * * *")
        with self.assertRaises(ValueError):
            parse_cron("@daily")

    def test_every_ten_minutes(self):
        r = rule(type="cron", cron="*/10 * * * *", timezone="UTC")
        self.assertEqual(T0 + 10 * M, next_fire(r, T0))

    def test_hour_range_and_weekdays(self):
        r = rule(type="cron", cron="0 9-17 * * 1-5", timezone="UTC")
        nxt = next_fire(r, T0)  # Sunday 15:00 -> Monday 09:00
        self.assertEqual(1, utc(nxt).isoweekday())
        self.assertEqual(9, utc(nxt).hour)

    def test_dom_or_dow_semantics(self):
        r = rule(type="cron", cron="0 12 13 * 1", timezone="UTC")
        fires = next_fires(r, ANCHOR, None, 5)
        # Oct 26 Mon (dow branch), Nov 2 + Nov 9 (Mondays), Nov 13 (dom branch) — both branches fire
        self.assertEqual("2026-10-26", utc(fires[0]).strftime("%Y-%m-%d"))
        self.assertIn("2026-11-13", [utc(f).strftime("%Y-%m-%d") for f in fires])

    def test_dow_seven_is_sunday(self):
        r = rule(type="cron", cron="30 4 * * 7", timezone="UTC")
        nxt = next_fire(r, T0)  # Sunday after 04:30 -> next Sunday
        self.assertEqual("2026-11-01", utc(nxt).strftime("%Y-%m-%d"))


class OnceWindowTests(unittest.TestCase):
    def test_once_future_and_past(self):
        r = rule(type="once", at="2026-11-01T09:00:00+05:30")
        self.assertIsNotNone(next_fire(r, T0))
        past = rule(type="once", at="2026-10-20T09:00:00+05:30")
        self.assertIsNone(next_fire(past, T0))

    def test_window_grid_anchored_at_window_start(self):
        r = rule(type="window", start="09:00", end="17:00", every_minutes=120, timezone="UTC")
        nxt = next_fire(r, T0)  # Sunday 15:00 outside window -> Monday 09:00
        self.assertEqual("2026-10-26T09:00", utc(nxt).strftime("%Y-%m-%dT%H:%M"))
        fires = next_fires(r, T0, None, 3)
        self.assertEqual(["09:00", "11:00", "13:00"], [utc(f).strftime("%H:%M") for f in fires])

    def test_window_mid_window_start(self):
        r = rule(type="window", start="09:00", end="17:00", every_minutes=60, timezone="UTC")
        after = ANCHOR + 9 * H + 5 * M
        self.assertEqual(ANCHOR + 10 * H, next_fire(r, after))

    def test_window_rejects_midnight_wrap(self):
        with self.assertRaises(ValueError):
            rule(type="window", start="22:00", end="04:00", every_minutes=30)


class MisfireFanoutConditionsTests(unittest.TestCase):
    def test_misfire_policies(self):
        missed = [T0 - 3 * H, T0 - 2 * H, T0 - H]
        self.assertEqual([], resolve_misfire("skip", missed))
        self.assertEqual([T0 - H], resolve_misfire("run_once", missed))
        self.assertEqual([T0 - 3 * H, T0 - 2 * H], resolve_misfire({"catch_up": 2}, missed))
        self.assertEqual([], resolve_misfire("skip", []))

    def test_fanout_modes(self):
        self.assertEqual([("p1", T0), ("p2", T0)], fan_out(["p1", "p2"], "sequential", 0, T0))
        self.assertEqual([("p1", T0), ("p2", T0 + 30_000)], fan_out(["p1", "p2"], "staggered", 30, T0))
        self.assertEqual([("p1", T0), ("p2", T0)], fan_out(["p1", "p2"], "parallel", 30, T0))

    def test_conditions_each_reason(self):
        env = {"charging": True, "battery_pct": 55, "unmetered": True, "now_ms": ANCHOR + 10 * H}
        ok, reason = evaluate_conditions({"charging": True, "battery_min": 30, "unmetered": True}, env)
        self.assertTrue(ok)
        self.assertIsNone(evaluate_conditions({}, env)[1])
        self.assertEqual("charging", evaluate_conditions({"charging": True}, {**env, "charging": False})[1])
        self.assertEqual("battery_min", evaluate_conditions({"battery_min": 30}, {**env, "battery_pct": 10})[1])
        self.assertEqual("unmetered", evaluate_conditions({"unmetered": True}, {**env, "unmetered": False})[1])
        win = {"window": {"start": "09:00", "end": "17:00", "timezone": "UTC"}}
        self.assertIsNone(evaluate_conditions(win, env)[1])
        win_day = {"window": {"start": "09:00", "end": "17:00", "days": [1], "timezone": "UTC"}}
        self.assertEqual("window_day", evaluate_conditions(win_day, env)[1])
        win_time = {"window": {"start": "09:00", "end": "17:00", "timezone": "UTC"}}
        self.assertEqual("window_time", evaluate_conditions(win_time, {**env, "now_ms": ANCHOR + 20 * H})[1])


class GoldenVectorTests(unittest.TestCase):
    """The committed vectors are the cross-implementation contract; the reference must match."""

    def test_reference_matches_committed_vectors(self):
        doc = json.loads((REPO / "testing" / "golden" / "schedule_vectors.json").read_text(encoding="utf-8"))
        self.assertTrue(doc["cases"], "no golden cases")
        for case in doc["cases"]:
            kind = case["kind"]
            if kind == "next":
                rule_json = case["rule"]
                if rule_json["type"] in ("daily", "weekly", "cron", "window"):
                    rule_json = {**rule_json, "timezone": rule_json.get("timezone", "UTC")}
                parsed = parse_rule(rule_json)
                with self.subTest(case=case["name"], kind=kind):
                    self.assertEqual(case["expected"], next_fire(parsed, case["after_ms"], case.get("state")))
                if "expected_next5" in case:
                    with self.subTest(case=case["name"], kind="next5"):
                        self.assertEqual(case["expected_next5"],
                                         next_fires(parsed, case["after_ms"], case.get("state"), 5))
            elif kind == "misfire":
                with self.subTest(case=case["name"], kind=kind):
                    self.assertEqual(case["expected"], resolve_misfire(case["policy"], case["missed"]))
            elif kind == "fanout":
                with self.subTest(case=case["name"], kind=kind):
                    got = [list(p) for p in fan_out(case["profile_ids"], case["mode"],
                                                    case["stagger_seconds"], case["due_ms"])]
                    self.assertEqual(case["expected"], got)
            elif kind == "conditions":
                with self.subTest(case=case["name"], kind=kind):
                    ok, reason = evaluate_conditions(case["conditions"], case["env"])
                    self.assertEqual(case["expected_ok"], ok)
                    self.assertEqual(case["expected_reason"], reason)
            else:
                self.fail(f"unknown vector kind {kind}")


if __name__ == "__main__":
    unittest.main()
