"""P10 part 1 through the simulator: the browser tier (webview/a11y/cdp), vision, and the
import-guard regression for the desktop-only browser libraries (plan §18 P10).

The sim mirrors the Kotlin gate chain (BrowserPolicy domain semantics, challenge routing,
honest input_mode) so the SDK surface and the Gate-10 story are pinned PC-side.
"""
import unittest

import support

from autohost import browser, errors, vision
from autohost_sim.device import FakeDevice

SCREENS = {
    "main": {"package": "com.fixture", "activity": "com.fixture.MainActivity", "nodes": []},
}


def make_device() -> FakeDevice:
    return FakeDevice(SCREENS, launch_map={"com.fixture": "main"})


class BrowserWebviewTests(unittest.TestCase):
    def setUp(self):
        self.device = make_device()
        support.runtime_for(self.device)

    def test_open_local_task_page(self):
        self.device.add_browser_grant(None)
        self.device.add_web_page(
            "task:web/login.html", title="Local login",
            values={"#status": ["signed in"]}, cookies={"session": "abc"},
        )
        page = browser.open("task:web/login.html")
        self.assertEqual("pg-1", page["page_id"])
        self.assertEqual("Local login", page["title"])
        self.assertEqual("loaded", page["load_state"])
        self.assertEqual("real", page["input_mode"])
        self.assertNotIn("challenge", page)

    def test_open_http_page_with_scoped_grant(self):
        self.device.add_browser_grant(["*.example.org"])
        self.device.add_web_page("https://a.example.org/x", title="X")
        page = browser.open("https://a.example.org/x", profile="p1")
        self.assertEqual("X", page["title"])
        self.assertEqual("p1", page["profile"])

    def test_domain_outside_allowlist_is_scope_violation(self):
        self.device.add_browser_grant(["*.example.org"])
        with self.assertRaises(errors.ScopeViolation):
            browser.open("https://evil.org/login")
        self.assertEqual([], self.device.web_pages_opened)

    def test_empty_allowlist_denies_all(self):
        self.device.add_browser_grant([])
        with self.assertRaises(errors.ScopeViolation):
            browser.open("https://example.org/")

    def test_no_grant_is_permission_denied(self):
        with self.assertRaises(errors.PermissionDenied):
            browser.open("https://example.org/")

    def test_file_and_javascript_schemes_are_refused(self):
        self.device.add_browser_grant(None)
        for url in ("file:///sdcard/secret.txt", "javascript:alert(1)", "data:text/html,x"):
            with self.assertRaises(errors.ScopeViolation, msg=url):
                browser.open(url)

    def test_task_escape_is_refused(self):
        self.device.add_browser_grant(None)
        with self.assertRaises(errors.ScopeViolation):
            browser.open("task:../../etc/passwd")

    def test_challenge_routes_to_waiting_user_by_default(self):
        self.device.add_browser_grant(None)
        self.device.add_web_page(
            "https://example.org/protected",
            challenge={"kind": "captcha", "matched": "url:/recaptcha/"},
        )
        with self.assertRaises(errors.NeedsUser) as ctx:
            browser.open("https://example.org/protected")
        self.assertEqual("SECURITY_CHALLENGE", ctx.exception.reason)

    def test_challenge_report_mode_returns_the_dict(self):
        self.device.add_browser_grant(None)
        self.device.add_web_page(
            "https://example.org/protected",
            challenge={"kind": "anti_bot", "matched": "title:/Attention required/"},
        )
        page = browser.open("https://example.org/protected", challenge="report")
        self.assertEqual("anti_bot", page["challenge"]["kind"])

    def test_click_type_extract_roundtrip(self):
        self.device.add_browser_grant(None)
        self.device.add_web_page("task:page.html", values={"#out": ["hello", "world"]})
        page = browser.open("task:page.html")
        click = browser.click(page["page_id"], "#out")
        self.assertTrue(click["clicked"])
        self.assertEqual("real", click["input_mode"])
        typed = browser.type(page["page_id"], "#out", "secret-value")
        self.assertTrue(typed["typed"])
        self.assertNotIn("secret-value", str(typed))
        got = browser.extract(page["page_id"], "#out")
        self.assertEqual(["hello", "world"], got["values"])
        missing = browser.click(page["page_id"], "#nope")
        self.assertFalse(missing["clicked"])
        self.assertFalse(missing["found"])

    def test_cookies_are_profile_scoped(self):
        self.device.add_browser_grant(None)
        self.device.add_web_page("task:a.html", cookies={"k": "v1"})
        self.device.add_web_page("task:b.html", cookies={"k": "v2"})
        page_a = browser.open("task:a.html", profile="pa")
        page_b = browser.open("task:b.html", profile="pb")
        self.assertEqual("v1", browser.cookies(page_a["page_id"])["cookies"][0]["value"])
        self.assertEqual("v2", browser.cookies(page_b["page_id"])["cookies"][0]["value"])
        browser.clear_cookies(page_a["page_id"])
        self.assertEqual([], browser.cookies(page_a["page_id"])["cookies"])
        self.assertEqual("v2", browser.cookies(page_b["page_id"])["cookies"][0]["value"])
        self.assertEqual(["pa"], self.device.cookies_cleared)

    def test_page_cap_is_four(self):
        self.device.add_browser_grant(None)
        pages = [browser.open(f"task:p{i}.html")["page_id"] for i in range(4)]
        self.assertEqual(4, len(browser.pages()["pages"]))
        with self.assertRaises(errors.ScopeViolation):
            browser.open("task:p4.html")
        browser.close(pages[0])
        browser.open("task:p4.html")
        self.assertEqual(4, len(browser.pages()["pages"]))

    def test_unknown_page_is_not_found(self):
        self.device.add_browser_grant(None)
        with self.assertRaises(errors.NotFound):
            browser.extract("pg-none", "#x")

    def test_timeout_bounds_validated_sdk_side(self):
        self.device.add_browser_grant(None)
        for bad in (0, -5, 120_001):
            with self.assertRaises(errors.InvalidArgs, msg=str(bad)):
                browser.open("task:x.html", timeout_ms=bad)


class BrowserA11yTests(unittest.TestCase):
    def setUp(self):
        self.device = make_device()
        support.runtime_for(self.device)

    def test_open_click_extract_on_the_user_browser(self):
        self.device.add_browser_grant(None)
        self.device.add_a11y_browser("com.android.chrome", lines=["Welcome", "Sign in"])
        result = browser.a11y_open("https://example.org/")
        self.assertEqual("com.android.chrome", result["package"])
        self.assertEqual("a11y", result["page"]["input_mode"])
        click = browser.a11y_click("sign in")
        self.assertTrue(click["clicked"])
        self.assertEqual("a11y", click["input_mode"])
        extract = browser.a11y_extract()
        self.assertEqual(["Welcome", "Sign in"], extract["values"])

    def test_challenge_detected_in_the_user_browser(self):
        self.device.add_browser_grant(None)
        self.device.add_a11y_browser(
            "com.android.chrome", lines=["Verify you are human"],
            challenge={"kind": "anti_bot", "matched": "text:/verify you are/"},
        )
        with self.assertRaises(errors.NeedsUser) as ctx:
            browser.a11y_open("https://example.org/")
        self.assertEqual("SECURITY_CHALLENGE", ctx.exception.reason)

    def test_cookies_are_honestly_unavailable_on_this_link(self):
        self.device.add_browser_grant(None)
        self.device.add_a11y_browser("com.android.chrome", lines=[])
        with self.assertRaises(errors.CapabilityUnavailable) as ctx:
            browser.current().call("browser.a11y", "cookies", {})
        self.assertIn("COOKIES_UNSUPPORTED_LINK", str(ctx.exception))

    def test_no_a11y_service_is_needs_setup(self):
        self.device.add_browser_grant(None)
        with self.assertRaises(errors.CapabilityUnavailable) as ctx:
            browser.a11y_open("https://example.org/")
        self.assertIn("ACCESSIBILITY_SERVICE_OFF", str(ctx.exception))

    def test_task_urls_do_not_resolve_here(self):
        self.device.add_browser_grant(None)
        self.device.add_a11y_browser("com.android.chrome", lines=[])
        with self.assertRaises(errors.ScopeViolation):
            browser.a11y_open("task:web/x.html")


class BrowserCdpTests(unittest.TestCase):
    def setUp(self):
        self.device = make_device()
        support.runtime_for(self.device)
        self.device.add_browser_grant(None)

    def test_status_is_always_honest_about_the_draft(self):
        status = browser.cdp_status()
        self.assertFalse(status["verified_on_hardware"])
        self.assertFalse(status["shell_live"])
        self.assertIn("S27", status["note"])

    def test_without_shell_is_needs_setup(self):
        with self.assertRaises(errors.CapabilityUnavailable) as ctx:
            browser.cdp_evaluate("1+1")
        self.assertIn("SHELL_TIER_NOT_PAIRED", str(ctx.exception))

    def test_live_route_serves_canned_results(self):
        self.device.add_cdp(evaluate_value="2", cookies=[{"name": "sid", "value": "x"}])
        page = browser.cdp_navigate("https://example.org/")
        self.assertEqual("cdp", page["input_mode"])
        result = browser.cdp_evaluate("1+1")
        self.assertEqual("2", result["result"]["value"])
        self.assertEqual("sid", browser.cdp_cookies()["cookies"][0]["name"])
        self.assertTrue(browser.cdp_close()["closed"])
        self.assertEqual(1, self.device.cdp_closed)

    def test_url_allowlist_still_applies(self):
        self.device.add_cdp()
        self.device.add_browser_grant(["*.example.org"])
        with self.assertRaises(errors.ScopeViolation):
            browser.cdp_navigate("https://off-allowlist.net/")

    def test_task_urls_do_not_resolve_over_cdp(self):
        self.device.add_cdp()
        with self.assertRaises(errors.ScopeViolation):
            browser.cdp_navigate("task:web/x.html")


class VisionTests(unittest.TestCase):
    def setUp(self):
        self.device = make_device()
        support.runtime_for(self.device)
        self.device.add_grants("vision.read")

    def test_ocr_returns_hinted_lines(self):
        self.device.add_ocr(["TOTAL 12.40", "THANK YOU"])
        result = vision.ocr(region={"left": 0, "top": 0, "width": 200, "height": 60})
        self.assertTrue(result["hint"])
        self.assertEqual(["TOTAL 12.40", "THANK YOU"], [line["text"] for line in result["lines"]])

    def test_ocr_region_validation(self):
        with self.assertRaises(errors.InvalidArgs):
            vision.ocr(region={"left": 0, "top": 0, "width": 0, "height": 10})
        with self.assertRaises(errors.InvalidArgs):
            vision.ocr(region={"left": -1, "top": 0, "width": 10, "height": 10})

    def test_template_match_canned_result_and_validation(self):
        self.device.set_template_result(found=True, score=0.97, box=(40, 30, 60, 28))
        result = vision.template_match("needles/btn.png", region={"left": 0, "top": 0, "width": 400, "height": 200})
        self.assertTrue(result["hint"])
        self.assertTrue(result["found"])
        self.assertEqual(0.97, result["score"])
        self.assertEqual({"left": 40, "top": 30, "width": 60, "height": 28}, result["box"])
        for bad_threshold in (0.1, 1.5):
            with self.assertRaises(errors.InvalidArgs):
                vision.template_match("n.png", threshold=bad_threshold)
        for bad_stride in (0, 33):
            with self.assertRaises(errors.InvalidArgs):
                vision.template_match("n.png", stride=bad_stride)

    def test_template_match_needs_needle(self):
        with self.assertRaises(errors.InvalidArgs):
            vision.template_match("")

    def test_qr_decode_roundtrip(self):
        empty = vision.qr_decode()
        self.assertFalse(empty["found"])
        self.assertNotIn("value", empty)
        self.device.add_qr("otpauth://x")
        result = vision.qr_decode()
        self.assertTrue(result["found"])
        self.assertEqual("otpauth://x", result["value"])

    def test_file_source_requires_path(self):
        with self.assertRaises(errors.InvalidArgs):
            vision.ocr(source="file")
        with self.assertRaises(errors.InvalidArgs):
            vision.ocr(source="gopher")

    def test_no_grant_is_permission_denied(self):
        device2 = make_device()
        support.runtime_for(device2)
        with self.assertRaises(errors.PermissionDenied):
            vision.ocr()


class ScanRegressionTests(unittest.TestCase):
    """The P10 fix: a playwright import must yield a suggestion, not a NameError
    (the committed scanner had DESKTOP_ONLYodule — a NameError on the desktop-only path)."""

    def test_desktop_only_import_produces_suggestion(self):
        import tempfile
        import os
        from autohost._scan import scan_dir
        with tempfile.TemporaryDirectory() as tmp:
            with open(os.path.join(tmp, "task.py"), "w") as fh:
                fh.write("import playwright\n")
            report = scan_dir(tmp)
        modules = {hit["module"]: hit for hit in report["desktopOnly"]}
        self.assertIn("playwright", modules)
        self.assertIn("browser", modules["playwright"]["suggestion"])

    def test_selenium_suggestion_points_at_the_browser_capability(self):
        import tempfile
        import os
        from autohost._scan import scan_dir
        with tempfile.TemporaryDirectory() as tmp:
            with open(os.path.join(tmp, "task.py"), "w") as fh:
                fh.write("import selenium\n")
            report = scan_dir(tmp)
        modules = {hit["module"]: hit for hit in report["desktopOnly"]}
        self.assertIn("selenium", modules)
        self.assertIn("browser", modules["selenium"]["suggestion"])


if __name__ == "__main__":
    unittest.main()
