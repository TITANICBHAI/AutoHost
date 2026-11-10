#!/usr/bin/env python3
"""Generate testing/golden/schedule_form_vectors.json: the executable spec of the schedule *form*.

The Schedules screen no longer asks anyone to type a rule as JSON (UI_SPEC S10). It shows a normal form;
the Kotlin `ScheduleForm` (pure class, JVM unit test) turns the form into the same `ScheduleRule` the
engine already runs. This file pins that mapping:

  * `form_to_rule` below is the reference mapping; the Kotlin class must produce an equal rule for every
    valid case and exactly the listed error keys for every invalid case;
  * every produced rule is parsed by the Python reference scheduler (`autohost_ref.schedule.parse_rule`)
    and `expected_next5` comes from `next_fires`, so the rules are proven to be rules the engine accepts.

Regenerate only when the form contract changes, and record why in docs/DECISIONS.md.
"""
import datetime as dt
import json
import pathlib
import sys
from zoneinfo import ZoneInfo

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "sdk-python"))

from autohost_ref.schedule import next_fires, parse_rule  # noqa: E402

M = 60_000
H = 3_600_000
T0 = 1792940400000  # 2026-10-25T15:00:00Z, a Sunday (same epoch as schedule_vectors.json)
TASK = "app.autohost.sample.hello"
RULE_ID = "sch_test"  # the Kotlin test passes this id to ScheduleForm.toRule; production ids come from Ids
MAX_EVERY_MINUTES = 7 * 24 * 60


def _hhmm_ok(value) -> bool:
    try:
        parts = str(value).split(":")
        return len(parts) == 2 and len(parts[0]) == 2 and len(parts[1]) == 2 \
            and 0 <= int(parts[0]) <= 23 and 0 <= int(parts[1]) <= 59
    except ValueError:
        return False


def _zone(name):
    try:
        return ZoneInfo(name)
    except Exception:
        return None


def _iso(moment: dt.datetime) -> str:
    """ISO-8601 with an offset, `Z` for UTC: the same text java.time's ISO_OFFSET_DATE_TIME prints."""
    text = moment.replace(microsecond=0).isoformat()
    return text[:-6] + "Z" if text.endswith("+00:00") else text


def _every_minutes(form) -> int:
    every = int(form.get("every", 0))
    return every * 60 if form.get("unit") == "hours" else every


def validate(form: dict, now_ms: int) -> list:
    """Error keys (stable identifiers; the UI maps them to words). Empty list = the form is usable."""
    errors = []
    if not form.get("task"):
        errors.append("no_task")
    zone = _zone(form.get("zone"))
    if zone is None:
        errors.append("unknown_zone")
    repeat = form.get("repeat")
    if repeat in ("interval", "window"):
        minutes = _every_minutes(form)
        if minutes < 1:
            errors.append("every_below_one")
        elif minutes > MAX_EVERY_MINUTES:
            errors.append("every_too_large")
    if repeat in ("daily", "weekly"):
        times = form.get("times") or []
        if not times:
            errors.append("no_times")
        elif not all(_hhmm_ok(t) for t in times):
            errors.append("bad_time")
    if repeat == "weekly":
        days = form.get("days") or []
        if not days:
            errors.append("no_days")
        elif not all(isinstance(d, int) and 1 <= d <= 7 for d in days):
            errors.append("bad_days")
    if repeat == "window":
        if not (_hhmm_ok(form.get("from")) and _hhmm_ok(form.get("to"))):
            errors.append("bad_time")
        elif form["from"] >= form["to"]:
            errors.append("window_end_not_after_start")
        days = form.get("days") or []
        if days and not all(isinstance(d, int) and 1 <= d <= 7 for d in days):
            errors.append("bad_days")
    if repeat == "once" and zone is not None:
        try:
            local = dt.datetime.fromisoformat(f"{form.get('date')}T{form.get('time')}:00")
        except ValueError:
            errors.append("bad_date")
        else:
            zoned = local.replace(tzinfo=zone)
            round_trip = zoned.astimezone(dt.timezone.utc).astimezone(zone).replace(tzinfo=None)
            if round_trip != local:
                errors.append("time_does_not_exist")  # inside a daylight-saving gap
            elif int(zoned.timestamp() * 1000) <= now_ms:
                errors.append("once_in_past")
    misfire = form.get("misfire", "skip")
    if isinstance(misfire, dict) and not (isinstance(misfire.get("catch_up"), int) and misfire["catch_up"] >= 1):
        errors.append("bad_misfire")
    if repeat not in ("interval", "daily", "weekly", "once", "window"):
        errors.append("bad_repeat")
    return errors


def form_to_rule(form: dict, rule_id: str, now_ms: int) -> dict:
    """The mapping. Keys with default values are left out, exactly as a hand-written rule would be."""
    zone = ZoneInfo(form["zone"])
    repeat = form["repeat"]
    rule = {"id": rule_id, "task": form["task"], "type": repeat, "timezone": form["zone"]}
    if repeat == "interval":
        rule["every_minutes"] = _every_minutes(form)
        rule["from"] = "fixed"
        # anchored at creation time so "every 30 minutes" is a fixed grid, not re-anchored at each re-arm
        # (format with ISO_OFFSET_DATE_TIME in Kotlin: OffsetDateTime.toString() drops zero seconds)
        rule["anchor"] = _iso(dt.datetime.fromtimestamp(now_ms / 1000, zone))
    elif repeat in ("daily", "weekly"):
        rule["times"] = sorted(form["times"])
        if repeat == "weekly":
            rule["days"] = sorted(set(form["days"]))
    elif repeat == "once":
        local = dt.datetime.fromisoformat(f"{form['date']}T{form['time']}:00")
        rule["at"] = _iso(local.replace(tzinfo=zone))
    elif repeat == "window":
        rule["every_minutes"] = _every_minutes(form)
        rule["start"] = form["from"]
        rule["end"] = form["to"]
        if form.get("days"):
            rule["days"] = sorted(set(form["days"]))
    misfire = form.get("misfire", "skip")
    if misfire != "skip":
        rule["misfire"] = misfire
    if form.get("profile_ids"):
        rule["profile_ids"] = list(form["profile_ids"])
    if form.get("enabled", True) is False:
        rule["enabled"] = False
    return rule


CASES = []


def valid(name, form, after=None, now=T0):
    form = {"task": TASK, "zone": "Asia/Kolkata", **form}
    errors = validate(form, now)
    assert not errors, (name, errors)
    rule = form_to_rule(form, RULE_ID, now)
    parsed = parse_rule(dict(rule))  # the engine's own reference parser must accept it
    after_ms = after if after is not None else now
    CASES.append({
        "name": name, "form": form, "now_ms": now, "rule_id": RULE_ID, "rule": rule,
        "after_ms": after_ms, "expected_next5": next_fires(parsed, after_ms, None, 5),
    })


def invalid(name, form, errors, now=T0):
    form = {"task": TASK, "zone": "Asia/Kolkata", **form}
    got = validate(form, now)
    assert got == errors, (name, got, errors)
    CASES.append({"name": name, "form": form, "now_ms": now, "errors": errors})


# --- interval: a fixed grid anchored at creation time -------------------------------------------
valid("every_30_minutes", {"repeat": "interval", "every": 30, "unit": "minutes"}, after=T0 + 7 * M)
valid("every_2_hours", {"repeat": "interval", "every": 2, "unit": "hours"}, after=T0 + 7 * M)
valid("every_15_minutes_utc_phone", {"repeat": "interval", "every": 15, "unit": "minutes", "zone": "UTC"})

# --- daily / weekly: wall-clock times in the PHONE's zone (never UTC by default) --------------------
valid("daily_0900_kolkata", {"repeat": "daily", "times": ["09:00"]})
valid("daily_two_times_sorted", {"repeat": "daily", "times": ["18:30", "09:00"]})
valid("daily_0015_half_hour_zone", {"repeat": "daily", "times": ["00:15"]})
valid("daily_0900_berlin_across_dst_change", {"repeat": "daily", "times": ["09:00"], "zone": "Europe/Berlin"},
      # 2027-03-26T12:00:00Z (Fri): the next fires cross the 2027-03-28 spring-forward (09:00 CET -> 09:00 CEST)
      after=int(dt.datetime(2027, 3, 26, 12, tzinfo=dt.timezone.utc).timestamp() * 1000))
valid("weekly_mon_wed_0900", {"repeat": "weekly", "days": [3, 1], "times": ["09:00"]})
valid("weekly_weekdays_0815", {"repeat": "weekly", "days": [1, 2, 3, 4, 5], "times": ["08:15"]})
valid("weekly_sunday_only", {"repeat": "weekly", "days": [7], "times": ["23:00"]})

# --- once ----------------------------------------------------------------------------------------
valid("once_tomorrow_0900", {"repeat": "once", "date": "2026-10-26", "time": "09:00"})

# --- window: every N minutes between two times ----------------------------------------------------
valid("window_every_15_min_9_to_17_weekdays",
      {"repeat": "window", "every": 15, "unit": "minutes", "from": "09:00", "to": "17:00", "days": [1, 2, 3, 4, 5]})
valid("window_every_hour_all_days", {"repeat": "window", "every": 1, "unit": "hours", "from": "08:00", "to": "12:00"})

# --- options ---------------------------------------------------------------------------------------
valid("misfire_run_once", {"repeat": "daily", "times": ["09:00"], "misfire": "run_once"})
valid("misfire_catch_up_3", {"repeat": "daily", "times": ["09:00"], "misfire": {"catch_up": 3}})
valid("two_profiles", {"repeat": "daily", "times": ["09:00"], "profile_ids": ["p1", "p2"]})
valid("disabled_rule", {"repeat": "daily", "times": ["09:00"], "enabled": False})

# --- invalid forms: the error KEYS are the contract (the UI turns keys into plain sentences) --------
invalid("no_task_chosen", {"task": "", "repeat": "daily", "times": ["09:00"]}, ["no_task"])
invalid("interval_zero", {"repeat": "interval", "every": 0, "unit": "minutes"}, ["every_below_one"])
invalid("interval_too_large", {"repeat": "interval", "every": 200, "unit": "hours"}, ["every_too_large"])
invalid("daily_without_times", {"repeat": "daily", "times": []}, ["no_times"])
invalid("daily_bad_time", {"repeat": "daily", "times": ["9:00"]}, ["bad_time"])
invalid("weekly_without_days", {"repeat": "weekly", "days": [], "times": ["09:00"]}, ["no_days"])
invalid("weekly_bad_day", {"repeat": "weekly", "days": [0], "times": ["09:00"]}, ["bad_days"])
invalid("once_in_the_past", {"repeat": "once", "date": "2026-10-25", "time": "08:00"}, ["once_in_past"])
invalid("once_bad_date", {"repeat": "once", "date": "2026-13-40", "time": "08:00"}, ["bad_date"])
invalid("once_inside_dst_gap",
        {"repeat": "once", "date": "2027-03-28", "time": "02:30", "zone": "Europe/Berlin"}, ["time_does_not_exist"])
invalid("window_end_before_start",
        {"repeat": "window", "every": 15, "unit": "minutes", "from": "17:00", "to": "09:00"}, ["window_end_not_after_start"])
invalid("unknown_zone", {"repeat": "daily", "times": ["09:00"], "zone": "Mars/Olympus"}, ["unknown_zone"])
invalid("catch_up_zero", {"repeat": "daily", "times": ["09:00"], "misfire": {"catch_up": 0}}, ["bad_misfire"])


def build_doc() -> dict:
    return {
        "description": "Executable spec of the schedule form (UI_SPEC S10): form -> ScheduleRule, validated by the "
                       "Python reference scheduler. Generated by tools/gen_schedule_form_vectors.py; the Kotlin "
                       "ScheduleForm must produce an equal rule for every case with `rule` and exactly the listed "
                       "error keys for every case with `errors`.",
        "epoch": "2026-10-25",
        "form_fields": {
            "task": "task id", "repeat": "interval|daily|weekly|once|window", "every": "int", "unit": "minutes|hours",
            "times": "HH:MM list", "days": "ISO weekdays 1=Mon..7=Sun", "date": "YYYY-MM-DD (once)",
            "time": "HH:MM (once)", "from": "HH:MM (window)", "to": "HH:MM (window)",
            "zone": "IANA zone id = the phone's zone", "misfire": "skip|run_once|{catch_up:n}",
            "profile_ids": "list", "enabled": "bool",
        },
        "cases": CASES,
    }


def main():
    doc = build_doc()
    out = ROOT / "testing" / "golden" / "schedule_form_vectors.json"
    out.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {out} with {len(CASES)} cases")


if __name__ == "__main__":
    main()
