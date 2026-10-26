#!/usr/bin/env python3
"""Recompute the dashboard in docs/PROGRESS.md and enforce tracker rules.

Usage:
  python tools/progress.py                 # rewrite the dashboard block in place
  python tools/progress.py --check         # validate (exit 1 on errors); never writes
  python tools/progress.py --html out.html # also write a static HTML dashboard
  python tools/progress.py --file path.md  # use another tracker file
"""
import argparse, html, pathlib, re, sys

SIZE_W = {"S": 1, "M": 2, "L": 4}
GATE_W = 2
ITEM = re.compile(r"^- \[(?P<x>[ xX])\] \*\*(?P<id>.+?)\*\*\s*(?P<rest>.*)$")
SIZE = re.compile(r"\[(S|M|L)\]\s*$")
EVID = re.compile(r"^\s+-\s+evidence:\s*(.*)$", re.I)
PHASE = re.compile(r"^##\s+Phase\s+(\d+)\s*[—-]\s*(.*)$")
H2 = re.compile(r"^##\s+(.*)$")
START, END = "<!-- DASHBOARD:START -->", "<!-- DASHBOARD:END -->"
PLACEHOLDERS = {"", "tbd", "todo", "...", "…", "_", "n/a?"}


class Item:
    def __init__(self, id_, done, rest, section, line):
        self.id, self.done, self.rest, self.section, self.line = id_, done, rest, section, line
        m = SIZE.search(rest)
        self.size = m.group(1) if m else None
        self.evidence = None  # None = no evidence line present
        self.kind = ("gate" if re.match(r"^G\d+-", id_) else "cap" if id_.startswith("CAP-")
                     else "cross" if id_.startswith("X-") else "task")
        self.wip = "(WIP)" in rest
        self.blocked = "(BLOCKED" in rest
        self.written = "(WRITTEN)" in rest
        pm = re.match(r"^[PG](\d+)-", id_)
        self.phase = int(pm.group(1)) if pm else None

    @property
    def weight(self):
        if self.kind == "gate":
            return GATE_W
        return SIZE_W.get(self.size or "S", 1)

    @property
    def needs_evidence(self):
        return self.kind == "gate" or self.size in ("M", "L")

    @property
    def has_evidence(self):
        return self.evidence is not None and self.evidence.strip().lower() not in PLACEHOLDERS \
            and not self.evidence.strip().startswith("<")


def parse(text):
    items, titles, section, last = [], {}, None, None
    for n, line in enumerate(text.splitlines(), 1):
        pm = PHASE.match(line)
        if pm:
            section = f"phase{int(pm.group(1))}"
            titles[int(pm.group(1))] = pm.group(2).strip()
            last = None
            continue
        h = H2.match(line)
        if h:
            section = h.group(1).strip().lower()
            last = None
            continue
        m = ITEM.match(line)
        if m:
            last = Item(m.group("id").strip(), m.group("x").lower() == "x", m.group("rest"), section, n)
            items.append(last)
            continue
        e = EVID.match(line)
        if e and last is not None:
            last.evidence = e.group(1)
    return items, titles


def bar(pct, width=10):
    filled = round(width * pct / 100)
    return "█" * filled + "░" * (width - filled)


def stats(group):
    total = sum(i.weight for i in group)
    done = sum(i.weight for i in group if i.done)
    return done, total, (100 * done / total if total else 0)


def written_pct(group):
    """Share of points whose code is written (ticked or tagged (WRITTEN)); verification is tracked by the tick."""
    total = sum(i.weight for i in group)
    w = sum(i.weight for i in group if i.done or i.written)
    return (100 * w / total if total else 0)


def compute(items, titles):
    phases = sorted({i.phase for i in items if i.phase is not None})
    rows, overall_items = [], []
    for p in phases:
        grp = [i for i in items if i.phase == p]
        tasks = [i for i in grp if i.kind == "task"]
        gates = [i for i in grp if i.kind == "gate"]
        d, t, pct = stats(grp)
        tasks_done, gates_done = sum(i.done for i in tasks), sum(i.done for i in gates)
        if any(i.blocked and not i.done for i in grp):
            status = "BLOCKED"
        elif tasks and gates and tasks_done == len(tasks) and gates_done == len(gates):
            status = "DONE"
        elif tasks and tasks_done == len(tasks):
            status = "GATE_PENDING"
        elif tasks_done or gates_done or any(i.wip for i in grp):
            status = "IN_PROGRESS"
        else:
            status = "NOT_STARTED"
        rows.append((p, titles.get(p, ""), status, tasks_done, len(tasks), gates_done, len(gates), pct,
                     written_pct(grp)))
        overall_items += grp
    cross = [i for i in items if i.kind == "cross"]
    caps = [i for i in items if i.kind == "cap"]
    overall_items += cross + caps
    od, ot, opct = stats(overall_items)
    return rows, cross, caps, (od, ot, opct), overall_items


def dashboard(items, titles):
    rows, cross, caps, (od, ot, opct), allitems = compute(items, titles)
    tasks = [i for i in items if i.kind in ("task", "cross")]
    gates = [i for i in items if i.kind == "gate"]
    wip = sum(1 for i in items if i.wip and not i.done)
    blocked = sum(1 for i in items if i.blocked and not i.done)
    cur = next((r for r in rows if r[2] in ("IN_PROGRESS", "GATE_PENDING", "BLOCKED")), None) \
        or next((r for r in rows if r[2] == "NOT_STARTED"), None)
    out = [
        f"**Verified: {opct:.0f}%** (weighted {od}/{ot} points)  {bar(opct, 20)}",
        f"**Code written (unverified): {written_pct(allitems):.0f}%**  {bar(written_pct(allitems), 20)}",
        f"Tasks {sum(i.done for i in tasks)}/{len(tasks)} · Gate items {sum(i.done for i in gates)}/{len(gates)} · "
        f"Capabilities {sum(i.done for i in caps)}/{len(caps)} · WIP {wip} · Blocked {blocked}",
        f"**Current phase:** {('Phase %d — %s [%s]' % (cur[0], cur[1], cur[2])) if cur else 'all done'}",
        "",
        "| Phase | Status | Tasks verified | Verified | Written | Gate |",
        "|---|---|---|---|---|---|",
    ]
    for p, title, status, td, tt, gd, gt, pct, wp in rows:
        out.append(f"| {p} {title} | {status} | {td}/{tt} | {bar(pct)} {pct:.0f}% | {wp:.0f}% | {gd}/{gt} |")
    cd, ct, cp = stats(cross) if cross else (0, 0, 0)
    out.append(f"| X Cross-cutting | – | {sum(i.done for i in cross)}/{len(cross)} | {bar(cp)} {cp:.0f}% | {written_pct(cross):.0f}% | – |")
    kd, kt, kp = stats(caps) if caps else (0, 0, 0)
    out.append(f"| CAP Capabilities | – | {sum(i.done for i in caps)}/{len(caps)} | {bar(kp)} {kp:.0f}% | {written_pct(caps):.0f}% | – |")
    return "\n".join(out)


def validate(items, text):
    errors, warns = [], []
    seen = {}
    for i in items:
        if i.id in seen:
            errors.append(f"duplicate id {i.id} (lines {seen[i.id]} and {i.line})")
        seen[i.id] = i.line
        if i.done and i.needs_evidence and not i.has_evidence:
            errors.append(f"{i.id}: ticked but evidence is missing (line {i.line})")
        if i.done and (i.wip or i.blocked):
            warns.append(f"{i.id}: ticked but still tagged (WIP)/(BLOCKED)")
    phases = sorted({i.phase for i in items if i.phase is not None})
    for k in phases:
        gates = [i for i in items if i.phase == k and i.kind == "gate" and i.done]
        open_tasks = [i.id for i in items if i.phase == k and i.kind == "task" and not i.done]
        if gates and open_tasks:
            warns.append(f"phase {k}: gate items ticked while tasks are still open: {', '.join(open_tasks[:5])}…")
    return errors, warns


def render_html(items, titles, dash_md):
    rows, cross, caps, (od, ot, opct), _ = compute(items, titles)
    def esc(s): return html.escape(s)
    parts = ["""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AutoHost progress</title><style>
:root{--bg:#fafcfc;--fg:#101414;--mut:#5f6b6d;--card:#fff;--line:#d5dede;--ok:#2e7d32;--run:#1565c0;--warn:#b26a00;--err:#c62828}
@media (prefers-color-scheme:dark){:root{--bg:#101414;--fg:#e8eeee;--mut:#9aa6a8;--card:#171d1e;--line:#2b3335;--ok:#81c784;--run:#64b5f6;--warn:#ffb74d;--err:#ef9a9a}}
body{font:15px/1.5 system-ui,sans-serif;background:var(--bg);color:var(--fg);margin:0;padding:16px;max-width:980px;margin-inline:auto}
h1{font-size:22px;margin:.2em 0}.sub{color:var(--mut)}table{border-collapse:collapse;width:100%;margin:12px 0}
th,td{border-bottom:1px solid var(--line);padding:6px 8px;text-align:left;font-size:14px}
.bar{background:var(--line);border-radius:6px;height:10px;width:140px;display:inline-block;vertical-align:middle;overflow:hidden}
.bar>i{display:block;height:100%;background:var(--run)}.DONE .bar>i{background:var(--ok)}
.st{font-size:12px;border:1px solid var(--line);border-radius:10px;padding:1px 8px}
.DONE{color:var(--ok)}.IN_PROGRESS,.GATE_PENDING{color:var(--run)}.BLOCKED{color:var(--err)}
details{background:var(--card);border:1px solid var(--line);border-radius:10px;margin:8px 0;padding:6px 12px}
summary{cursor:pointer;font-weight:600}li{list-style:none;margin:4px 0}.ev{color:var(--mut);font-size:13px;margin-left:24px}
.t{display:inline-block;width:1.4em}.sz{color:var(--mut);font-size:12px}
</style></head><body>"""]
    parts.append(f"<h1>AutoHost — build progress</h1><div class='sub'>Overall {opct:.0f}% ({od}/{ot} points)</div>")
    parts.append(f"<div class='bar' style='width:100%;height:14px'><i style='width:{opct:.0f}%'></i></div>")
    parts.append("<table><tr><th>Phase</th><th>Status</th><th>Tasks</th><th>Progress</th><th>Gate</th></tr>")
    for p, title, status, td, tt, gd, gt, pct, wp in rows:
        parts.append(f"<tr class='{status}'><td>{p} — {esc(title)}</td><td><span class='st {status}'>{status}</span></td>"
                     f"<td>{td}/{tt}</td><td><span class='bar'><i style='width:{pct:.0f}%'></i></span> {pct:.0f}% verified · {wp:.0f}% written</td><td>{gd}/{gt}</td></tr>")
    parts.append("</table>")
    def block(title, group):
        d, t, pc = stats(group)
        s = [f"<details><summary>{esc(title)} — {pc:.0f}%</summary><ul style='padding-left:0'>"]
        for i in group:
            mark = "✅" if i.done else ("⛔" if i.blocked else ("📝" if i.written else ("🟡" if i.wip else "⬜")))
            ev = f"<div class='ev'>evidence: {esc(i.evidence.strip())}</div>" if i.evidence and i.evidence.strip() else ""
            desc = re.sub(r"\s*\[(S|M|L)\]\s*$", "", i.rest)
            s.append(f"<li><span class='t'>{mark}</span><b>{esc(i.id)}</b> {esc(desc)} <span class='sz'>{i.size or ''}</span>{ev}</li>")
        s.append("</ul></details>")
        return "".join(s)
    for p, title, *_ in rows:
        parts.append(block(f"Phase {p} — {title}", [i for i in items if i.phase == p]))
    parts.append(block("Cross-cutting", cross))
    parts.append(block("Capabilities", caps))
    parts.append("</body></html>")
    return "\n".join(parts)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--file", default="docs/PROGRESS.md")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--html")
    a = ap.parse_args(argv)
    path = pathlib.Path(a.file)
    text = path.read_text(encoding="utf-8")
    items, titles = parse(text)
    dash = dashboard(items, titles)
    if START not in text or END not in text:
        print("ERROR: dashboard markers missing", file=sys.stderr)
        return 1
    block = f"{START}\n{dash}\n{END}"
    current = text[text.index(START): text.index(END) + len(END)]
    errors, warns = validate(items, text)
    if a.check:
        if current != block:
            errors.append("dashboard is stale — run `python tools/progress.py`")
        for w in warns:
            print("WARN:", w)
        for e in errors:
            print("ERROR:", e)
        print("progress check:", "FAILED" if errors else "ok")
        return 1 if errors else 0
    new = text.replace(current, block)
    path.write_text(new, encoding="utf-8")
    for w in warns:
        print("WARN:", w)
    print(dash.split("\n")[0])
    if a.html:
        pathlib.Path(a.html).write_text(render_html(items, titles, dash), encoding="utf-8")
        print("wrote", a.html)
    return 0


if __name__ == "__main__":
    sys.exit(main())
