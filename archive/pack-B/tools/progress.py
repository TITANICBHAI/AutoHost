#!/usr/bin/env python3
"""Recompute the dashboard in docs/PROGRESS.md and enforce tracker rules.

Usage:
  python tools/progress.py                 # rewrite the dashboard block in place
  python tools/progress.py --check         # validate (exit 1 on errors); never writes
  python tools/progress.py --html out.html # also write a static HTML dashboard
  python tools/progress.py --stats         # print machine-readable counts (JSON) and exit
  python tools/progress.py --file path.md  # use another tracker file

Item syntax (one line per item, optional `  - evidence:` line below it):
  - [ ] **P1-12** (STATE) description [S|M|L] (deps: P1-05, P1-06) (needs: INTEG)
Tags (all optional, any order after the id):
  (WIP) (BLOCKED: reason)            flow markers; (FAILING: reason) = a verification was run and failed
  (IMPORTED) (WRITTEN) (COMPILED) (UNIT) (INTEG) (DEVICE)
                                     evidence ladder; highest tag wins; COMPILED and above need an evidence line
  (DOC)                              documentation / process item: no code ladder, excluded from the pipeline
  (deps: ID, ID)                     tasks that must be ticked before this one can be ticked
  (needs: STATE)                     lowest ladder state at which this item may be ticked (default UNIT)
Phase header: `## Phase 12 — Title {after: 5}` = implementation of that phase may start once the gates of phases <= 5 have passed (not enforced on ticks).
The tick `[x]` means "done at the level the item requires, with evidence"; the ladder shows how far unticked work got.
Ordering (ADR-084): phase order constrains starting new implementation scope, not verification ticks. Any independently
verified task may be ticked as soon as it reaches its required level and its dependencies are ticked; a gate stays unticked
until its own procedure and evidence exist. `{after: N}` records that a phase's implementation may start once the gates up to N pass.
"""
import argparse, html, json, pathlib, re, sys

SIZE_W = {"S": 1, "M": 2, "L": 4}
GATE_W = 2
STATES = ["IMPORTED", "WRITTEN", "COMPILED", "UNIT", "INTEG", "DEVICE"]
RANK = {s: n for n, s in enumerate(STATES, 1)}
DEFAULT_NEEDS = "UNIT"
STATE_ICON = {"IMPORTED": "📥", "WRITTEN": "📝", "COMPILED": "🔨", "UNIT": "🧪", "INTEG": "🔗", "DEVICE": "📱"}
ITEM = re.compile(r"^- \[(?P<x>[ xX])\] \*\*(?P<id>.+?)\*\*\s*(?P<rest>.*)$")
SIZE = re.compile(r"\[(S|M|L)\](?!\()")
EVID = re.compile(r"^\s+-\s+evidence:\s*(.*)$", re.I)
PHASE = re.compile(r"^##\s+Phase\s+(\d+)\s*[—-]\s*(.*?)\s*(?:\{after:\s*(\d+)\})?\s*$")
H2 = re.compile(r"^##\s+(.*)$")
STATE_TAG = re.compile(r"\((" + "|".join(STATES) + r")\)")
DEPS = re.compile(r"\(deps:\s*([^)]*)\)")
NEEDS = re.compile(r"\(needs:\s*([A-Z]+)\)")
ID_TOKEN = re.compile(r"^(?:P\d+-\d+|G\d+-[a-z]|X-\d+|CAP-.+)$")
START, END = "<!-- DASHBOARD:START -->", "<!-- DASHBOARD:END -->"
PLACEHOLDERS = {"", "tbd", "todo", "...", "…", "_", "n/a?"}


class Item:
    def __init__(self, id_, done, rest, section, line):
        self.id, self.done, self.rest, self.section, self.line = id_, done, rest, section, line
        sizes = SIZE.findall(rest)
        self.size = sizes[-1] if sizes else None
        self.evidence = None  # None = no evidence line present
        self.kind = ("gate" if re.match(r"^G\d+-", id_) else "cap" if id_.startswith("CAP-")
                     else "cross" if id_.startswith("X-") else "task")
        self.wip = "(WIP)" in rest
        self.blocked = bool(re.search(r"\(BLOCKED[:)]", rest))
        self.failing = bool(re.search(r"\(FAILING[:)]", rest))
        self.doc = "(DOC)" in rest
        tags = STATE_TAG.findall(rest)
        self.state_tags = tags
        self.state = max(tags, key=lambda s: RANK[s]) if tags else None
        self.rank = RANK[self.state] if self.state else 0
        self.implied = False
        m = DEPS.search(rest)
        self.deps = [d.strip() for d in m.group(1).split(",") if d.strip()] if m else []
        n = NEEDS.search(rest)
        self.needs = n.group(1) if n else None
        pm = re.match(r"^[PG](\d+)-", id_)
        self.phase = int(pm.group(1)) if pm else None

    @property
    def written(self):  # kept for callers of the v1 API
        return self.rank >= RANK["WRITTEN"]

    @property
    def weight(self):
        return GATE_W if self.kind == "gate" else SIZE_W.get(self.size or "S", 1)

    @property
    def needs_evidence(self):
        return self.kind == "gate" or self.size in ("M", "L")

    @property
    def has_evidence(self):
        return self.evidence is not None and self.evidence.strip().lower() not in PLACEHOLDERS \
            and not self.evidence.strip().startswith("<")

    @property
    def in_pipeline(self):
        return self.kind != "gate" and not self.doc


def parse(text, with_meta=False):
    """Returns (items, titles); with_meta=True adds a third element {after, sections} (used by the dashboard and checks)."""
    items, titles, after, section_lines = [], {}, {}, {}
    section, last = None, None
    for n, line in enumerate(text.splitlines(), 1):
        pm = PHASE.match(line)
        if pm:
            p = int(pm.group(1))
            section = f"phase{p}"
            titles[p] = pm.group(2).strip()
            if pm.group(3):
                after[p] = int(pm.group(3))
            last = None
            continue
        h = H2.match(line)
        if h:
            section = h.group(1).strip().lower()
            section_lines.setdefault(section, [])
            last = None
            continue
        if section is not None:
            section_lines.setdefault(section, []).append(line)
        m = ITEM.match(line)
        if m:
            last = Item(m.group("id").strip(), m.group("x").lower() == "x", m.group("rest"), section, n)
            items.append(last)
            continue
        e = EVID.match(line)
        if e and last is not None:
            last.evidence = e.group(1)
    meta = {"after": after, "sections": section_lines}
    return (items, titles, meta) if with_meta else (items, titles)


def bar(pct, width=10):
    filled = round(width * pct / 100)
    return "█" * filled + "░" * (width - filled)


def stats(group):
    total = sum(i.weight for i in group)
    done = sum(i.weight for i in group if i.done)
    return done, total, (100 * done / total if total else 0)


def eff_rank(i):
    """Ladder rank used for statistics: a tick implies at least the required level (default UNIT)."""
    if i.done and not i.doc and i.kind != "gate":
        need = i.needs if i.needs in RANK else DEFAULT_NEEDS
        return max(i.rank, RANK[need])
    return i.rank


def pipeline(group):
    """Share of weighted points at or above each ladder state, over code items (not gates, not DOC)."""
    code = [i for i in group if i.in_pipeline]
    total = sum(i.weight for i in code)
    out = {}
    for s in STATES:
        pts = sum(i.weight for i in code if eff_rank(i) >= RANK[s])
        out[s] = (100 * pts / total) if total else 0.0
    return out, total


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
        elif tasks_done or gates_done or any(i.wip for i in grp) or any(i.rank for i in tasks) or any(i.failing for i in grp):
            status = "IN_PROGRESS"
        else:
            status = "NOT_STARTED"
        rows.append((p, titles.get(p, ""), status, tasks_done, len(tasks), gates_done, len(gates), pct, pipeline(grp)[0]))
        overall_items += grp
    cross = [i for i in items if i.kind == "cross"]
    caps = [i for i in items if i.kind == "cap"]
    overall_items += cross + caps
    od, ot, opct = stats(overall_items)
    return rows, cross, caps, (od, ot, opct), overall_items


def dashboard(items, titles, meta=None):
    rows, cross, caps, (od, ot, opct), allitems = compute(items, titles)
    after = (meta or {}).get("after", {})
    tasks = [i for i in items if i.kind in ("task", "cross")]
    gates = [i for i in items if i.kind == "gate"]
    wip = sum(1 for i in items if i.wip and not i.done)
    blocked = sum(1 for i in items if i.blocked and not i.done)
    failing = sum(1 for i in items if i.failing and not i.done)
    pl, ptotal = pipeline(allitems)
    cur = next((r for r in rows if r[2] in ("IN_PROGRESS", "GATE_PENDING", "BLOCKED")), None) \
        or next((r for r in rows if r[2] == "NOT_STARTED"), None)
    out = [
        f"**Done: {opct:.0f}%** (weighted {od}/{ot} points; ticked = met its required evidence level)  {bar(opct, 20)}",
        "**Evidence ladder** (share of code-item points at or above each state; docs and gates excluded): "
        + " · ".join(f"{s.lower()} {pl[s]:.0f}%" for s in STATES),
        f"Tasks {sum(i.done for i in tasks)}/{len(tasks)} · Gate items {sum(i.done for i in gates)}/{len(gates)} · "
        f"Capabilities {sum(i.done for i in caps)}/{len(caps)} · WIP {wip} · Blocked {blocked} · Failing {failing}",
        f"**Current phase:** {('Phase %d — %s [%s]' % (cur[0], cur[1], cur[2])) if cur else 'all done'}",
        "",
        "| Phase | Status | Tasks done | Done | Written | Compiled | Unit | Integ | Device | Gate |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    for p, title, status, td, tt, gd, gt, pct, pp in rows:
        note = f" (parallel after phase {after[p]})" if p in after else ""
        out.append(f"| {p} {title}{note} | {status} | {td}/{tt} | {bar(pct)} {pct:.0f}% | {pp['WRITTEN']:.0f}% | "
                   f"{pp['COMPILED']:.0f}% | {pp['UNIT']:.0f}% | {pp['INTEG']:.0f}% | {pp['DEVICE']:.0f}% | {gd}/{gt} |")
    for label, grp in (("X Cross-cutting", cross), ("CAP Capabilities", caps)):
        d, t, p = stats(grp) if grp else (0, 0, 0)
        pp = pipeline(grp)[0]
        out.append(f"| {label} | – | {sum(i.done for i in grp)}/{len(grp)} | {bar(p)} {p:.0f}% | {pp['WRITTEN']:.0f}% | "
                   f"{pp['COMPILED']:.0f}% | {pp['UNIT']:.0f}% | {pp['INTEG']:.0f}% | {pp['DEVICE']:.0f}% | – |")
    return "\n".join(out)


def stats_json(items, titles, meta):
    rows, cross, caps, (od, ot, opct), allitems = compute(items, titles)
    pl, ptotal = pipeline(allitems)
    return {
        "points": {"done": od, "total": ot, "pct": round(opct, 1)},
        "tasks": {"done": sum(i.done for i in items if i.kind in ("task", "cross")),
                  "total": sum(1 for i in items if i.kind in ("task", "cross"))},
        "gates": {"done": sum(i.done for i in items if i.kind == "gate"), "total": sum(1 for i in items if i.kind == "gate")},
        "capabilities": {"done": sum(i.done for i in caps), "total": len(caps)},
        "blocked": [i.id for i in items if i.blocked and not i.done],
        "failing": [i.id for i in items if i.failing and not i.done],
        "ladder_pct": {s.lower(): round(pl[s], 1) for s in STATES},
        "ladder_counts": {s.lower(): sum(1 for i in items if i.in_pipeline and eff_rank(i) >= RANK[s]) for s in STATES},
        "phases": {str(r[0]): {"title": r[1], "status": r[2], "tasks": [r[3], r[4]], "gates": [r[5], r[6]]} for r in rows},
    }


def validate(items, text, meta=None):
    if meta is None:
        meta = parse(text, with_meta=True)[2]
    errors, warns = [], []
    seen, by_id = {}, {}
    for i in items:
        if i.id in seen:
            errors.append(f"duplicate id {i.id} (lines {seen[i.id]} and {i.line})")
        seen[i.id] = i.line
        by_id[i.id] = i
    for i in items:
        if i.done and i.needs_evidence and not i.has_evidence:
            errors.append(f"{i.id}: ticked but evidence is missing (line {i.line})")
        if i.done and (i.wip or i.blocked):
            warns.append(f"{i.id}: ticked but still tagged (WIP)/(BLOCKED)")
        if i.failing and i.done:
            errors.append(f"{i.id}: ticked while tagged (FAILING)")
        if i.failing and not i.has_evidence:
            errors.append(f"{i.id}: (FAILING) needs an evidence line with the command, exit status and error (line {i.line})")
        if len(set(i.state_tags)) > 1:
            warns.append(f"{i.id}: several state tags {i.state_tags}; the highest ({i.state}) is used")
        if i.kind == "gate" and (i.state or i.doc):
            warns.append(f"{i.id}: gate items take no ladder/DOC tag")
        if i.rank >= RANK["COMPILED"] and not i.has_evidence:
            errors.append(f"{i.id}: state {i.state} claimed without an evidence line (line {i.line})")
        if i.needs and i.needs not in RANK:
            errors.append(f"{i.id}: unknown (needs: {i.needs})")
        if i.done and i.kind != "gate" and not i.doc:
            need = i.needs if i.needs in RANK else DEFAULT_NEEDS
            if i.state is None:
                warns.append(f"{i.id}: ticked without a ladder tag; counted as {need} (add (UNIT)/(INTEG)/(DEVICE) to say how far the evidence goes)")
            elif i.rank < RANK[need]:
                errors.append(f"{i.id}: ticked at state {i.state} but requires {need}; tag the proven state or mark (DOC) (line {i.line})")
        for d in i.deps:
            if not ID_TOKEN.match(d):
                errors.append(f"{i.id}: malformed dependency '{d}'")
            elif d not in by_id:
                errors.append(f"{i.id}: dependency {d} does not exist")
            elif i.done and not by_id[d].done:
                errors.append(f"{i.id}: ticked before its dependency {d}")
    # dependency cycles
    state, stack = {}, []
    def dfs(n):
        state[n] = 1
        stack.append(n)
        for d in by_id[n].deps:
            if d in by_id:
                if state.get(d) == 1:
                    errors.append("dependency cycle: " + " -> ".join(stack[stack.index(d):] + [d]))
                elif state.get(d) is None:
                    dfs(d)
        stack.pop()
        state[n] = 2
    for n in by_id:
        if state.get(n) is None:
            dfs(n)
    # ADR-084: phase order constrains starting new implementation scope, never verification ticks: no gate-order check here.
    for k in sorted({i.phase for i in items if i.phase is not None}):
        if [g for g in items if g.phase == k and g.kind == "gate" and g.done]:
            open_tasks = [i.id for i in items if i.phase == k and i.kind == "task" and not i.done]
            if open_tasks:
                warns.append(f"phase {k}: gate items ticked while tasks are still open: {', '.join(open_tasks[:5])}…")
    # blockers must be listed in the Blockers section (and only those)
    blocked = [i.id for i in items if i.blocked and not i.done]
    btxt = "\n".join(meta["sections"].get("blockers", []))
    if blocked and re.search(r"_\(none\)_", btxt):
        errors.append("Blockers section says (none) while items are tagged (BLOCKED): " + ", ".join(blocked))
    for b in blocked:
        if b not in btxt:
            errors.append(f"{b} is tagged (BLOCKED) but is not listed under ## Blockers")
    listed = set(re.findall(r"\*\*((?:P\d+-\d+|G\d+-[a-z]|X-\d+))\*\*", btxt))
    for b in sorted(listed - set(blocked)):
        warns.append(f"{b} is listed under Blockers but is not tagged (BLOCKED) or is already ticked")
    return errors, warns


def render_html(items, titles, dash_md):
    rows, cross, caps, (od, ot, opct), allitems = compute(items, titles)
    pl, _ = pipeline(allitems)
    def esc(s): return html.escape(s)
    parts = ["""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AutoHost progress</title><style>
:root{--bg:#fafcfc;--fg:#101414;--mut:#5f6b6d;--card:#fff;--line:#d5dede;--ok:#2e7d32;--run:#1565c0;--warn:#b26a00;--err:#c62828}
@media (prefers-color-scheme:dark){:root{--bg:#101414;--fg:#e8eeee;--mut:#9aa6a8;--card:#171d1e;--line:#2b3335;--ok:#81c784;--run:#64b5f6;--warn:#ffb74d;--err:#ef9a9a}}
body{font:15px/1.5 system-ui,sans-serif;background:var(--bg);color:var(--fg);margin:0;padding:16px;max-width:1040px;margin-inline:auto}
h1{font-size:22px;margin:.2em 0}.sub{color:var(--mut)}table{border-collapse:collapse;width:100%;margin:12px 0}
th,td{border-bottom:1px solid var(--line);padding:6px 8px;text-align:left;font-size:14px}
.bar{background:var(--line);border-radius:6px;height:10px;width:140px;display:inline-block;vertical-align:middle;overflow:hidden}
.bar>i{display:block;height:100%;background:var(--run)}.DONE .bar>i{background:var(--ok)}
.st{font-size:12px;border:1px solid var(--line);border-radius:10px;padding:1px 8px}
.DONE{color:var(--ok)}.IN_PROGRESS,.GATE_PENDING{color:var(--run)}.BLOCKED{color:var(--err)}
details{background:var(--card);border:1px solid var(--line);border-radius:10px;margin:8px 0;padding:6px 12px}
summary{cursor:pointer;font-weight:600}li{list-style:none;margin:4px 0}.ev{color:var(--mut);font-size:13px;margin-left:24px}
.t{display:inline-block;width:1.6em}.sz{color:var(--mut);font-size:12px}
</style></head><body>"""]
    parts.append(f"<h1>AutoHost — build progress</h1><div class='sub'>Done {opct:.0f}% ({od}/{ot} points). Ladder: "
                 + " · ".join(f"{s.lower()} {pl[s]:.0f}%" for s in STATES) + "</div>")
    parts.append(f"<div class='bar' style='width:100%;height:14px'><i style='width:{opct:.0f}%'></i></div>")
    parts.append("<table><tr><th>Phase</th><th>Status</th><th>Tasks</th><th>Done</th><th>Unit+</th><th>Device</th><th>Gate</th></tr>")
    for p, title, status, td, tt, gd, gt, pct, pp in rows:
        parts.append(f"<tr class='{status}'><td>{p} — {esc(title)}</td><td><span class='st {status}'>{status}</span></td>"
                     f"<td>{td}/{tt}</td><td><span class='bar'><i style='width:{pct:.0f}%'></i></span> {pct:.0f}%</td>"
                     f"<td>{pp['UNIT']:.0f}%</td><td>{pp['DEVICE']:.0f}%</td><td>{gd}/{gt}</td></tr>")
    parts.append("</table>")
    def block(title, group):
        d, t, pc = stats(group)
        s = [f"<details><summary>{esc(title)} — {pc:.0f}%</summary><ul style='padding-left:0'>"]
        for i in group:
            mark = "✅" if i.done else ("⛔" if i.blocked else (STATE_ICON.get(i.state) or ("🟡" if i.wip else "⬜")))
            ev = f"<div class='ev'>evidence: {esc(i.evidence.strip())}</div>" if i.evidence and i.evidence.strip() else ""
            desc = SIZE.sub("", i.rest).strip()
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
    ap.add_argument("--stats", action="store_true")
    a = ap.parse_args(argv)
    path = pathlib.Path(a.file)
    text = path.read_text(encoding="utf-8")
    items, titles, meta = parse(text, with_meta=True)
    if a.stats:
        print(json.dumps(stats_json(items, titles, meta), indent=2))
        return 0
    dash = dashboard(items, titles, meta)
    if START not in text or END not in text:
        print("ERROR: dashboard markers missing", file=sys.stderr)
        return 1
    block = f"{START}\n{dash}\n{END}"
    current = text[text.index(START): text.index(END) + len(END)]
    errors, warns = validate(items, text, meta)
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
