"""레지스트리 전환 회귀 테스트: EXP-001 값이 바뀌지 않고, 새 언어가 코드 수정 없이 흐르는지 확인한다."""
import csv
import importlib
import os
import shutil
import tempfile
import unittest
from pathlib import Path

import metrics
import runs_row
import runtime_parse

ROOT = Path(__file__).resolve().parent.parent
REAL_LANG_DIR = ROOT / ".claude" / "skills" / "langbench" / "languages"


class RegistryRegression(unittest.TestCase):
    def test_deps_match_runs_csv(self):
        apps = ROOT / "experiments" / "001-five-language-realworld" / "runs" / "apps"
        with open(ROOT / "analysis" / "metrics" / "runs.csv", newline="") as f:
            rows = [r for r in csv.DictReader(f) if r["exp"] == "EXP-001"]
        self.assertTrue(rows)
        for r in rows:
            want = int(r["deps"]) if r["deps"] != "" else None
            with self.subTest(run=r["run"]):
                self.assertEqual(runtime_parse.deps(apps / r["run"], r["language"]), want)

    def test_scanners_unchanged(self):
        self.assertEqual(runs_row.SCANNERS,
                         {"typescript": "npm", "python": "pip", "go": "go", "rust": "cargo", "cpp": None})

    def test_languages_unchanged(self):
        self.assertEqual(metrics.LANGUAGES, ["typescript", "python", "go", "rust", "cpp"])
        self.assertEqual(metrics.LANGUAGE_NAMES,
                         {"typescript": "TypeScript", "python": "Python", "go": "Go", "rust": "Rust", "cpp": "C++"})

    def test_unknown_language_raises(self):
        with self.assertRaises(ValueError):
            runtime_parse.deps(Path("."), "cobol")

    def test_fake_language_flows(self):
        tmp = tempfile.mkdtemp()
        old = os.environ.get("LB_LANG_DIR")
        try:
            for p in REAL_LANG_DIR.glob("*.json"):
                shutil.copy(p, tmp)
            (Path(tmp) / "zig.json").write_text(
                '{"key": "zig", "name": "Zig", "order": 6, "deps": {"kind": "go-mod"},'
                ' "scanner": {"format": "none"}, "loc": {"scc_names": ["Zig"]}}')
            os.environ["LB_LANG_DIR"] = tmp
            importlib.reload(metrics)
            importlib.reload(runs_row)
            self.assertEqual(runs_row.parse_run("sonnet55-zig-1"), ("sonnet55", "zig", "1"))
            self.assertIsNone(runs_row.SCANNERS["zig"])
            self.assertEqual(metrics.LANGUAGE_NAMES["zig"], "Zig")
            self.assertEqual(runtime_parse.loc([{"Name": "Zig", "Code": "7"}], "zig"), 7)
        finally:
            if old is None:
                os.environ.pop("LB_LANG_DIR", None)
            else:
                os.environ["LB_LANG_DIR"] = old
            shutil.rmtree(tmp)
            importlib.reload(metrics)
            importlib.reload(runs_row)


if __name__ == "__main__":
    unittest.main()
