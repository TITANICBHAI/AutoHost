#!/usr/bin/env python3
"""gen_agent_pack (P5-16): write the Skill-format agent context pack (plan §13.3 step 8).

Thin wrapper over autohost_cli.pack.write_pack so `python3 tools/gen_agent_pack.py DIR` works
without the CLI; the CLI (`autohost docs --pack DIR`) adds live availability from the device.

Usage: gen_agent_pack.py OUT_DIR [--device-info '{"api":…}']
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "sdk-python"))

from autohost_cli.pack import write_pack  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out_dir")
    parser.add_argument("--device-info", default=None, help="JSON string with device facts")
    args = parser.parse_args(argv)
    device_info = json.loads(args.device_info) if args.device_info else {}
    print(write_pack(args.out_dir, device_info=device_info))
    return 0


if __name__ == "__main__":
    sys.exit(main())
