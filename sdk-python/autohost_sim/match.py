"""Selector evaluation over simulated nodes. Mirrors the JSON contract in schemas/broker.schema.json.

Simplifications versus the real SelectorEngine (documented, not hidden):
- relations use bounding boxes only (child_of / contains_* are containment checks);
- 'deepest match' is not modelled: results are in document order (P3 parity: bounds modes,
  drawing_order, depth, index relation are all evaluated exactly like the Kotlin engine);
- BFS is not modelled (sim nodes carry no tree depth): results are in document order;
- text matching normalises Unicode, case and typographic quotes, like the device does.
"""
from __future__ import annotations

import re
import unicodedata
from typing import Any


def norm(value: Any) -> str:
    text = unicodedata.normalize("NFKC", str(value))
    for src, dst in (("\u2019", "'"), ("\u2018", "'"), ("\u201c", '"'), ("\u201d", '"')):
        text = text.replace(src, dst)
    return " ".join(text.casefold().split())


def _match_value(value: Any, spec: dict) -> bool:
    labels = spec.get("labels", [])
    mode = spec.get("mode", "contains")
    cs = bool(spec.get("case_sensitive", False))
    raw = "" if value is None else str(value)
    hay = raw if cs else norm(raw)
    for label in labels:
        needle = str(label) if cs else norm(label)
        if mode == "eq":
            ok = hay == needle
        elif mode == "contains":
            ok = needle in hay
        elif mode == "starts_with":
            ok = hay.startswith(needle)
        elif mode == "ends_with":
            ok = hay.endswith(needle)
        elif mode == "regex":
            ok = re.search(str(label), raw, 0 if cs else re.IGNORECASE) is not None
        else:
            raise ValueError(f"unknown match mode: {mode}")
        if ok:
            return True
    return False


def _flag(node: dict, name: str) -> bool:
    return bool(node.get(name, name == "enabled"))


def positions_index(nodes: list[dict], node: dict) -> int | None:
    try:
        return nodes.index(node)
    except ValueError:
        return None


def _inside(child: dict, parent: dict) -> bool:
    cb, pb = child.get("bounds"), parent.get("bounds")
    if not cb or not pb:
        return False
    return cb[0] >= pb[0] and cb[1] >= pb[1] and cb[2] <= pb[2] and cb[3] <= pb[3]


def _bounds_holds(node: dict, spec: dict) -> bool:
    nb = node.get("bounds")
    if not nb:
        return False
    l = spec.get("left", -(10**9))
    t = spec.get("top", -(10**9))
    r = spec.get("right", 10**9)
    b = spec.get("bottom", 10**9)
    if spec.get("mode", "inside") == "overlaps":
        return nb[0] < r and nb[2] > l and nb[1] < b and nb[3] > t
    return nb[0] >= l and nb[1] >= t and nb[2] <= r and nb[3] <= b


def _relation_holds(kind: str, node: dict, other: dict, n: int | None = None,
                    node_index: int | None = None, anchor_indexes: list[int] | None = None) -> bool:
    nb, ob = node.get("bounds"), other.get("bounds")
    if kind == "index":
        # node must be the n-th match of the anchor selector in document order (mirrors SelectorEngine)
        if anchor_indexes is None or node_index is None:
            return False
        ordered = sorted(i for i in anchor_indexes if i is not None)
        if not ordered or n is None and len(ordered) == 0:
            return False
        want = ordered[n] if n is not None else ordered[0]
        return want == node_index
    if not nb or not ob:
        return False
    if kind == "below":
        return nb[1] >= ob[3]
    if kind == "above":
        return nb[3] <= ob[1]
    if kind == "left_of":
        return nb[2] <= ob[0]
    if kind == "right_of":
        return nb[0] >= ob[2]
    if kind in ("child_of", "contains_descendants"):
        return _inside(node, other) if kind == "child_of" else _inside(other, node)
    if kind == "contains_child":
        return _inside(other, node)
    raise ValueError(f"unknown relation: {kind}")


def _term_ok(term: dict, node: dict, nodes: list[dict]) -> bool:
    if len(term) != 1:
        raise ValueError("each selector term must have exactly one key")
    (key, spec), = term.items()
    if key == "text":
        return _match_value(node.get("text", ""), spec)
    if key == "desc":
        return _match_value(node.get("desc", ""), spec)
    if key == "id":
        return _match_value(node.get("id", ""), spec)
    if key == "class":
        return _match_value(node.get("cls", ""), spec)
    if key == "pkg":
        return _match_value(node.get("pkg", ""), spec)
    if key == "state":
        return all(_flag(node, k) == bool(v) for k, v in spec.items())
    if key == "bounds":
        return _bounds_holds(node, spec)
    if key == "depth":
        return node.get("depth") == spec
    if key == "index_in_parent":
        siblings = [n for n in nodes if n.get("parent") is not None and n.get("parent") == node.get("parent")]
        return siblings.index(node) == spec if node in siblings else False
    if key == "drawing_order":
        return node.get("drawing_order") == spec
    if key == "relation":
        if spec["kind"] == "index":
            # The node itself is a candidate for the index relation (unlike spatial relations).
            matches = select(spec["selector"], nodes)
            node_index = node.get("index")
            if node_index is None:
                node_index = positions_index(nodes, node)
            ordered = sorted(m.get("index", positions_index(nodes, m)) for m in matches)
            pick = spec.get("n") or 0
            if not ordered or pick < 0 or pick >= len(ordered):
                return False
            return ordered[pick] == node_index
        others = select(spec["selector"], nodes)
        positions = {id(n): i for i, n in enumerate(nodes)}
        anchors = [o.get("index", positions.get(id(o))) for o in others if o.get("ref") != node.get("ref")]
        node_index = node.get("index")
        if node_index is None:
            node_index = positions.get(id(node))
        return any(
            o.get("ref") != node.get("ref")
            and _relation_holds(spec["kind"], node, o, spec.get("n"), node_index, anchors)
            for o in others
        )
    if key == "sel":
        refs = {n.get("ref") for n in select(spec, nodes)}
        return node.get("ref") in refs
    raise ValueError(f"unknown selector term: {key}")


def select(selector: dict | None, nodes: list[dict]) -> list[dict]:
    if not selector:
        return list(nodes)
    candidates = nodes
    if selector.get("within"):
        parents = select(selector["within"], nodes)
        if not parents:
            return []
        candidates = [n for n in nodes if any(_inside(n, p) for p in parents)]
    terms = selector.get("terms", [])
    op = selector.get("op", "and")
    if terms:
        def ok(node: dict) -> bool:
            results = [_term_ok(t, node, nodes) for t in terms]
            return all(results) if op == "and" else any(results)

        result = [n for n in candidates if ok(n)]
    else:
        result = list(candidates)
    if "nth" in selector:
        i = int(selector["nth"])
        result = result[i:i + 1]
    return result
