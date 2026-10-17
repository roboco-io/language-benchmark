import unittest

from build_dashboard_data import build
from metrics import LANGUAGES, METRICS, MODELS


def row(language, model, rep, passed=1, **values):
    r = {"exp": "EXP-001", "run": f"{language}-{model}-{rep}", "language": language,
         "model": model, "rep": str(rep), "pass": str(passed)}
    r.update({k: str(v) for k, v in values.items()})
    return r


class BuildTests(unittest.TestCase):
    def test_empty_rows(self):
        cells = build([])["cells"]
        self.assertEqual(list(cells), LANGUAGES)
        for lang in LANGUAGES:
            self.assertEqual(set(cells[lang]), set(MODELS) | {"all"})
            for cell in cells[lang].values():
                self.assertEqual(cell["n"], 0)
                for m in METRICS:
                    self.assertIsNone(cell[m["key"]], m["key"])

    def test_all_failed_cell(self):
        rows = [row("rust", "opus55", i, passed=0, cost_usd=1.0) for i in (1, 2, 3)]
        cell = build(rows)["cells"]["rust"]["opus55"]
        self.assertEqual(cell["pass_rate"], 0.0)
        self.assertIsNone(cell["cost_per_pass"])
        self.assertIsNone(cell["rps"])
        self.assertEqual(cell["rps_n"], 0)

    def test_median_and_missing(self):
        rows = [row("go", "sonnet55", 1, rps=100), row("go", "sonnet55", 2, rps=300),
                row("go", "sonnet55", 3, rps="")]
        cell = build(rows)["cells"]["go"]["sonnet55"]
        self.assertEqual(cell["rps"], 200.0)
        self.assertEqual(cell["rps_n"], 2)

    def test_all_combines_models(self):
        rows = [row("go", "sonnet55", 1, wall_min=4), row("go", "opus55", 1, wall_min=8)]
        self.assertEqual(build(rows)["cells"]["go"]["all"]["wall_min"], 6.0)

    def test_cost_includes_failures(self):
        rows = [row("python", "opus55", 1, passed=1, cost_usd=1.0),
                row("python", "opus55", 2, passed=0, cost_usd=3.0)]
        self.assertEqual(build(rows)["cells"]["python"]["opus55"]["cost_per_pass"], 4.0)

    def test_failed_run_product_metrics_ignored(self):
        rows = [row("cpp", "opus55", 1, passed=1, rps=500),
                row("cpp", "opus55", 2, passed=0, rps=9999)]
        cell = build(rows)["cells"]["cpp"]["opus55"]
        self.assertEqual(cell["rps"], 500.0)
        self.assertEqual(cell["rps_n"], 1)

    def test_followup_pass_rate(self):
        rows = [row("go", "opus55", 1, followup_pass=1), row("go", "opus55", 2, followup_pass=0)]
        self.assertEqual(build(rows)["cells"]["go"]["opus55"]["followup_pass"], 0.5)

    def test_unknown_language_rejected(self):
        with self.assertRaises(ValueError):
            build([row("java", "opus55", 1)])


if __name__ == "__main__":
    unittest.main()
