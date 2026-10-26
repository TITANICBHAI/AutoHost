"""App packs (P11-04, ADR-080) — the task-side helpers over the run's injected `config.packs`.

The host resolves a task's `uses_packs` at run start and injects the referenced packs into the
run config under the reserved key `packs` (one mechanism for every runtime — Python ctx.config,
the JS prelude, flow `{{config.*}}`). This module is the readable way in:

    import autohost.packs as packs
    login = packs.selector(ctx, "com.example.presser-pack", "login_button")
    autohost.device.ui.click(login)

A pack's named selector is a selector DOCUMENT (the `sel.to_json()` shape — one selector
language for flows, packs and the SDK); `packs.selector` returns it rebuilt as a `sel.Selector`
ready for `device.ui.*`. Missing packs or names raise `PackMissing` / `SelectorMissing` —
honest failures, never a silent fallback to a hardcoded selector.
"""

from __future__ import annotations

from typing import Any

from . import sel
from .errors import InvalidArgs


class PackError(Exception):
    """Base class: a pack the run cannot see (missing at run start, or the task never declared it)."""


class PackMissing(PackError):
    pass


class SelectorMissing(PackError):
    pass


def _packs(ctx: Any) -> dict:
    config = dict(ctx.config)
    packs = config.get("packs")
    if not isinstance(packs, dict):
        # Honest distinction: declared-but-missing (host warned at run start) vs never injected.
        raise PackMissing("no packs in this run's config — the task declared no usable 'uses_packs' (or every pack was missing)")
    return packs


def pack(ctx: Any, name: str) -> dict:
    """The full pack payload: {target, version, selectors, screens, labels, tips}."""
    packs = _packs(ctx)
    entry = packs.get(name)
    if entry is None:
        known = ", ".join(sorted(packs)) or "none"
        raise PackMissing(f"pack '{name}' is not in this run (installed packs referenced by the task: {known})")
    return dict(entry)


def selector(ctx: Any, name: str, selector_name: str) -> "sel.Selector":
    """A named selector from the pack, rebuilt as a `sel.Selector` ready for `device.ui.*`."""
    entry = pack(ctx, name)
    selectors = entry.get("selectors") or {}
    found = selectors.get(selector_name)
    if not isinstance(found, dict):
        raise SelectorMissing(f"pack '{name}' has no selector '{selector_name}' (has: {', '.join(sorted(selectors)) or 'none'})")
    return sel.from_json(found)


def screen(ctx: Any, name: str, screen_name: str) -> dict:
    """A named Screen signature from the pack (for ctx screen classification helpers)."""
    entry = pack(ctx, name)
    screens = entry.get("screens") or {}
    found = screens.get(screen_name)
    if not isinstance(found, dict):
        raise SelectorMissing(f"pack '{name}' has no screen '{screen_name}' (has: {', '.join(sorted(screens)) or 'none'})")
    return dict(found)


def label(ctx: Any, name: str, key: str, locale: str | None = None) -> str:
    """A label with locale fallback: exact tag → language-only tag → "en" → first available.

    Mirrors `AppPacks.label` (ADR-080) — keep the two in sync.
    """
    entry = pack(ctx, name)
    labels = entry.get("labels") or {}

    def entry_for(tag: str) -> str | None:
        values = labels.get(tag)
        if isinstance(values, dict):
            found = values.get(key)
            if isinstance(found, str):
                return found
        return None

    if locale:
        found = entry_for(locale)
        if found is not None:
            return found
        language = locale.split("-")[0]
        if language != locale:
            found = entry_for(language)
            if found is not None:
                return found
    found = entry_for("en")
    if found is not None:
        return found
    for values in labels.values():
        if isinstance(values, dict):
            found = values.get(key)
            if isinstance(found, str):
                return found
    raise SelectorMissing(f"pack '{name}' has no label '{key}' in any locale")


def tips(ctx: Any, name: str) -> list[str]:
    return list(pack(ctx, name).get("tips") or [])
