"""Digest JUnit XML test reports: one line per failing test with its assertion message and
the first source frame. Used by CI's "Assertion details" step (annotations are the only
failure channel readable without repository admin access)."""
import re
import sys
from pathlib import Path


def digest_file(path: Path):
    xml = path.read_text(encoding="utf-8", errors="replace")
    for m in re.finditer(
        r'<testcase name="([^"]+)" classname="([^"]+)"[^>]*>\s*'
        r'<failure(?: message="([^"]*)")?([^>]*)>(.{0,300}?)</failure>',
        xml,
        re.DOTALL,
    ):
        name, cls, msg, _attrs, body = m.groups()
        msg = (msg or "").replace("&quot;", '"').replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        frame_m = re.search(r"at ([^(\n]+\(\w+\.kt:\d+\))", body)
        frame = frame_m.group(1) if frame_m else ""
        yield f"{cls}.{name} :: {msg[:140]} :: {frame}"


def main(argv):
    root = Path(argv[1]) if len(argv) > 1 else Path(".")
    count = 0
    for f in sorted(root.rglob("TEST-*.xml")):
        for line in digest_file(f):
            print(line)
            count += 1
            if count >= 40:
                return 0
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
