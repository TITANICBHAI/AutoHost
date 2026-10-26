"""P9 part 1 through the simulator: device.shell (run + transports) and its allowlist semantics.

The sim mirrors the broker-side CommandAllowlist (metacharacter deny + glob patterns + fail-closed
grants), so these tests pin the SDK surface, the honest error shapes and the two-level-gate story
that the Kotlin tests cannot cover from the sandbox (plan §19 layer 5 — PC-runnable).
"""
import unittest

import support

from autohost import errors, shell
from autohost_sim.device import FakeDevice


def make_runtime(device: FakeDevice):
    return support.runtime_for(device)


SCREENS = {
    "main": {"package": "com.fixture", "activity": "com.fixture.MainActivity", "nodes": []},
}


class ShellRunTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)

    def test_allowlisted_command_runs(self):
        self.device.add_shell_tier(["settings get *", "dumpsys battery"])
        result = shell.run("settings get secure enabled_notification_listeners")
        self.assertEqual("[sim] settings get secure enabled_notification_listeners", result["stdout"])
        self.assertEqual(0, result["exit_code"])
        self.assertEqual("shell_v2", result["mode"])
        self.assertEqual("adb.tcp", result["transport"])
        self.assertEqual(["settings get secure enabled_notification_listeners"], self.device.shell_commands)

    def test_second_pattern_matches(self):
        self.device.add_shell_tier(["settings get *", "dumpsys battery"])
        result = shell.run("dumpsys battery", timeout_ms=5000)
        self.assertEqual("[sim] dumpsys battery", result["stdout"])
        self.assertEqual(5000, self.device.calls[-1][2]["timeout_ms"])

    def test_unlisted_command_is_scope_violation(self):
        self.device.add_shell_tier(["settings get *"])
        with self.assertRaises(errors.ScopeViolation) as ctx:
            shell.run("rm -rf /sdcard/important")
        self.assertIn("allowlist", str(ctx.exception))
        self.assertEqual([], self.device.shell_commands)

    def test_metacharacters_are_always_denied(self):
        self.device.add_shell_tier(["settings get *"])  # would even match all of these
        for command in [
            "settings get secure; rm -rf /data",
            "settings get secure && reboot",
            "settings get secure || reboot",
            "settings get `id`",
            "settings get $(id)",
            "settings get secure | grep x",
            "settings get secure < /etc/passwd",
            "settings get secure > /data/exfil",
        ]:
            with self.assertRaises(errors.ScopeViolation, msg=command):
                shell.run(command)
        self.assertEqual([], self.device.shell_commands)

    def test_no_grant_is_permission_denied(self):
        # Pairing without a grant object: connected transport, no device.shell grant at all.
        self.device.add_shell_tier(None)
        with self.assertRaises(errors.PermissionDenied):
            shell.run("settings get secure x")

    def test_empty_allowlist_denies_everything(self):
        self.device.add_shell_tier([])
        with self.assertRaises(errors.ScopeViolation) as ctx:
            shell.run("settings get secure x")
        self.assertIn("empty allowlist", str(ctx.exception))

    def test_unpaired_tier_is_needs_setup(self):
        with self.assertRaises(errors.CapabilityUnavailable) as ctx:
            shell.run("settings get secure x")
        self.assertIn("SHELL_TIER_NOT_PAIRED", str(ctx.exception))

    def test_timeout_bounds_are_validated_sdk_side(self):
        for bad in (0, -1, 120_001):
            with self.assertRaises(errors.InvalidArgs, msg=str(bad)):
                shell.run("settings get secure x", timeout_ms=bad)

    def test_blank_and_oversized_commands_are_rejected_sdk_side(self):
        with self.assertRaises(errors.InvalidArgs):
            shell.run("")
        with self.assertRaises(errors.InvalidArgs):
            shell.run("   ")
        with self.assertRaises(errors.InvalidArgs):
            shell.run("x" * 4001)

    def test_sim_glob_matches_kotlin_semantics(self):
        # `?` matches exactly one non-space; `*` matches any run including spaces.
        self.device.add_shell_tier(["settings get ?ecure", "dumpsys *"])
        shell.run("settings get secure")     # ? -> 's'
        shell.run("dumpsys window")          # * -> "window"
        self.assertEqual(2, len(self.device.shell_commands))


class ShellTransportsTests(unittest.TestCase):
    def setUp(self):
        self.device = FakeDevice(SCREENS, launch_map={"com.fixture": "main"})
        make_runtime(self.device)

    def test_live_transport_state(self):
        self.device.add_shell_tier(["settings get *"])
        result = shell.transports()
        self.assertEqual(1, len(result["transports"]))
        row = result["transports"][0]
        self.assertEqual("adb.tcp", row["id"])
        self.assertEqual("live", row["state"])

    def test_awaiting_authorization_state(self):
        self.device.add_shell_tier(["settings get *"], awaiting=True)
        row = shell.transports()["transports"][0]
        self.assertEqual("awaiting_authorization", row["state"])
        self.assertEqual("SHELL_AWAITING_AUTHORIZATION", row["reason"])

    def test_transports_needs_the_grant(self):
        with self.assertRaises(errors.PermissionDenied):
            shell.transports()


if __name__ == "__main__":
    unittest.main()
