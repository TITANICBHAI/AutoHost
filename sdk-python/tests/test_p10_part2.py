"""P10 part 2 through the simulator: the personal-data cluster, hardware cluster, SMS/telephony
(two-level gates, premium rails) and the net riders (net.socket, net.reverse) — plan §18 P10.

The sim mirrors the Kotlin gate chains (DataPolicy caps, lazy-permission availability, the
sms/telephony/net_reverse switch pairs) so the SDK surfaces are pinned PC-side.
"""
import base64
import unittest

import support

from autohost import calendar, camera, contacts, errors, location, media, microphone, sensors
from autohost import sms, sockets, telephony
from autohost_sim.device import FakeDevice

SCREENS = {
    "main": {"package": "com.fixture", "activity": "com.fixture.MainActivity", "nodes": []},
}


def make_device() -> FakeDevice:
    return FakeDevice(SCREENS, launch_map={"com.fixture": "main"})


class PersonalDataTests(unittest.TestCase):
    def setUp(self):
        self.device = make_device()
        support.runtime_for(self.device)

    def test_contacts_query_and_insert(self):
        self.device.add_grants("contacts.read", "contacts.write")
        self.device.add_contacts([{"name": "Ada Lovelace", "phone": "+15550001", "email": "ada@example.org"}])
        result = contacts.query(limit=10)
        self.assertEqual(1, result["count"])
        self.assertEqual("Ada Lovelace", result["contacts"][0]["name"])
        inserted = contacts.insert("Grace Hopper", phone="+15550002")
        self.assertEqual(1001, inserted["id"])
        self.assertEqual(1, self.device.contacts_inserted)

    def test_contacts_no_grant_is_permission_denied(self):
        with self.assertRaises(errors.PermissionDenied):
            contacts.query()

    def test_calendar_window_and_insert_validation(self):
        self.device.add_grants("calendar.read", "calendar.write")
        self.device.add_events([
            {"title": "Standup", "start_ms": 1_000, "end_ms": 2_000, "location": None, "description": None},
        ])
        result = calendar.query(from_ms=0, to_ms=3_000)
        self.assertEqual("Standup", result["events"][0]["title"])
        with self.assertRaises(errors.InvalidArgs):
            calendar.query(from_ms=5_000, to_ms=1_000)
        with self.assertRaises(errors.InvalidArgs):
            calendar.query(from_ms=0, to_ms=32 * 24 * 60 * 60 * 1000)
        with self.assertRaises(errors.InvalidArgs):
            calendar.insert("Bad", start_ms=10, end_ms=5)
        ok = calendar.insert("Retro", start_ms=10, end_ms=20)
        self.assertEqual(2001, ok["id"])

    def test_media_list_read_insert_delete(self):
        self.device.add_grants("media", "media.write")
        self.device.add_media([{"id": 5, "kind": "image", "display_name": "shot.png",
                                "size_bytes": 3, "added_ms": 42}])
        listed = media.list(kind="image")
        self.assertEqual(5, listed["media"][0]["id"])
        self.assertEqual(b"sim", media.read(5))
        with self.assertRaises(errors.NotFound):
            media.read(999)
        inserted = media.insert("image", "x.png", b"abc")
        self.assertGreater(inserted["id"], 5)  # a fresh id beyond the seeded row
        self.assertNotEqual(5, inserted["id"])
        deleted = media.delete(5)
        self.assertTrue(deleted["deleted"])
        with self.assertRaises(errors.NotFound):
            media.delete(5)
        with self.assertRaises(errors.InvalidArgs):
            media.insert("doc", "x.png", b"abc")

    def test_media_no_grant_is_permission_denied(self):
        with self.assertRaises(errors.PermissionDenied):
            media.list()


class HardwareTests(unittest.TestCase):
    def setUp(self):
        self.device = make_device()
        support.runtime_for(self.device)

    def test_location_current_and_stale_last_known(self):
        self.device.add_grants("location")
        self.device.set_location({"latitude": 52.5, "longitude": 13.4, "accuracy_m": 12.0,
                                  "time_ms": 7, "provider": "gps"})
        fix = location.current(timeout_ms=20_000)
        self.assertFalse(fix["stale"])
        self.assertEqual(52.5, fix["latitude"])
        stale = location.last_known()
        self.assertTrue(stale["stale"])

    def test_location_background_gate_is_honest(self):
        self.device.add_grants("location")
        self.device.set_location({"latitude": 1, "longitude": 2, "accuracy_m": 5, "time_ms": 1,
                                  "provider": "gps"}, background=False)
        with self.assertRaises(errors.CapabilityUnavailable) as ctx:
            location.last_known()
        self.assertIn("BACKGROUND_LOCATION", str(ctx.exception))

    def test_location_timeout_is_honest(self):
        self.device.add_grants("location")
        self.device.set_location(None)
        with self.assertRaises(errors.Timeout):
            location.current(timeout_ms=5_000)

    def test_camera_and_mic_are_bounded_and_visible(self):
        self.device.add_grants("camera", "microphone")
        shot = camera.capture_photo(front=True)
        self.assertEqual(1920, shot["width"])
        self.assertEqual([{"front": True}], self.device.captures)
        recording = microphone.record(duration_ms=2_000)
        self.assertEqual(2_000, recording["duration_ms"])
        with self.assertRaises(errors.InvalidArgs):
            microphone.record(duration_ms=61_000)

    def test_sensors_list_and_read(self):
        self.device.add_grants("sensors")
        self.device.add_sensors(
            [{"name": "lsm6", "vendor": "ST", "type": "accelerometer"}],
            samples={"lsm6": [0.1, 0.2, 9.8]},
        )
        self.assertEqual(1, sensors.list()["count"])
        samples = sensors.read(["lsm6"])
        self.assertEqual([0.1, 0.2, 9.8], samples["samples"][0]["values"])

    def test_hardware_needs_permissions(self):
        with self.assertRaises(errors.PermissionDenied):
            camera.capture_photo()
        with self.assertRaises(errors.PermissionDenied):
            location.current()


class SmsTelephonyTests(unittest.TestCase):
    def setUp(self):
        self.device = make_device()
        support.runtime_for(self.device)

    def test_two_level_gate_grant_and_switch(self):
        self.device.add_grants("device.sms")  # grant without the switch
        with self.assertRaises(errors.PermissionDenied) as ctx:
            sms.read_inbox()
        self.assertIn("sms switch", str(ctx.exception))
        self.device.add_grants("device.sms", switches=("sms",))
        self.device.add_sms([{"sender": "+15550001", "body": "hello", "received_ms": 5, "read": False}])
        result = sms.read_inbox()
        self.assertEqual("hello", result["messages"][0]["body"])

    def test_send_rails_premium_and_short_codes(self):
        self.device.add_grants("device.sms", switches=("sms",))
        result = sms.send("+15551234567", "meeting at 5")
        self.assertTrue(result["sent"])
        self.assertFalse(result["delivered"])  # never claimed
        self.assertEqual(1, len(self.device.sms_sent))
        for bad in ("1900NUMBER", "12345"):
            with self.assertRaises(errors.ScopeViolation, msg=bad):
                sms.send(bad, "x")
        with self.assertRaises(errors.InvalidArgs):
            sms.send("+15551234567", "y" * 481)

    def test_send_budget_is_ten_per_run(self):
        self.device.add_grants("device.sms", switches=("sms",))
        for i in range(10):
            sms.send(f"+1555123000{i}", "run budget")
        with self.assertRaises(errors.RateLimited):
            sms.send("+15551234567", "over budget")

    def test_wait_for_matches_and_times_out(self):
        self.device.add_grants("device.sms", switches=("sms",))
        with self.assertRaises(errors.Timeout):
            sms.wait_for(contains="nope", timeout_ms=2_000)
        self.device.add_sms([{"sender": "+15550001", "body": "code 42", "received_ms": 5, "read": False}])
        hit = sms.wait_for(contains="code 42")
        self.assertEqual("code 42", hit["body"])

    def test_telephony_two_level_and_dialer_ownership(self):
        self.device.add_grants("device.telephony", switches=("telephony",))
        self.device.set_call_state("ringing")
        self.assertEqual("ringing", telephony.call_state()["state"])
        result = telephony.place_call("+15551234567")
        self.assertTrue(result["placed"])
        self.assertFalse(result["verified"])  # the dialer owns the call
        self.assertEqual(["+15551234567"], self.device.calls_placed)
        with self.assertRaises(errors.ScopeViolation):
            telephony.place_call("1900NUMBER")


class NetRiderTests(unittest.TestCase):
    def setUp(self):
        self.device = make_device()
        support.runtime_for(self.device)

    def test_tcp_connect_domain_allowlist(self):
        self.device.add_socket_grant(["example.com"])
        handle = sockets.socket.tcp_connect("example.com", 443)
        self.assertEqual("tcp-1", handle)
        self.assertEqual([{"host": "example.com", "port": 443}], self.device.tcp_connections)
        with self.assertRaises(errors.ScopeViolation):
            sockets.socket.tcp_connect("evil.org", 443)

    def test_tcp_send_recv_close(self):
        self.device.add_socket_grant(None)
        handle = sockets.socket.tcp_connect("example.com", 443)
        sent = sockets.socket.tcp_send(handle, b"ping")
        self.assertEqual(4, sent)
        self.device.tcp_recv_data = b"pong"
        self.assertEqual(b"pong", sockets.socket.tcp_recv(handle))
        self.assertTrue(sockets.socket.tcp_close(handle))
        with self.assertRaises(errors.NotFound):
            sockets.socket.tcp_recv(handle)

    def test_udp_send_and_recv(self):
        self.device.add_socket_grant(None)
        sent = sockets.socket.udp_send("example.com", 5353, b"dns")
        self.assertEqual(3, sent)
        self.assertEqual([{"host": "example.com", "port": 5353}], self.device.udp_sends)
        self.device.udp_recv_canned = b"reply"
        result = sockets.socket.udp_recv(9999)
        self.assertEqual(b"reply", result["data"])

    def test_socket_needs_grant(self):
        with self.assertRaises(errors.PermissionDenied):
            sockets.socket.tcp_connect("example.com", 443)

    def test_socket_scope_object_restriction(self):
        self.device.add_socket_grant(["*.example.org"])
        sockets.socket.tcp_connect("deep.example.org", 443)
        sockets.socket.tcp_connect("example.org", 443)  # committed NetPolicy semantics: bare suffix allowed
        with self.assertRaises(errors.ScopeViolation):
            sockets.socket.tcp_connect("evilexample.org", 443)  # lookalike never matches

    def test_reverse_two_level_gate(self):
        self.device.add_grants("net.reverse")  # no switch yet
        with self.assertRaises(errors.PermissionDenied) as ctx:
            sockets.reverse.open("wss://pc.example.org/hook", "t0k3n")
        self.assertIn("net_reverse switch", str(ctx.exception))
        self.device.add_grants("net.reverse", switches=("net_reverse",))
        handle = sockets.reverse.open("wss://pc.example.org/hook", "t0k3n")
        self.assertEqual("rev-1", handle)
        self.assertEqual([{"url": "wss://pc.example.org/hook"}], self.device.reverse_opened)
        self.assertTrue(sockets.reverse.send(handle, "hello"))
        self.assertEqual("[sim] reverse frame", sockets.reverse.recv(handle)["text"])
        self.assertTrue(sockets.reverse.close(handle))

    def test_reverse_plain_ws_is_localhost_only(self):
        self.device.add_grants("net.reverse", switches=("net_reverse",))
        with self.assertRaises(errors.ScopeViolation):
            sockets.reverse.open("ws://remote.example.org/hook", "t0k3n")
        ok = sockets.reverse.open("ws://localhost:9999/hook", "t0k3n")
        self.assertEqual("rev-1", ok)
        with self.assertRaises(errors.InvalidArgs):
            sockets.reverse.open("wss://pc.example.org/hook", "")  # empty token


if __name__ == "__main__":
    unittest.main()
