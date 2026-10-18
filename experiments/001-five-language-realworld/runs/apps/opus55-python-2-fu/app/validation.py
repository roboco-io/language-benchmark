"""Helpers for validating RealWorld request bodies of the form {"<wrapper>": {...}}."""

from typing import Any

from .errors import APIError, unprocessable

BLANK = "can't be blank"
_MISSING = object()


def unwrap(payload: Any, key: str) -> dict:
    if not isinstance(payload, dict) or not isinstance(payload.get(key), dict):
        raise unprocessable(key, "is missing")
    return payload[key]


def required_string(data: dict, field: str) -> str:
    """A field that must be present and non-blank."""
    value = data.get(field)
    if value is None or (isinstance(value, str) and not value.strip()):
        raise unprocessable(field, BLANK)
    if not isinstance(value, str):
        raise unprocessable(field, "must be a string")
    return value


def optional_required_string(data: dict, field: str) -> str | object:
    """A field that may be omitted, but if present must be a non-blank string."""
    if field not in data:
        return _MISSING
    return required_string(data, field)


def nullable_string(data: dict, field: str) -> str | None | object:
    """A field that may be omitted; null or "" normalizes to None."""
    if field not in data:
        return _MISSING
    value = data[field]
    if value is None or value == "":
        return None
    if not isinstance(value, str):
        raise unprocessable(field, "must be a string")
    return value


def tag_list(data: dict, field: str = "tagList", allow_null: bool = False) -> list[str] | None | object:
    if field not in data:
        return _MISSING
    value = data[field]
    if value is None:
        if allow_null:
            return []
        raise unprocessable(field, "must be an array")
    if not isinstance(value, list) or not all(isinstance(t, str) for t in value):
        raise unprocessable(field, "must be an array of strings")
    seen: list[str] = []
    for tag in value:
        tag = tag.strip()
        if tag and tag not in seen:
            seen.append(tag)
    return seen


def present(value: Any) -> bool:
    return value is not _MISSING


__all__ = ["APIError", "BLANK", "unwrap", "required_string", "optional_required_string",
           "nullable_string", "tag_list", "present"]
