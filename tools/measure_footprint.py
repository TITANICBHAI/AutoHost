#!/usr/bin/env python3
"""P15-01 (ADR-093): the footprint measurement harness — APK size per flavor/ABI, cold start,
resident memory, worker spawn time — from one connected device via adb.

Under owner decision D-25 there is NO device to run this against: the harness is written and
reviewed, and its numbers land in `docs/PERF.md` only if the owner ever re-enables device work.

Usage (PC with adb, one device attached):
    python tools/measure_footprint.py --apk app/build/outputs/apk/corePy/release/app-core-py-release.apk
    python tools/measure_footprint.py --apk <path> --runs 5 --package app.autohost

Output: a table (stdout, and `--json` for docs/PERF.md) with
  - APK size (bytes) per requested artifact;
  - cold start (am start after force-stop; wall clock, N runs, p50/max);
  - resident memory (dumpsys meminfo total PSS of the main process + each :w* worker);
  - worker spawn time (first `ps -A | grep :w` observation after a Python run starts — best effort).

The harness NEVER writes to the device beyond installing the APK it was given.
"""
from __future__ import annotations

import argparse
import json
import statistics
import subprocess
import sys
import time

MAIN_PROCESS = "app.autohost"


def adb(*args: str) -> str:
    out = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=120)
    if out.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {out.stderr.strip()}")
    return out.stdout


def apk_size(apk: str) -> int:
    import os

    return os.path.getsize(apk)


def install(apk: str) -> None:
    adb("install", "-r", apk)


def cold_start_ms(runs: int) -> list[int]:
    """force-stop → am start → poll `dumpsys activity` for the resumed activity; wall clock per run."""
    samples: list[int] = []
    for _ in range(runs):
        adb("shell", "am", "force-stop", MAIN_PROCESS)
        time.sleep(1.0)
        t0 = time.monotonic()
        adb("shell", "am", "start", "-n", f"{MAIN_PROCESS}/.MainActivity")
        while time.monotonic() - t0 < 30:
            dump = adb("shell", "dumpsys", "activity", "activities")
            if "RESUMED" in dump and MAIN_PROCESS in dump:
                samples.append(int((time.monotonic() - t0) * 1000))
                break
            time.sleep(0.05)
        else:
            raise RuntimeError("cold start never reached RESUMED within 30 s")
    return samples


def resident_memory() -> dict:
    """PSS totals for the main process and every :w* worker (kb)."""
    meminfo = adb("shell", "dumpsys", "meminfo", MAIN_PROCESS)
    total = None
    for line in meminfo.splitlines():
        if "TOTAL PSS" in line or (line.startswith("TOTAL") and "PSS" in line):
            parts = line.split()
            if len(parts) >= 2:
                total = int(parts[1])
                break
    workers = adb("shell", "ps", "-A")
    worker_pids = [l.split()[1] for l in workers.splitlines() if f"{MAIN_PROCESS}:w" in l]
    worker_kb = {}
    for pid in worker_pids:
        w = adb("shell", "cat", f"/proc/{pid}/statm").split()
        worker_kb[pid] = int(w[1]) * 4  # pages → kb (4 kB pages)
    return {"main_pss_kb": total, "workers": worker_kb}


def worker_spawn_ms() -> int | None:
    """Best effort: after a start attempt, poll ps for the first :w* worker (one-shot sample)."""
    adb("shell", "am", "start", "-n", f"{MAIN_PROCESS}/.MainActivity")
    t0 = time.monotonic()
    while time.monotonic() - t0 < 30:
        workers = adb("shell", "ps", "-A")
        if f"{MAIN_PROCESS}:w" in workers:
            return int((time.monotonic() - t0) * 1000)
        time.sleep(0.05)
    return None


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--apk", required=True, help="path to the APK to install and measure")
    ap.add_argument("--runs", type=int, default=5, help="cold-start sample count")
    ap.add_argument("--json", action="store_true", help="emit a JSON record for docs/PERF.md")
    ap.add_argument("--skip-install", action="store_true", help="the APK is already installed")
    args = ap.parse_args()

    size = apk_size(args.apk)
    install(args.apk) if not args.skip_install else None
    cold = cold_start_ms(args.runs)
    mem = resident_memory()
    spawn = worker_spawn_ms()

    record = {
        "apk": args.apk,
        "apk_bytes": size,
        "cold_start_ms": {"samples": cold, "p50": int(statistics.median(cold)), "max": max(cold)},
        "resident": mem,
        "worker_spawn_ms": spawn,
        "device": adb("shell", "getprop", "ro.product.model").strip(),
        "android": adb("shell", "getprop", "ro.build.version.release").strip(),
        "note": "under D-25 this harness has never been executed; numbers land only if device work is re-enabled",
    }
    if args.json:
        print(json.dumps(record, indent=1))
    else:
        print(f"APK: {record['apk_bytes']} bytes")
        print(f"cold start: p50 {record['cold_start_ms']['p50']} ms / max {record['cold_start_ms']['max']} ms ({args.runs} runs)")
        print(f"main PSS: {mem['main_pss_kb']} kb; workers: {mem['workers']}")
        print(f"worker spawn: {spawn} ms")
        print(f"device: {record['device']} (Android {record['android']})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
