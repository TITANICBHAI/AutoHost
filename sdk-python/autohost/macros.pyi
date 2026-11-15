from typing import Any

class MacroResult:
    run_id: str
    status: str
    code: str | None
    data: dict[str, Any]
    text: str | None
    @property
    def ok(self) -> bool: ...

def run(macro: str, inputs: dict[str, Any] | None = None, *, profile: str | None = None,
        timeout_ms: int | None = None, check: bool = True) -> MacroResult: ...
