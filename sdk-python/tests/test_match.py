import unittest

import support  # noqa: F401
from autohost import sel
from autohost_sim.match import norm, select


def node(ref, text="", id="", cls="android.widget.TextView", bounds=(0, 0, 100, 100), **flags):
    n = {"ref": ref, "text": text, "id": id, "cls": cls, "bounds": list(bounds), "enabled": True}
    n.update(flags)
    return n


NODES = [
    node("a", "Example app", id="fx_title"),
    node("b", "Continue", id="fx_btn_continue", cls="android.widget.Button", clickable=True, bounds=(0, 200, 100, 300)),
    node("c", "Weiter", bounds=(0, 320, 100, 420)),
    node("d", "Net\u2019s status", bounds=(0, 440, 100, 540)),
]


class Matching(unittest.TestCase):
    def refs(self, s):
        return [n["ref"] for n in select(s.to_json(), NODES)]

    def test_contains_is_case_insensitive(self):
        self.assertEqual(self.refs(sel.text("continue")), ["b"])

    def test_eq_is_exact(self):
        self.assertEqual(self.refs(sel.text("Continu", mode="eq")), [])
        self.assertEqual(self.refs(sel.text("Continue", mode="eq")), ["b"])

    def test_any_label_matches(self):
        self.assertEqual(self.refs(sel.text("Weiter", "Continue")), ["b", "c"])

    def test_typographic_apostrophe_matches_ascii(self):
        self.assertEqual(self.refs(sel.text("Net's status")), ["d"])

    def test_id_is_case_sensitive(self):
        self.assertEqual(self.refs(sel.id("FX_BTN_CONTINUE")), [])
        self.assertEqual(self.refs(sel.id("fx_btn_continue")), ["b"])

    def test_state_filter(self):
        self.assertEqual(self.refs(sel.clickable()), ["b"])

    def test_and_or(self):
        self.assertEqual(self.refs(sel.text("Continue") & sel.clickable()), ["b"])
        self.assertEqual(self.refs(sel.text("Weiter") | sel.id("fx_title")), ["a", "c"])

    def test_below_relation(self):
        self.assertEqual(self.refs(sel.below(sel.id("fx_title"))), ["b", "c", "d"])

    def test_nth_picks_one_result(self):
        self.assertEqual([n["ref"] for n in select(sel.text("e", mode="contains").nth(1).to_json(), NODES)], ["b"])

    def test_norm_is_stable(self):
        self.assertEqual(norm("  Hello\u00a0World "), "hello world")


if __name__ == "__main__":
    unittest.main()


class P3SelectorFeatures(unittest.TestCase):
    """P3-01 parity: bounds modes, drawing_order, index relation (sim mirrors the Kotlin engine)."""

    def refs(self, s):
        return [n["ref"] for n in select(s.to_json(), NODES)]

    def test_bounds_inside_filters(self):
        self.assertEqual(self.refs(sel.bounds_inside(0, 200, 100, 300)), ["b"])

    def test_bounds_overlaps_matches_intersection(self):
        self.assertEqual(self.refs(sel.bounds_overlaps(50, 210, 200, 260)), ["b"])

    def test_drawing_order(self):
        nodes = [node("a", "Overlay", drawing_order=2), node("b", "Under", drawing_order=0)]
        refs = [n["ref"] for n in select(sel.drawing_order(2).to_json(), nodes)]
        self.assertEqual(refs, ["a"])

    def test_index_relation_picks_nth_anchor_match(self):
        # 'Continue' anchors: [b]; the node must be the 0th anchor match itself → b. n=1 has no answer.
        self.assertEqual(self.refs(sel.index_at(sel.text("Continue"), 0)), ["b"])
        self.assertEqual(self.refs(sel.index_at(sel.text("Continue"), 1)), [])

    def test_index_relation_with_two_anchors(self):
        nodes = [
            node("a", "Go", bounds=(0, 400, 10, 500)),
            node("b", "Go", bounds=(0, 600, 10, 700)),
            node("c", "Other", bounds=(0, 0, 10, 100)),
        ]
        pick = lambda n: [x["ref"] for x in select(sel.index_at(sel.text("Go"), n).to_json(), nodes)]
        self.assertEqual(pick(0), ["a"])
        self.assertEqual(pick(1), ["b"])
        self.assertEqual(pick(2), [])


if __name__ == "__main__":
    unittest.main()
