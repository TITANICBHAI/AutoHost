"""Drift check: every top-level name declared in a .pyi stub must exist in its implementation module."""
import ast
import importlib
import unittest
from pathlib import Path

import support  # noqa: F401

PKG = support.SDK / "autohost"
PAIRS = {
    "errors.pyi": "autohost.errors", "sel.pyi": "autohost.sel", "flow.pyi": "autohost.flow",
    "ui.pyi": "autohost.ui", "notify.pyi": "autohost.notify", "http.pyi": "autohost.http",
    "files.pyi": "autohost.files", "android.pyi": "autohost.android", "device/__init__.pyi": "autohost.device",
}


def declared_names(path: Path):
    tree = ast.parse(path.read_text(encoding="utf-8"))
    names = set()
    for node in tree.body:
        if isinstance(node, (ast.ClassDef, ast.FunctionDef)):
            names.add(node.name)
        elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
            names.add(node.target.id)
    return names


class StubDrift(unittest.TestCase):
    def test_stub_names_exist_in_implementation(self):
        for stub, module_name in PAIRS.items():
            module = importlib.import_module(module_name)
            for name in declared_names(PKG / stub):
                self.assertTrue(hasattr(module, name), f"{stub} declares {name} but {module_name} lacks it")


if __name__ == "__main__":
    unittest.main()
