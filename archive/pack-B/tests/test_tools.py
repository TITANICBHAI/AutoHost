"""Tests for tools/progress.py (evidence ladder, deps, blockers, parallel phases) and tools/check_docs.py."""
import hashlib, pathlib, sys, tempfile, unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import progress, check_docs, loc_report  # noqa: E402

HEAD = "# T\n<!-- DASHBOARD:START -->\nx\n<!-- DASHBOARD:END -->\n"


def tracker(body):
    return HEAD + body


def errs(body):
    text = tracker(body)
    items, titles, meta = progress.parse(text, with_meta=True)
    e, w = progress.validate(items, text, meta)
    return e, w


class LadderTests(unittest.TestCase):
    def test_parse_tags_deps_needs(self):
        items, _ = progress.parse(tracker(
            "## Phase 1 — A\n- [ ] **P1-01** (UNIT) do it [M] (deps: P1-02, P1-03) (needs: INTEG)\n  - evidence: t\n"))
        i = items[0]
        self.assertEqual((i.state, i.rank, i.size, i.deps, i.needs), ("UNIT", 4, "M", ["P1-02", "P1-03"], "INTEG"))

    def test_highest_state_wins(self):
        items, _ = progress.parse(tracker("## Phase 1 — A\n- [ ] **P1-01** (WRITTEN) (COMPILED) x [S]\n  - evidence: b\n"))
        self.assertEqual(items[0].state, "COMPILED")

    def test_ticked_below_the_required_level_fails(self):
        e, _ = errs("## Phase 0 — A\n- [x] **P0-01** (WRITTEN) thing [S]\n")
        self.assertTrue(any("requires UNIT" in m for m in e))

    def test_ticked_below_needs_fails_but_doc_passes(self):
        e, _ = errs("## Phase 0 — A\n- [x] **P0-01** (WRITTEN) thing [S] (needs: DEVICE)\n- [x] **P0-02** (DOC) notes [S]\n")
        self.assertTrue(any("P0-01" in m and "requires DEVICE" in m for m in e))
        self.assertFalse(any("P0-02" in m for m in e))

    def test_verification_states_need_evidence(self):
        e, _ = errs("## Phase 1 — A\n- [ ] **P1-01** (COMPILED) thing [S]\n")
        self.assertTrue(any("claimed without an evidence line" in m for m in e))
        e, _ = errs("## Phase 1 — A\n- [ ] **P1-01** (WRITTEN) thing [S]\n")
        self.assertEqual(e, [])

    def test_pipeline_counts_only_code_items(self):
        items, _ = progress.parse(tracker(
            "## Phase 1 — A\n- [ ] **P1-01** (UNIT) a [S]\n  - evidence: t\n- [ ] **P1-02** b [S]\n- [x] **P1-03** (DOC) c [S]\n"
            "- [ ] **G1-a** gate\n  - evidence:\n"))
        pl, total = progress.pipeline(items)
        self.assertEqual(total, 2)
        self.assertEqual(round(pl["UNIT"]), 50)
        self.assertEqual(round(pl["DEVICE"]), 0)


class DependencyTests(unittest.TestCase):
    def test_missing_unticked_and_cycle(self):
        e, _ = errs("## Phase 1 — A\n- [ ] **P1-01** a [S] (deps: P1-09)\n- [ ] **P1-02** b [S] (deps: P1-03)\n- [ ] **P1-03** c [S] (deps: P1-02)\n")
        self.assertTrue(any("P1-09 does not exist" in m for m in e))
        self.assertTrue(any("cycle" in m for m in e))
        e, _ = errs("## Phase 0 — A\n- [ ] **P0-01** a [S]\n- [x] **P0-02** (DOC) b [S] (deps: P0-01)\n")
        self.assertTrue(any("before its dependency P0-01" in m for m in e))


class BlockerTests(unittest.TestCase):
    def test_blocked_item_must_be_listed(self):
        body = "## Phase 0 — A\n- [ ] **P0-01** a [S] (BLOCKED: waiting)\n\n## Blockers\n_(none)_\n"
        e, _ = errs(body)
        self.assertTrue(any("says (none)" in m for m in e))
        self.assertTrue(any("not listed under ## Blockers" in m for m in e))
        e, _ = errs(body.replace("_(none)_", "- **P0-01** waiting for the owner"))
        self.assertEqual(e, [])


class PhaseOrderTests(unittest.TestCase):
    def test_ticks_and_gates_are_not_phase_ordered(self):
        body = ("## Phase 0 — A\n- [ ] **G0-a** gate\n  - evidence:\n"
                "## Phase 1 — B\n- [x] **P1-01** (UNIT) done [M]\n  - evidence: gradle :core:model:test exit 0\n- [x] **G1-a** gate\n  - evidence: e\n")
        e, _ = errs(body)
        self.assertFalse(any("GATE_SKIPPED" in m for m in e))
        self.assertEqual(e, [])

    def test_after_is_recorded_for_the_dashboard(self):
        items, titles, meta = progress.parse(tracker("## Phase 3 — D {after: 0}\n- [ ] **P3-01** x [S]\n"), with_meta=True)
        self.assertEqual(meta["after"], {3: 0})
        self.assertEqual(titles[3], "D")

    def test_tick_without_ladder_tag_is_allowed_with_a_warning(self):
        e, w = errs("## Phase 1 — A\n- [x] **P1-01** thing [M]\n  - evidence: gradle exit 0\n")
        self.assertEqual(e, [])
        self.assertTrue(any("ticked without a ladder tag" in m for m in w))


class FailingTagTests(unittest.TestCase):
    def test_failing_needs_evidence_and_cannot_be_ticked(self):
        e, _ = errs("## Phase 1 — A\n- [ ] **P1-01** (WRITTEN) x [S] (FAILING: gradle exit 1)\n")
        self.assertTrue(any("(FAILING) needs an evidence line" in m for m in e))
        e, _ = errs("## Phase 1 — A\n- [x] **P1-01** (UNIT) x [S] (FAILING: y)\n  - evidence: z\n")
        self.assertTrue(any("ticked while tagged (FAILING)" in m for m in e))


class CheckDocsTests(unittest.TestCase):
    def make(self, files):
        d = tempfile.TemporaryDirectory()
        root = pathlib.Path(d.name)
        for rel, content in files.items():
            p = root / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
        self.addCleanup(d.cleanup)
        return root

    MANIFEST_HEAD = "id\tstatus\trepo\tsource_path\tstaged_path\tsha256\tmatrix_row\tnotes\n"

    def test_unknown_ids_links_and_paths(self):
        root = self.make({
            "docs/PROGRESS.md": "- [ ] **P1-01** a [S]\n",
            "docs/DECISIONS.md": "ADR-001 D-01\n",
            "docs/REUSE_MATRIX.md": "### R-01\n",
            "docs/migration-manifest.tsv": self.MANIFEST_HEAD,
            "docs/A.md": "See P1-01 and P9-99, ADR-001, ADR-777, R-01, R-42, [x](missing.md) and `docs/nope.md`.\n",
        })
        errors, _, _, _ = check_docs.run(root)
        text = "\n".join(errors)
        for needle in ("P9-99", "ADR-777", "R-42", "missing.md", "docs/nope.md"):
            self.assertIn(needle, text)
        self.assertNotIn("P1-01", text)

    def test_manifest_hash_and_gradle_isolation(self):
        data = b"class A"
        sha = hashlib.sha256(data).hexdigest()
        root = self.make({
            "docs/PROGRESS.md": "- [ ] **P1-01** a [S]\n",
            "settings.gradle.kts": 'include(":app", ":ai:local:imported")\n',
            "docs/migration-manifest.tsv": self.MANIFEST_HEAD + f"M-1\tstaged\taria\ta/A.kt\tmod/imported/aria/a/A.kt\t{sha}\tR-01\t\n",
        })
        (root / "mod/imported/aria/a").mkdir(parents=True)
        (root / "mod/imported/aria/a/A.kt").write_bytes(b"class A edited")
        (root / "reference/aria/a").mkdir(parents=True)
        (root / "reference/aria/a/A.kt").write_bytes(data)
        errors, _, _, rows = check_docs.run(root)
        text = "\n".join(errors)
        self.assertEqual(rows, 1)
        self.assertIn("differs from recorded sha256", text)
        self.assertIn("must never be built", text)
        (root / "mod/imported/aria/a/A.kt").write_bytes(data)
        errors, _, _, _ = check_docs.run(root)
        self.assertFalse(any("differs" in e for e in errors))


    def test_aria_rows_cannot_be_integrated_while_licence_unconfirmed(self):
        row = "M-1\tintegrated\taria\ta/A.kt\tapp/src/A.kt\t\tR-01\t\n"
        files = {"docs/PROGRESS.md": "- [ ] **P1-01** a [S]\n", "docs/migration-manifest.tsv": self.MANIFEST_HEAD + row, "app/src/A.kt": "x"}
        root = self.make({**files, "docs/DECISIONS.md": "| D-19 | ARIA licence status | **UNCONFIRMED** |\n"})
        self.assertTrue(any("D-19" in e for e in check_docs.run(root)[0]))
        root = self.make({**files, "docs/DECISIONS.md": "| D-19 | ARIA licence status | **CONFIRMED** (owner, 2026-11-01) |\n"})
        self.assertFalse(any("D-19" in e for e in check_docs.run(root)[0]))


class LocReportTests(unittest.TestCase):
    def test_roles_and_exclusions(self):
        import loc_report
        d = tempfile.TemporaryDirectory(); self.addCleanup(d.cleanup); root = pathlib.Path(d.name)
        for rel, n in {"core/x/src/main/kotlin/A.kt": 5, "core/x/src/test/kotlin/ATest.kt": 3, "sdk-python/autohost/a.py": 4,
                       "sdk-python/autohost_sim/s.py": 2, "tests/test_a.py": 2, "reference/aria/B.kt": 99,
                       "app/imported/closepaw/C.kt": 99, "runtime/python/src/main/python/autohost/a.py": 99}.items():
            f = root / rel; f.parent.mkdir(parents=True, exist_ok=True); f.write_text("x\n" * n)
        rows = loc_report.measure(root)
        self.assertEqual(rows[("Kotlin", "Kotlin shipped in the app")], [1, 5])
        self.assertEqual(rows[("Python", "Python embedded in the app (SDK)")], [1, 4])
        self.assertEqual(rows[("Python", "Python PC tooling (simulator, CLI, MCP, generators)")], [1, 2])
        self.assertEqual(sum(v[1] for v in rows.values()), 16)


if __name__ == "__main__":
    unittest.main()
