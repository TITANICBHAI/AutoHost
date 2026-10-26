"""P8 part 2 capabilities through the simulator: files.shared, net.ws, net.server, audio, power,
ui.overlay, device.settings, device.screen control, tasks.run, android.api.direct.

The sim returns broker envelopes exactly like the real bridge, so these tests pin the SDK surface
and the honest error shapes (plan §19 layer 5 — PC-runnable, no phone needed).
"""
import base64
import unittest

import support

from autohost import (android, audio, device, errors, events, files, net, power, tasks, ui)
from autohost_sim.device import FakeDevice

TREE = "content://com.android.externalstorage.documents/tree/primary%3ADownload"


def make_runtime(device: FakeDevice):
    return support.runtime_for(device)


SCREENS = {
    "main": {"package": "com.fixture", "activity": "com.fixture.MainActivity", "nodes": []},
}


class FilesSharedTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)
        self.device.add_tree(TREE, {"notes/a.txt": "hello", "b.bin": b"\x00\x01"})

    def test_list_read_write_delete_round_trip(self):
        rows = files.shared.list(TREE, "notes")
        self.assertEqual(["a.txt"], [r["name"] for r in rows])
        self.assertEqual("hello", files.shared.read(TREE, "notes/a.txt"))
        raw = files.shared.read(TREE, "b.bin", binary=True)
        self.assertEqual(b"\x00\x01", raw)
        written = files.shared.write(TREE, "notes/c.txt", "world")
        self.assertEqual(5, written)
        self.assertEqual("world", files.shared.read(TREE, "notes/c.txt"))
        self.assertTrue(files.shared.delete(TREE, "notes/c.txt"))
        with self.assertRaises(errors.NotFound):
            files.shared.read(TREE, "notes/c.txt")

    def test_ungranted_tree_is_scope_violation(self):
        with self.assertRaises(errors.ScopeViolation):
            files.shared.list("content://other/tree/x")

    def test_traversal_is_rejected(self):
        with self.assertRaises(errors.ScopeViolation):
            files.shared.read(TREE, "../../etc/passwd")

    def test_media_round_trip_and_permission_gate(self):
        rows = files.shared.media_list("images")
        self.assertEqual([], rows)
        rid = files.shared.media_write("shot.png", b"png-bytes", media_type="images")
        rows = files.shared.media_list("images")
        self.assertEqual([rid], [r["id"] for r in rows])
        self.assertEqual(b"png-bytes", files.shared.media_read(rid, binary=True))
        self.assertTrue(files.shared.media_delete(rid))

        self.device.media_permission = False
        with self.assertRaises(errors.CapabilityUnavailable) as ctx:
            files.shared.media_list()
        self.assertIn("runtime permission", str(ctx.exception))


class NetWsTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)
        self.device.ws_endpoints["ws://sim.local/feed"] = ["one", "two"]

    def test_connect_send_recv_close(self):
        handle = net.ws.connect("ws://sim.local/feed")
        self.assertTrue(handle)
        self.assertTrue(net.ws.send(handle, "hi"))
        self.device.ws_endpoints["ws://sim.local/feed"].insert(0, "ack")
        frame = net.ws.recv(handle, timeout_ms=100)
        self.assertEqual("text", frame["kind"])
        self.assertEqual("ack", frame["text"])
        self.assertTrue(net.ws.close(handle))

    def test_scope_enforced(self):
        self.device.ws_scope_domains = ["other.local"]
        with self.assertRaises(errors.ScopeViolation):
            net.ws.connect("ws://sim.local/feed")

    def test_sdk_validates_url(self):
        with self.assertRaises(errors.InvalidArgs):
            net.ws.connect("http://sim.local/feed")


class NetServerTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)

    def test_status_and_hook_names(self):
        status = net.webhook_status()
        self.assertTrue(status["running"])
        self.assertEqual(8082, status["port"])
        self.assertFalse(status["lan"])
        self.device.hook_names = ["inbox"]
        self.assertEqual(["inbox"], net.webhook_names())


class AudioTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)

    def test_volume_read_and_set(self):
        vol = audio.volume("music")
        self.assertEqual(8, vol["level"])
        changed = audio.volume("music", level=50)
        # max is 15 → 50% maps to level 8, which reads back as 53% (honest rounding, both ways)
        self.assertEqual(8, changed["level"])
        self.assertEqual(53, changed["percent"])
        self.assertEqual(8, audio.volume("music")["level"])

    def test_volume_validates(self):
        with self.assertRaises(errors.InvalidArgs):
            audio.volume("nonsense")
        with self.assertRaises(errors.InvalidArgs):
            audio.volume("music", level=150)

    def test_ringer_and_dnd(self):
        self.assertEqual("normal", audio.ringer()["mode"])
        self.assertEqual("vibrate", audio.ringer("vibrate")["mode"])
        self.assertFalse(audio.dnd()["on"])

    def test_speak_vibrate_play_sound_are_recorded(self):
        self.assertTrue(audio.speak("hello", queue=True)["spoken"])
        self.assertTrue(audio.vibrate(150)["vibrated"])
        self.assertTrue(audio.play_sound("confirm")["played"])
        self.assertEqual(1, len(self.device.tts_log))
        self.assertEqual([150], self.device.vibrate_log)
        self.assertEqual(["confirm"], self.device.played_tones)
        with self.assertRaises(errors.InvalidArgs):
            audio.speak("")

    def test_flashlight_honest_unavailable(self):
        self.assertTrue(audio.flashlight(True)["on"])
        self.device.torch_available = False
        with self.assertRaises(errors.CapabilityUnavailable):
            audio.flashlight(True)


class PowerTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)

    def test_battery_state(self):
        state = power.battery()
        self.assertEqual(80, state["level"])
        self.assertFalse(state["charging"])
        self.device.battery_state = {"level": 15, "charging": True, "plugged": "ac", "health": "good"}
        self.assertTrue(power.battery()["charging"])

    def test_wake_lock_and_keep_awake(self):
        self.assertTrue(power.wake_lock(reason="long job")["held"])
        self.assertTrue(self.device.wake_lock_held)
        power.wake_lock(hold=False)
        self.assertFalse(self.device.wake_lock_held)
        self.assertTrue(power.keep_awake(True)["on"])


class UiOverlayTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)

    def test_highlight_panel_clear(self):
        self.assertTrue(ui.highlight(10, 20, 30, 40, label="target")["shown"])
        self.assertTrue(ui.panel("step 2/5", corner="bottom")["shown"])
        kinds = [o["kind"] for o in self.device.overlays]
        self.assertEqual(["highlight", "panel"], kinds)
        self.assertTrue(ui.overlay_clear()["cleared"])
        self.assertEqual([], self.device.overlays)

    def test_a11y_off_is_honest(self):
        self.device.a11y_overlay_connected = False
        with self.assertRaises(errors.CapabilityUnavailable):
            ui.panel("nope")

    def test_sdk_validates(self):
        with self.assertRaises(errors.InvalidArgs):
            ui.highlight(-1, 0, 10, 10)
        with self.assertRaises(errors.InvalidArgs):
            ui.panel("")


class DeviceSettingsTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)
        self.device.settings_table = {("secure", "enabled_notification_listeners"): "app.autohost/x"}

    def test_read_and_read_many(self):
        row = device.settings_read("secure", "enabled_notification_listeners")
        self.assertEqual("app.autohost/x", row["value"])
        values = device.settings_read_many("secure", ["enabled_notification_listeners", "nope"])
        values = values.get("values", values)
        self.assertEqual("app.autohost/x", values["enabled_notification_listeners"])
        self.assertIsNone(values["nope"])

    def test_table_validated(self):
        with self.assertRaises(errors.InvalidArgs):
            device.settings_read("bogus", "x")


class ScreenControlTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)

    def test_wake_and_lock(self):
        self.device.screen_on = False
        self.assertTrue(device.screen.wake()["on"])
        self.assertTrue(device.screen.lock()["locked"])
        self.assertFalse(self.device.screen_on)

    def test_keyguard_dismiss_honest_about_secure(self):
        self.device.keyguard_secured = False
        self.assertTrue(device.screen.dismiss_keyguard()["dismissed"])
        self.device.keyguard_secured = True
        result = device.screen.dismiss_keyguard()
        self.assertFalse(result["dismissed"])
        self.assertTrue(result["secure"])

    def test_brightness_gated(self):
        self.assertEqual(60, device.screen.brightness()["level"])
        self.device.write_settings_granted = True
        self.assertEqual(30, device.screen.brightness(30)["level"])
        self.device.write_settings_granted = False
        with self.assertRaises(errors.CapabilityUnavailable):
            device.screen.brightness(10)
        with self.assertRaises(errors.InvalidArgs):
            device.screen.brightness(120)


class TasksRunTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)

    def test_chain_success(self):
        self.device.chain_results["app.autohost.sample.hello"] = {"status": "SUCCEEDED", "result": "ok"}
        outcome = tasks.run("app.autohost.sample.hello")
        self.assertEqual("SUCCEEDED", outcome["status"])

    def test_unknown_task_not_found(self):
        with self.assertRaises(errors.NotFound):
            tasks.run("app.autohost.missing")

    def test_sdk_validates(self):
        with self.assertRaises(errors.InvalidArgs):
            tasks.run("")


class AndroidDirectTests(unittest.TestCase):
    def setUp(self):
        android._AUTHORIZED = False  # module-global gate: reset per test
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)

    def test_gate_switch_off(self):
        self.assertFalse(android.is_authorized())
        with self.assertRaises(errors.PermissionDenied):
            android.authorize()
        with self.assertRaises(errors.CapabilityUnavailable):
            android.jclass("java.lang.String")

    def test_gate_without_grant(self):
        self.device.raw_api_switch = True
        with self.assertRaises(errors.PermissionDenied):
            android.authorize()

    def test_authorized_then_passthrough_needs_device(self):
        self.device.raw_api_switch = True
        self.device.raw_bridge_authorized = True
        marker = android.authorize()
        self.assertTrue(marker["authorized"])
        self.assertTrue(android.is_authorized())
        with self.assertRaises(errors.CapabilityUnavailable):
            android.jclass("java.lang.String")  # honest: no Chaquopy on the PC

    def test_system_service_before_gate(self):
        with self.assertRaises(errors.CapabilityUnavailable):
            android.system_service("window")


if __name__ == "__main__":
    unittest.main()
