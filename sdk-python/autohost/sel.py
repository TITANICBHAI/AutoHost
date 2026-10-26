"""Selectors. A Selector serialises to the broker JSON in schemas/broker.schema.json#/$defs/Selector."""
from __future__ import annotations

import copy
from typing import Any

from .errors import InvalidArgs


class Selector:
    __slots__ = ("_json",)

    def __init__(self, terms: list | None = None, op: str = "and") -> None:
        self._json: dict[str, Any] = {"op": op, "terms": list(terms or [])}

    @classmethod
    def _from_json(cls, data: dict) -> "Selector":
        sel = cls()
        sel._json = copy.deepcopy(data)
        return sel

    def to_json(self) -> dict:
        return copy.deepcopy(self._json)

    def __and__(self, other: "Selector") -> "Selector":
        return _combine("and", self, other)

    def __or__(self, other: "Selector") -> "Selector":
        return _combine("or", self, other)

    def within(self, parent: "Selector") -> "Selector":
        data = self.to_json()
        data["within"] = parent.to_json()
        return Selector._from_json(data)

    def nth(self, index: int) -> "Selector":
        data = self.to_json()
        data["nth"] = int(index)
        return Selector._from_json(data)

    def bfs(self) -> "Selector":
        data = self.to_json()
        data["algorithm"] = "bfs"
        return Selector._from_json(data)

    def __repr__(self) -> str:
        return f"Selector({self._json!r})"


def _combine(op: str, a: Selector, b: Selector) -> Selector:
    return Selector(terms=[{"sel": a.to_json()}, {"sel": b.to_json()}], op=op)


def _match(kind: str, labels: tuple, mode: str, case_sensitive: bool) -> Selector:
    if not labels:
        raise ValueError("at least one label is required")
    spec = {"labels": [str(x) for x in labels], "mode": mode, "case_sensitive": bool(case_sensitive)}
    return Selector(terms=[{kind: spec}])


def text(*labels: str, mode: str = "contains", case_sensitive: bool = False) -> Selector:
    return _match("text", labels, mode, case_sensitive)


def desc(*labels: str, mode: str = "contains", case_sensitive: bool = False) -> Selector:
    return _match("desc", labels, mode, case_sensitive)


def id(*ids: str, mode: str = "eq") -> Selector:  # noqa: A001 - name fixed by the stub
    return _match("id", ids, mode, True)


def cls(*names: str, mode: str = "eq") -> Selector:
    return _match("class", names, mode, True)


def pkg(*names: str) -> Selector:
    return _match("pkg", names, "eq", True)


def _state(name: str, value: bool) -> Selector:
    return Selector(terms=[{"state": {name: bool(value)}}])


def clickable(value: bool = True) -> Selector:
    return _state("clickable", value)


def enabled(value: bool = True) -> Selector:
    return _state("enabled", value)


def checked(value: bool = True) -> Selector:
    return _state("checked", value)


def selected(value: bool = True) -> Selector:
    return _state("selected", value)


def focused(value: bool = True) -> Selector:
    return _state("focused", value)


def editable(value: bool = True) -> Selector:
    return _state("editable", value)


def scrollable(value: bool = True) -> Selector:
    return _state("scrollable", value)


def visible(value: bool = True) -> Selector:
    return _state("visible", value)


def bounds_inside(left: int, top: int, right: int, bottom: int) -> Selector:
    return Selector(terms=[{"bounds": {"left": int(left), "top": int(top), "right": int(right), "bottom": int(bottom)}}])


def bounds_overlaps(left: int, top: int, right: int, bottom: int) -> Selector:
    """Any-intersection bounds filter (plan §7.6 numeric filters); inside is the default mode elsewhere."""
    return Selector(terms=[{"bounds": {"left": int(left), "top": int(top), "right": int(right), "bottom": int(bottom), "mode": "overlaps"}}])


def depth(level: int) -> Selector:
    return Selector(terms=[{"depth": int(level)}])


def index_in_parent(position: int) -> Selector:
    return Selector(terms=[{"index_in_parent": int(position)}])


def drawing_order(z: int) -> Selector:
    """Framework drawing order inside the parent — higher = drawn on top (plan §7.6)."""
    return Selector(terms=[{"drawing_order": int(z)}])


def _relation(kind: str, other: Selector) -> Selector:
    return Selector(terms=[{"relation": {"kind": kind, "selector": other.to_json()}}])


def index_at(other: Selector, n: int = 0) -> Selector:
    """Node is the n-th (0-based) match of `other` in document order (plan §7.6 relational filters)."""
    return Selector(terms=[{"relation": {"kind": "index", "selector": other.to_json(), "n": int(n)}}])


def below(other: Selector) -> Selector:
    return _relation("below", other)


def above(other: Selector) -> Selector:
    return _relation("above", other)


def left_of(other: Selector) -> Selector:
    return _relation("left_of", other)


def right_of(other: Selector) -> Selector:
    return _relation("right_of", other)


def child_of(other: Selector) -> Selector:
    return _relation("child_of", other)


def contains_child(other: Selector) -> Selector:
    return _relation("contains_child", other)


def contains_descendants(*others: Selector) -> Selector:
    return Selector(terms=[{"relation": {"kind": "contains_descendants", "selector": o.to_json()}} for o in others])


def from_json(doc: dict) -> Selector:
    """Builds a Selector from a stored selector document (the `to_json()` shape).

    App packs (P11-04) store named selectors as selector documents so one selector language
    serves flows, packs and the SDK; this is the readable way back into `sel` objects. A
    non-dict raises InvalidArgs — fail closed, never a half-built selector.
    """
    if not isinstance(doc, dict):
        raise InvalidArgs("selector document must be a JSON object (sel.to_json() shape)")
    return Selector._from_json(copy.deepcopy(doc))
