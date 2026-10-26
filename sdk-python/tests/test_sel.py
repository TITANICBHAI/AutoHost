import unittest

import support  # noqa: F401
from autohost import sel


class SelectorJson(unittest.TestCase):
    def test_text_defaults_to_contains_and_case_insensitive(self):
        self.assertEqual(sel.text("Continue").to_json(), {
            "op": "and",
            "terms": [{"text": {"labels": ["Continue"], "mode": "contains", "case_sensitive": False}}]})

    def test_multiple_labels_are_kept_in_order(self):
        j = sel.text("Continue", "Weiter").to_json()
        self.assertEqual(j["terms"][0]["text"]["labels"], ["Continue", "Weiter"])

    def test_and_or_nest_selectors(self):
        a, b = sel.text("A"), sel.clickable()
        self.assertEqual((a & b).to_json()["op"], "and")
        self.assertEqual((a | b).to_json()["op"], "or")
        self.assertEqual((a & b).to_json()["terms"][0], {"sel": a.to_json()})

    def test_within_nth_and_bfs_fields(self):
        j = sel.text("Row").within(sel.id("list")).nth(2).bfs().to_json()
        self.assertEqual(j["nth"], 2)
        self.assertEqual(j["algorithm"], "bfs")
        self.assertIn("within", j)

    def test_every_term_has_exactly_one_key(self):
        builders = [sel.text("x"), sel.desc("x"), sel.id("x"), sel.cls("x"), sel.pkg("x"), sel.clickable(),
                    sel.bounds_inside(0, 0, 1, 1), sel.below(sel.text("x")), sel.contains_descendants(sel.text("x"))]
        for b in builders:
            for term in b.to_json()["terms"]:
                self.assertEqual(len(term), 1, term)

    def test_no_labels_is_rejected(self):
        with self.assertRaises(ValueError):
            sel.text()


if __name__ == "__main__":
    unittest.main()
