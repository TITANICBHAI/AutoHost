"""Digest JUnit XML test reports: one line per failing test with its assertion message and
the first source frame. Used by CI's "Assertion details" step (annotations are the only
failure channel readable without repository admin access)."""
import re
import sys
from pathlib import Path


def digest_file(path: Path):
    xml = path.read_text(encoding="utf-8", errors="replace")
    for tc in re.finditer(r'<testcase\b([^>]+)>(.*?)</testcase>', xml, re.DOTALL):
        attrs, body = tc.groups()
        name_m = re.search(r'name="([^"]+)"', attrs)
        cls_m = re.search(r'classname="([^"]+)"', attrs)
        fail_m = re.search(r'<failure\b([^>]*?)>(.*?)</failure>', body, re.DOTALL)
        if not fail_m:
            continue
        name = name_m.group(1) if name_m else "unknown"
        cls = cls_m.group(1) if cls_m else "unknown"
        fail_attrs, fail_body = fail_m.groups()
        msg_m = re.search(r'message="([^"]*)"', fail_attrs)
        msg = (msg_m.group(1) if msg_m else "").replace("&quot;", '"').replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        if not msg:
            first_line = fail_body.strip().split("\n")[0].strip()
            msg = first_line[:140]
        frame_m = re.search(r"at ([^(\n]+\(\w+\.kt:\d+\))", fail_body)
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
