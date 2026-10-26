"""P8 part 1 capabilities through the simulator: notifications, clipboard, intents, events.

The sim returns broker envelopes exactly like the real bridge, so these tests pin the SDK surface
and the honest error shapes (plan §19 layer 5 — PC-runnable, no phone needed).
"""
import unittest

import support

import support

from autohost import clipboard, errors, events, intents, notifications
from autohost_sim.device import FakeDevice


def make_runtime(device: FakeDevice):
    return support.runtime_for(device)


SCREENS = {
    "main": {"package": "com.fixture", "activity": "com.fixture.MainActivity", "nodes": []},
}


class NotificationsTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)

    def test_list_and_read(self):
        self.device.inject_notification("n1", "com.mail", title="Invoice", text="due tomorrow")
        rows = notifications.list()
        self.assertEqual(1, len(rows))
        self.assertEqual("Invoice", rows[0]["title"])
        full = notifications.read("n1")
        self.assertEqual("com.mail", full["package"])

    def test_list_package_filter(self):
        self.device.inject_notification("n1", "com.mail", title="a")
        self.device.inject_notification("n2", "com.chat", title="b")
        rows = notifications.list(package="com.chat")
        self.assertEqual(["n2"], [r["id"] for r in rows])

    def test_press_by_label_and_index(self):
        self.device.inject_notification("n1", "com.mail", title="a", actions=["Open", "Archive"])
        result = notifications.press("n1", "Archive")
        self.assertEqual(1, result["action_index"])
        self.assertEqual(0, notifications.press("n1", 0)["action_index"])

    def test_press_without_actions_is_invalid(self):
        self.device.inject_notification("n1", "com.mail", title="a")
        with self.assertRaises(errors.InvalidArgs):
            notifications.press("n1")

    def test_dismiss_and_dismiss_all(self):
        self.device.inject_notification("n1", "com.mail", title="a")
        self.device.inject_notification("n2", "com.mail", title="b", ongoing=True)
        self.assertTrue(notifications.dismiss("n1")["dismissed"])
        result = notifications.dismiss_all()
        self.assertEqual(0, result["dismissed"])  # only the ongoing one is left
        self.assertEqual(1, len(notifications.list()))

    def test_wait_for_found_and_timeout(self):
        self.device.inject_notification("n1", "com.mail", title="Invoice due")
        found = notifications.wait_for(title_regex="Invoice", timeout_ms=100)
        self.assertTrue(found["found"])
        with self.assertRaises(errors.Timeout):
            notifications.wait_for(title_regex="nope", timeout_ms=50)

    def test_sdk_validates_args(self):
        with self.assertRaises(errors.InvalidArgs):
            notifications.read("")
        with self.assertRaises(errors.InvalidArgs):
            notifications.wait_for(timeout_ms=-1)


class ClipboardTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)

    def test_set_then_get_while_focused(self):
        clipboard.set("hello")
        result = clipboard.get()
        self.assertEqual("hello", result["text"])
        self.assertTrue(result["focused"])

    def test_background_read_is_honest(self):
        clipboard.set("secret")
        self.device.clipboard_focused = False
        with self.assertRaises(errors.CapabilityUnavailable) as ctx:
            clipboard.get()
        self.assertIn("device.ime.clipboard_get", str(ctx.exception))

    def test_set_validates(self):
        with self.assertRaises(errors.InvalidArgs):
            clipboard.set(123)


class IntentsTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)

    def test_open_url_allows_http_https_only(self):
        self.assertTrue(intents.open_url("https://example.com")["dispatched"])
        with self.assertRaises(errors.ScopeViolation):
            intents.open_url("intent://evil")

    def test_open_settings_known_pages(self):
        self.assertTrue(intents.open_settings("wifi")["dispatched"])
        with self.assertRaises(errors.ScopeViolation):
            intents.open_settings("root")

    def test_start_activity_and_broadcast_dispatch(self):
        result = intents.start_activity(action="com.example.RUN", extras={"k": "1"})
        self.assertTrue(result["dispatched"])
        self.assertFalse(result["verified"])  # honest: unverified by design
        self.assertTrue(intents.broadcast("com.example.PING")["dispatched"])
        kinds = [(i["method"], i["action"]) for i in self.device.intents_dispatched]
        self.assertIn(("start_activity", "com.example.RUN"), kinds)
        self.assertIn(("broadcast", "com.example.PING"), kinds)


class EventsTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)

    def test_recent_returns_injected_events(self):
        self.device.emit_event("screen.on")
        self.device.emit_event("battery.low", payload={"level": 14})
        rows = events.recent()
        self.assertEqual(2, len(rows))
        self.assertEqual("battery.low", rows[-1]["kind"])

    def test_recent_filters_by_kind(self):
        self.device.emit_event("screen.on")
        self.device.emit_event("battery.low")
        rows = events.recent(kinds=["battery.low"])
        self.assertEqual(["battery.low"], [r["kind"] for r in rows])

    def test_recent_limit(self):
        for i in range(5):
            self.device.emit_event("screen.on")
        self.assertEqual(2, len(events.recent(limit=2)))


if __name__ == "__main__":
    unittest.main()
