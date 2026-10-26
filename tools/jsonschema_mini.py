"""Minimal JSON Schema validator (stdlib only) for the keywords used in schemas/.

It refuses keywords it does not implement, so a schema can never pass by being silently ignored.
Supported: type, enum, const, required, properties, additionalProperties, patternProperties, propertyNames,
items, minItems, maxItems, minProperties, maxProperties, minimum, maximum, minLength, maxLength, pattern,
allOf, oneOf, if/then/else, $ref (local "#/..." only), $defs. Annotations (title, description, $schema, $id,
default, examples) and x-* keys are ignored.
"""
from __future__ import annotations

import re
from typing import Any

ANNOTATIONS = {"$schema", "$id", "title", "description", "$defs", "default", "examples", "$comment"}
SUPPORTED = {"type", "enum", "const", "required", "properties", "additionalProperties", "patternProperties",
             "propertyNames", "items", "minItems", "maxItems", "minProperties", "maxProperties", "minimum",
             "maximum", "minLength", "maxLength", "pattern", "allOf", "oneOf", "if", "then", "else", "$ref"}


class SchemaError(Exception):
    """The schema itself is invalid or uses an unsupported keyword."""


def validate(instance: Any, schema: dict, root: dict | None = None) -> list[str]:
    errors: list[str] = []
    _check(instance, schema, schema if root is None else root, "$", errors)
    return errors


def _resolve(ref: str, root: dict) -> dict:
    if not ref.startswith("#/"):
        raise SchemaError(f"unsupported $ref: {ref}")
    node: Any = root
    for part in ref[2:].split("/"):
        node = node[part]
    return node


def _is_type(value: Any, name: str) -> bool:
    if name == "object":
        return isinstance(value, dict)
    if name == "array":
        return isinstance(value, list)
    if name == "string":
        return isinstance(value, str)
    if name == "boolean":
        return isinstance(value, bool)
    if name == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if name == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if name == "null":
        return value is None
    raise SchemaError(f"unknown type: {name}")


def _check(v: Any, s: Any, root: dict, path: str, errs: list[str]) -> None:
    if s is True or s == {}:
        return
    if s is False:
        errs.append(f"{path}: not allowed")
        return
    if not isinstance(s, dict):
        raise SchemaError(f"schema at {path} is not an object")
    for key in s:
        if key not in SUPPORTED and key not in ANNOTATIONS and not key.startswith("x-"):
            raise SchemaError(f"unsupported keyword '{key}' at {path}")

    if "$ref" in s:
        _check(v, _resolve(s["$ref"], root), root, path, errs)
    if "type" in s:
        names = s["type"] if isinstance(s["type"], list) else [s["type"]]
        if not any(_is_type(v, n) for n in names):
            errs.append(f"{path}: expected {'/'.join(names)}, got {type(v).__name__}")
            return
    if "const" in s and v != s["const"]:
        errs.append(f"{path}: must equal {s['const']!r}")
    if "enum" in s and v not in s["enum"]:
        errs.append(f"{path}: {v!r} is not one of {s['enum']!r}")

    if isinstance(v, str):
        if "minLength" in s and len(v) < s["minLength"]:
            errs.append(f"{path}: shorter than {s['minLength']}")
        if "maxLength" in s and len(v) > s["maxLength"]:
            errs.append(f"{path}: longer than {s['maxLength']}")
        if "pattern" in s and not re.search(s["pattern"], v):
            errs.append(f"{path}: {v!r} does not match {s['pattern']!r}")

    if isinstance(v, (int, float)) and not isinstance(v, bool):
        if "minimum" in s and v < s["minimum"]:
            errs.append(f"{path}: below minimum {s['minimum']}")
        if "maximum" in s and v > s["maximum"]:
            errs.append(f"{path}: above maximum {s['maximum']}")

    if isinstance(v, list):
        if "minItems" in s and len(v) < s["minItems"]:
            errs.append(f"{path}: fewer than {s['minItems']} items")
        if "maxItems" in s and len(v) > s["maxItems"]:
            errs.append(f"{path}: more than {s['maxItems']} items")
        if "items" in s:
            for i, item in enumerate(v):
                _check(item, s["items"], root, f"{path}[{i}]", errs)

    if isinstance(v, dict):
        if "required" in s:
            for key in s["required"]:
                if key not in v:
                    errs.append(f"{path}: missing required property '{key}'")
        if "minProperties" in s and len(v) < s["minProperties"]:
            errs.append(f"{path}: fewer than {s['minProperties']} properties")
        if "maxProperties" in s and len(v) > s["maxProperties"]:
            errs.append(f"{path}: more than {s['maxProperties']} properties")
        props = s.get("properties", {})
        patterns = s.get("patternProperties", {})
        for key, sub in props.items():
            if key in v:
                _check(v[key], sub, root, f"{path}.{key}", errs)
        additional = s.get("additionalProperties", True)
        for key, value in v.items():
            if key in props:
                continue
            matched = [sub for pat, sub in patterns.items() if re.search(pat, key)]
            if matched:
                for sub in matched:
                    _check(value, sub, root, f"{path}.{key}", errs)
                continue
            if additional is False:
                errs.append(f"{path}: unknown property '{key}'")
            elif isinstance(additional, dict):
                _check(value, additional, root, f"{path}.{key}", errs)
        if "propertyNames" in s:
            for key in v:
                _check(key, s["propertyNames"], root, f"{path}<key {key!r}>", errs)

    if "allOf" in s:
        for sub in s["allOf"]:
            _check(v, sub, root, path, errs)
    if "oneOf" in s:
        passing = [sub for sub in s["oneOf"] if not _errors_of(v, sub, root, path)]
        if len(passing) != 1:
            errs.append(f"{path}: must match exactly one of oneOf (matched {len(passing)})")
    if "if" in s:
        branch = "then" if not _errors_of(v, s["if"], root, path) else "else"
        if branch in s:
            _check(v, s[branch], root, path, errs)


def _errors_of(v: Any, s: Any, root: dict, path: str) -> list[str]:
    out: list[str] = []
    _check(v, s, root, path, out)
    return out
