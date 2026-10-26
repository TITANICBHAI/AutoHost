"""contacts.*: query and insert contacts (P10-06). Row-capped; lazy READ/WRITE_CONTACTS."""
from __future__ import annotations

from typing import Any

from ._core import current
from .errors import InvalidArgs

MAX_ROWS = 500


def _rows(limit: int | None) -> int | None:
    if limit is None:
        return None
    if not 1 <= int(limit) <= MAX_ROWS:
        raise InvalidArgs(f"limit must be 1..{MAX_ROWS}")
    return int(limit)


def query(filter: str | None = None, limit: int | None = None) -> dict[str, Any]:
    """Query contacts by name filter (contains). Returns {contacts:[{name,phone?,email?}], count}."""
    args: dict[str, Any] = {}
    if filter is not None:
        args["filter"] = filter
    r = _rows(limit)
    if r is not None:
        args["limit"] = r
    return current().call("contacts", "query", args)


def insert(name: str, phone: str | None = None, email: str | None = None) -> dict[str, Any]:
    """Insert a contact (needs WRITE_CONTACTS). Returns {id}."""
    if not name or not name.strip():
        raise InvalidArgs("name is required")
    args: dict[str, Any] = {"name": name}
    if phone is not None:
        args["phone"] = phone
    if email is not None:
        args["email"] = email
    return current().call("contacts", "insert", args)
