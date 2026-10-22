"""Optional toolkit for robust multi-step UI scripts."""
from typing import Any, Callable, Literal
from .sel import Selector
class Screen:
    name: str
    def __init__(self, name: str, *, all_text: list[str] = ..., any_text: list[str] = ..., none_text: list[str] = ...,
                 ids: list[str] = ..., desc: list[str] = ..., package: str | None = None, priority: int = 0) -> None: ...
UNKNOWN: str
def classify(screens: list[Screen]) -> str: ...                    # screen name or UNKNOWN (ambiguous => UNKNOWN)
def transition(action: Callable[[], Any], expect: Screen | Selector, timeout_ms: int = 10000,
               unknown: Literal["fail", "manual"] = "fail", evidence_on_fail: bool = True) -> None: ...
def retry(times: int = 2, backoff_ms: int = 500, on: tuple[type[BaseException], ...] = ...) -> Callable[[Callable[..., Any]], Callable[..., Any]]: ...
def stage(name: str, retries: int = 0, on_resume: Literal["verify", "redo", "manual"] = "verify") -> Callable[[Callable[..., Any]], Callable[..., Any]]: ...
def run_stages() -> None: ...                                       # run decorated stages in order with checkpoints
