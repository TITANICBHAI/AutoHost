from typing import Any, Literal, TypedDict
from ..sel import Selector

class Node(TypedDict, total=False):
    ref: str; pkg: str; cls: str; id: str; text: str; desc: str
    bounds: list[int]; flags: list[str]; index: int

class Snapshot(TypedDict, total=False):
    ts: int; package: str | None; activity: str | None; keyboard: Literal["visible", "hidden"]
    nodes: list[Node]; compact: str; node_count: int; empty: bool; degraded: bool; degraded_reason: str | None

class ClickResult(TypedDict):
    method: Literal["NODE_ACTION", "ANCESTOR_ACTION", "COORDINATE"]
    verified: bool

class TextResult(TypedDict):
    outcome: Literal["VERIFIED", "REJECTED", "ACCEPTED_UNVERIFIED", "COMMIT_OUTCOME_UNKNOWN", "INPUT_SESSION_CHANGED"]
    strategy: str

class LaunchResult(TypedDict):
    result: Literal["LAUNCHED_VERIFIED", "LAUNCHED_UNVERIFIED", "FAILED"]
    strategy: str

class StopResult(TypedDict):
    result: Literal["VERIFIED", "BEST_EFFORT", "FAILED"]
    method: str

NodeOrSel = Node | Selector

class DeviceUi:
    def snapshot(self, compact: bool = False, packages: list[str] | None = None) -> Snapshot: ...
    def find(self, selector: Selector, limit: int = 20, within: Node | None = None) -> list[Node]: ...
    def wait_for(self, selector: Selector, timeout_ms: int, visible_only: bool = True) -> Node | None: ...
    def wait_for_settle(self, timeout_ms: int = 3000, stable_ms: int = 300) -> bool: ...
    def is_window_updating(self) -> bool: ...
    def dump(self, compact: bool = True) -> str: ...
    def click(self, target: NodeOrSel, strategy: Literal["auto", "node", "ancestor", "coordinate"] = "auto",
              expect: Selector | None = None, retry_if_no_change: bool = False, timeout_ms: int | None = None) -> ClickResult: ...
    def long_click(self, target: NodeOrSel) -> ClickResult: ...
    def set_text(self, target: NodeOrSel, text: str, secret: bool = False, verify: bool = True) -> TextResult: ...
    def scroll_until(self, selector: Selector, direction: Literal["down", "up", "left", "right"] = "down", max_swipes: int = 10) -> Node | None: ...
    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> None: ...   # flagged COORDINATE
    def tap(self, x: int, y: int) -> None: ...                                                   # flagged COORDINATE
    def press(self, key: Literal["back", "home", "recents", "enter", "notifications", "quick_settings", "lock", "power_dialog"]) -> None: ...
    def hide_keyboard(self) -> None: ...
    def keyboard_visible(self) -> bool: ...
    def screenshot(self, region: list[int] | None = None, scale: float = 1.0) -> str: ...        # path (device.screenshot capability)

class DeviceApp:
    def query(self, package: str) -> dict[str, Any]: ...        # {installed, version_name, version_code}
    def list(self, filter: str | None = None) -> list[dict[str, str]]: ...
    def foreground(self) -> dict[str, str | None]: ...          # {package, activity}
    def wait_foreground(self, package: str, timeout_ms: int) -> None: ...   # raises Timeout
    def launch(self, package: str, activity: str | None = None, uri: str | None = None, user: int | None = None) -> LaunchResult: ...
    def stop(self, package: str, strategy: str = "auto") -> StopResult: ...

class DeviceScreen:
    def is_on(self) -> bool: ...
    def is_unlocked(self) -> bool: ...

class DeviceIme:
    def commit_text(self, text: str, secret: bool = False, clear_first: bool = False) -> TextResult: ...
    def clipboard_get(self) -> str | None: ...
    def clipboard_set(self, text: str) -> None: ...

ui: DeviceUi
app: DeviceApp
screen: DeviceScreen
ime: DeviceIme
def info() -> dict[str, Any]: ...                 # {api, manufacturer, model, locale, screen:{w,h,dpi}, tiers:{...}}
