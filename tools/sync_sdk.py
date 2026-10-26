#!/usr/bin/env python3
"""Keep runtime/python/src/main/python/autohost identical to sdk-python/autohost (the .py files only).

  python tools/sync_sdk.py          # copy
  python tools/sync_sdk.py --check  # exit 1 if they differ (CI)
"""
import pathlib
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "sdk-python" / "autohost"
DST = ROOT / "runtime" / "python" / "src" / "main" / "python" / "autohost"


def files(base: pathlib.Path) -> dict:
    return {p.relative_to(base).as_posix(): p.read_bytes() for p in base.rglob("*.py") if "__pycache__" not in p.parts}


def main() -> int:
    want, have = files(SRC), (files(DST) if DST.exists() else {})
    if "--check" in sys.argv:
        if want != have:
            diff = sorted(set(want) ^ set(have)) or sorted(k for k in want if want[k] != have.get(k))
            print("SDK copy is out of sync:", ", ".join(diff[:10]))
            return 1
        print("SDK copy in sync")
        return 0
    if DST.exists():
        shutil.rmtree(DST)
    for rel, data in want.items():
        out = DST / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(data)
    print(f"copied {len(want)} files to {DST.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
