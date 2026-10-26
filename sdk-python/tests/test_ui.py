"""P7-04: imperative ui.* — dialog, form, overlay and console (plan §10.2).

The sim host answers prompts from `prompt_answers` (keyed by title): a str answers dialogs/asks,
a dict answers forms, missing or "TIMEOUT" means nobody answered.
"""
from __future__ import annotations

import unittest

from support import press_device, runtime_for  # noqa: F401

from autohost import ui
from autohost._core import current
from autohost.errors import InvalidArgs


class UiDialogFormTest(unittest.TestCase):
    def setUp(self) -> None:
        self.device = press_device()

    def test_dialog_returns_pressed_button(self) -> None:
        self.device.prompt_answers = {"Pick": "Cancel"}
        runtime_for(self.device)
        self.assertEqual(ui.dialog("Pick", "Choose one", buttons=["OK", "Cancel"]), "Cancel")
        self.assertEqual(self.device.prompts_asked[-1]["buttons"], ["OK", "Cancel"])

    def test_dialog_default_button_and_dismissal(self) -> None:
        runtime_for(self.device)
        self.assertEqual(ui.dialog("Unscripted", "Nobody answers"), "")  # sim user is not home
        self.device.prompt_answers = {"Styled": "OK"}
        self.assertEqual(ui.dialog("Styled", "Default button list"), "OK")
        self.assertEqual(self.device.prompts_asked[-1]["buttons"], ["OK"])

    def test_dialog_validates_buttons(self) -> None:
        runtime_for(self.device)
        with self.assertRaises(InvalidArgs):
            ui.dialog("T", "M", buttons=[])
        with self.assertRaises(InvalidArgs):
            ui.dialog("T", "M", buttons=["A", "B", "C", "D", "E"])
        with self.assertRaises(InvalidArgs):
            ui.dialog("T", "M", buttons=["OK", "  "])

    def test_form_returns_typed_values(self) -> None:
        self.device.prompt_answers = {"Settings": {"count": 3, "enabled": True, "mode": "fast"}}
        runtime_for(self.device)
        values = ui.form("Settings", [
            {"name": "count", "label": "Count", "type": "number", "default": 1},
            {"name": "enabled", "label": "Enabled", "type": "switch", "default": False},
            {"name": "mode", "label": "Mode", "type": "select", "choices": ["fast", "slow"]},
        ])
        self.assertEqual(values, {"count": 3, "enabled": True, "mode": "fast"})
        asked = self.device.prompts_asked[-1]
        self.assertEqual([f["name"] for f in asked["fields"]], ["count", "enabled", "mode"])

    def test_form_dismissal_returns_none(self) -> None:
        runtime_for(self.device)
        self.assertIsNone(ui.form("Unscripted", [{"name": "a", "label": "A"}]))

    def test_form_validates_fields(self) -> None:
        runtime_for(self.device)
        with self.assertRaises(InvalidArgs):
            ui.form("T", [])
        with self.assertRaises(InvalidArgs):
            ui.form("T", [{"label": "no name"}])
        with self.assertRaises(InvalidArgs):
            ui.form("T", [{"name": "x", "label": "X", "type": "color"}])
        with self.assertRaises(InvalidArgs):
            ui.form("T", [{"name": "x", "label": "X", "type": "select"}])  # select needs choices
        with self.assertRaises(InvalidArgs):
            ui.form("T", [{"name": "x", "label": "X"}, {"name": "x", "label": "Dup"}])

    def test_overlay_reports_shown(self) -> None:
        runtime_for(self.device)
        result = ui.overlay("Almost done", duration_ms=1500)
        self.assertTrue(result["shown"])
        self.assertEqual(self.device.notifications[-1]["kind"], "overlay")
        self.assertEqual(self.device.notifications[-1]["duration_ms"], 1500)

    def test_overlay_validates_input(self) -> None:
        runtime_for(self.device)
        with self.assertRaises(InvalidArgs):
            ui.overlay("   ")
        with self.assertRaises(InvalidArgs):
            ui.overlay("hi", duration_ms=10)
        with self.assertRaises(InvalidArgs):
            ui.overlay("hi", duration_ms=60_000)

    def test_console_write_emits_redacted_event(self) -> None:
        device = press_device()
        runtime_for(device, secrets={"TOKEN": "sekret-value"})
        ui.console.write("token is sekret-value", level="warn")
        event = device.events[-1]
        self.assertEqual(event["type"], "ui.console")
        self.assertEqual(event["data"]["level"], "warn")
        self.assertNotIn("sekret-value", event["data"]["text"])
        self.assertIn("[REDACTED]", event["data"]["text"])
        with self.assertRaises(InvalidArgs):
            ui.console.write("x", level="loud")

    def test_toast_still_works(self) -> None:
        runtime_for(self.device)
        ui.toast("hello")
        self.assertEqual(self.device.notifications[-1]["kind"], "toast")


class UiHandlerDecoratorTest(unittest.TestCase):
    def test_handler_marks_function(self) -> None:
        @ui.handler("greet")
        def greet(args: dict) -> dict:  # pragma: no cover - attribute check only
            return {}

        self.assertEqual(greet.__autohost_handler__, "greet")

    def test_sdk_calls_outside_run_are_fatal(self) -> None:
        from autohost._core import uninstall

        uninstall()
        with self.assertRaises(Exception):
            current().call("ui.prompt", "toast", {"text": "x"})


if __name__ == "__main__":
    unittest.main()
