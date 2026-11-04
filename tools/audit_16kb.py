#!/usr/bin/env python3
"""P15-05 (ADR-093): the 16 KB page-size audit for every shipped native library.

Play's 16 KB requirement applies from 2027-02-01 for targetSdk >= 35 (verify the current rule
before acting — `docs/GATES.md` G15-c). The audit checks each .so in an APK for 16 KB ELF
LOAD-segment alignment. Under D-26 (no builds) this has never been executed; it runs on a PC
with the NDK's llvm-readelf (or falls back to a pure-python ELF parse of the program headers).

Usage:
    python tools/audit_16kb.py --apk app/build/outputs/apk/corePy/release/app-core-py-release.apk
Output: one line per .so (OK / MISALIGNED with the offending max alignment), exit 1 on any
misalignment, so CI could gate it once device/CI work resumes.
"""
from __future__ import annotations

import argparse
import json
import struct
import sys
import zipfile


def elf_max_load_alignment(data: bytes) -> int:
    """Pure-python ELF program-header scan: max p_align over PT_LOAD segments (works for
    32/64-bit, little-endian; enough for the 16 KB check)."""
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    is64 = data[4] == 2
    little = data[5] == 1
    fmt = "<" if little else ">"
    if is64:
        e_phoff = struct.unpack_from(fmt + "Q", data, 0x20)[0]
        e_phentsize = struct.unpack_from(fmt + "H", data, 0x36)[0]
        e_phnum = struct.unpack_from(fmt + "H", data, 0x38)[0]
        # p_align is the last 8 bytes of a 56-byte Phdr
        aligns = [struct.unpack_from(fmt + "Q", data, e_phoff + i * e_phentsize + 0x30)[0]
                  for i in range(e_phnum)
                  if struct.unpack_from(fmt + "I", data, e_phoff + i * e_phentsize)[0] == 1]  # PT_LOAD
    else:
        e_phoff = struct.unpack_from(fmt + "I", data, 0x1C)[0]
        e_phentsize = struct.unpack_from(fmt + "H", data, 0x2A)[0]
        e_phnum = struct.unpack_from(fmt + "H", data, 0x2C)[0]
        aligns = [struct.unpack_from(fmt + "I", data, e_phoff + i * e_phentsize + 0x1C)[0]
                  for i in range(e_phnum)
                  if struct.unpack_from(fmt + "I", data, e_phoff + i * e_phentsize)[0] == 1]
    return max(aligns) if aligns else 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--min-alignment", type=int, default=16384, help="required alignment (bytes)")
    args = ap.parse_args()

    rows = []
    failed = False
    with zipfile.ZipFile(args.apk) as zf:
        for info in zf.infolist():
            if not info.filename.endswith(".so"):
                continue
            data = zf.read(info)
            try:
                align = elf_max_load_alignment(data)
            except ValueError as e:
                rows.append({"lib": info.filename, "error": str(e)})
                failed = True
                continue
            ok = align >= args.min_alignment
            failed = failed or not ok
            rows.append({"lib": info.filename, "max_load_align": align, "ok": ok})

    if args.json:
        print(json.dumps(rows, indent=1))
    else:
        for r in rows:
            if "error" in r:
                print(f"ERROR {r['lib']}: {r['error']}")
            else:
                print(f"{'OK        ' if r['ok'] else 'MISALIGNED'} {r['lib']}: {r['max_load_align']} bytes")
        print(f"{len(rows)} native libraries; required alignment {args.min_alignment} bytes")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
