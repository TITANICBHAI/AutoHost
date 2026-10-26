"""P9 part 2 through the simulator: device.app.manage, device.logcat, device.radio/display/
device.location.mock and the device.settings write path.

The sim mirrors the Kotlin two-level gates (grant AND switch, plan §14.5), the confirm rails on
destructive methods and the honest result shapes, so these tests pin the SDK surface from the
sandbox (plan §19 layer 5). The UiAutomation exclusivity rule is JVM-tested (pure policy); the sim
has no ui.automation link by design.
"""
import unittest

import support

from autohost import app_manage, devicectl, device, errors, logcat
from autohost_sim.device import FakeDevice


def make_runtime(device: FakeDevice):
    return support.runtime_for(device)


SCREENS = {
    "main": {"package": "com.fixture", "activity": "com.fixture.MainActivity", "nodes": []},
}


class AppManageTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)
        self.device.add_shell_tier(["*"])
        self.device.add_grants("device.app.manage", switches=("app_manage",))

    def test_force_stop_is_verified_through_the_shell(self):
        result = app_manage.force_stop("com.target.app")
        self.assertEqual("VERIFIED", result["result"])
        self.assertEqual("shell", result["method"])
        self.assertEqual(0, result["exit_code"])
        self.assertEqual(
            [{"method": "force_stop", "package": "com.target.app"}],
            self.device.app_manage_calls,
        )

    def test_missing_grant_is_permission_denied(self):
        self.device.add_shell_tier(["*"])
        self.device.grants.clear()
        with self.assertRaises(errors.PermissionDenied):
            app_manage.force_stop("com.target.app")

    def test_missing_switch_is_insecure_feature_disabled(self):
        self.device.switches.clear()
        with self.assertRaises(errors.PermissionDenied) as ctx:
            app_manage.force_stop("com.target.app")
        self.assertEqual("INSECURE_FEATURE_DISABLED", ctx.exception.code)

    def test_clear_state_demands_confirm(self):
        with self.assertRaises(errors.InvalidArgs) as ctx:
            app_manage.clear_state("com.target.app")
        self.assertIn("confirm", str(ctx.exception))
        self.assertEqual([], self.device.app_manage_calls)
        result = app_manage.clear_state("com.target.app", confirm=True)
        self.assertEqual("VERIFIED", result["result"])
        self.assertEqual("pm_clear", result["method"])

    def test_permission_names_must_be_fully_qualified(self):
        with self.assertRaises(errors.InvalidArgs):
            app_manage.grant_permission("com.target.app", "CAMERA")
        result = app_manage.grant_permission("com.target.app", "android.permission.CAMERA")
        self.assertEqual("pm_grant", result["method"])
        self.assertFalse(result["verified"])  # honest: no readback claim

    def test_install_validates_path_and_mode(self):
        with self.assertRaises(errors.InvalidArgs):
            app_manage.install("relative/path.apk", confirm=True)
        with self.assertRaises(errors.InvalidArgs):
            app_manage.install("/data/local/tmp/app.bin", confirm=True)
        with self.assertRaises(errors.InvalidArgs):
            app_manage.install("/data/local/tmp/app.apk", confirm=True, mode="sideload")
        result = app_manage.install("/data/local/tmp/app.apk", confirm=True)
        self.assertEqual("VERIFIED", result["result"])
        self.assertEqual("pm", result["method"])
        installer = app_manage.install("/data/local/tmp/app.apk", confirm=True, mode="installer")
        self.assertEqual("CONFIRMED", installer["result"])
        self.assertEqual("installer", installer["method"])

    def test_uninstall_needs_confirm(self):
        with self.assertRaises(errors.InvalidArgs):
            app_manage.uninstall("com.target.app")
        result = app_manage.uninstall("com.target.app", confirm=True)
        self.assertEqual("VERIFIED", result["result"])


class LogcatTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)
        self.device.add_shell_tier(["*"])
        self.device.add_grants("device.logcat", switches=("logcat",))
        self.device.add_logcat([
            {"ts": 1000, "level": "D", "tag": "AutoHost", "pid": 42, "message": "worker started"},
            {"ts": 2000, "level": "I", "tag": "AutoHost", "pid": 42, "message": "run ok"},
            {"ts": 3000, "level": "E", "tag": "Other", "pid": 7, "message": "boom in AutoHost too"},
        ])

    def test_snapshot_returns_all_lines_without_filters(self):
        result = logcat.snapshot()
        self.assertEqual(3, result["count"])
        self.assertEqual(3, result["parsed"])
        self.assertEqual("run ok", result["lines"][1]["message"])

    def test_level_and_contains_filters(self):
        result = logcat.snapshot(level="W")
        self.assertEqual(1, result["count"])
        self.assertEqual("E", result["lines"][0]["level"])
        result = logcat.snapshot(contains="run ok")
        self.assertEqual(1, result["count"])
        self.assertEqual("AutoHost", result["lines"][0]["tag"])

    def test_package_filter_is_honest_when_pid_unknown(self):
        result = logcat.snapshot(package="com.missing.app")
        self.assertEqual([], result["lines"])
        self.assertEqual(0, result["count"])
        self.assertFalse(result["package_pid"])

    def test_clear(self):
        self.assertTrue(logcat.clear()["cleared"])
        self.assertEqual(1, self.device.logcat_cleared)
        self.assertEqual(0, logcat.snapshot()["count"])

    def test_two_level_gate(self):
        self.device.switches.clear()
        with self.assertRaises(errors.PermissionDenied):
            logcat.snapshot()
        self.device.switches.add("logcat")
        self.device.grants.clear()
        with self.assertRaises(errors.PermissionDenied):
            logcat.snapshot()


class DeviceControlTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)
        self.device.add_shell_tier(["*"])
        self.device.add_grants(
            "device.radio", "device.display", "device.location.mock",
            switches=("mock_location",),
        )

    def test_radio_toggles_update_sim_state(self):
        result = devicectl.set_wifi(False)
        self.assertFalse(result["verified"])  # honest: no readback claim
        self.assertFalse(self.device.radio_state["wifi"])
        devicectl.set_airplane(True)
        self.assertTrue(self.device.radio_state["airplane"])

    def test_radio_needs_the_grant(self):
        self.device.grants.clear()
        with self.assertRaises(errors.PermissionDenied):
            devicectl.set_wifi(False)

    def test_display_control(self):
        devicectl.set_dark_mode("yes")
        self.assertEqual("yes", self.device.display_state["night"])
        devicectl.set_orientation("landscape")
        self.assertEqual("landscape", self.device.display_state["orientation"])
        devicectl.set_font_scale(1.2)
        self.assertEqual("1.2", self.device.display_state["font_scale"])
        with self.assertRaises(errors.InvalidArgs):
            devicectl.set_orientation("diagonal")

    def test_mock_location_is_gated_and_verified(self):
        result = devicectl.set_mock_location(True)
        self.assertTrue(result["verified"])
        self.assertEqual("allow", self.device.mock_location_appop)
        self.assertEqual("app.autohost", result["appop_package"])
        self.device.switches.clear()
        with self.assertRaises(errors.PermissionDenied):
            devicectl.set_mock_location(False)


class SettingsWriteTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)

    def test_write_round_trips_and_reports_the_path(self):
        result = device.settings_write("system", "user_rotation", "1", confirm=True)
        self.assertTrue(result["written"])
        self.assertEqual("write_secure", result["path"])
        self.assertTrue(result["verified"])
        self.assertEqual("1", device.settings_read("system", "user_rotation")["value"])

    def test_protected_keys_are_refused_sdk_side(self):
        with self.assertRaises(errors.InvalidArgs):
            device.settings_write("secure", "default_input_method", "x")

    def test_secure_needs_confirm(self):
        with self.assertRaises(errors.InvalidArgs):
            device.settings_write("secure", "some.key", "v")
        result = device.settings_write("secure", "some.key", "v", confirm=True)
        self.assertTrue(result["written"])

    def test_value_caps(self):
        with self.assertRaises(errors.InvalidArgs):
            device.settings_write("system", "k", "v" * 1001)


if __name__ == "__main__":
    unittest.main()
