import csv
import io
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import runs_row
from build_dashboard_data import build
from metrics import CSV_COLUMNS

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "harness"
SCRIPT = Path(__file__).resolve().parent / "runs_row.py"


def as_dict(line):
    return dict(zip(CSV_COLUMNS, next(csv.reader(io.StringIO(line)))))


class RunsRowTests(unittest.TestCase):
    def base(self):
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp)
        dst = Path(tmp) / "harness"
        shutil.copytree(FIXTURE, dst)
        return dst

    def row(self, run, base=FIXTURE):
        return as_dict(runs_row.format_row(runs_row.build_row(base, "EXP-901", run)))

    def test_pass_row_values(self):
        line = runs_row.format_row(runs_row.build_row(FIXTURE, "EXP-901", "opus55-go-1"))
        self.assertEqual(len(next(csv.reader(io.StringIO(line)))), len(CSV_COLUMNS))
        r = as_dict(line)
        self.assertEqual((r["exp"], r["run"], r["language"], r["model"], r["rep"]),
                         ("EXP-901", "opus55-go-1", "go", "opus55", "1"))
        self.assertEqual((r["pass"], r["iterations"], r["wall_min"]), ("1", "2", "12.5"))
        self.assertEqual(r["interventions"], "2")
        self.assertEqual(r["token_proxy"], "6.0")
        # 1000*15 + 2000*75 + 3000*18.75 + 4000*1.5 = 227250 -> 0.22725
        self.assertEqual(r["cost_usd"], "0.2273")
        self.assertEqual((r["rps"], r["p50_ms"], r["p95_ms"], r["p99_ms"]), ("800.0", "11.0", "32.0", "64.0"))
        self.assertEqual(r["error_rate"], "0.2")
        self.assertEqual((r["idle_rss_mb"], r["peak_rss_mb"], r["soak_mem_growth_mb"]), ("12.5", "40.25", "6.5"))
        self.assertEqual(r["soak_error_rate"], "0.5")
        self.assertEqual((r["build_sec"], r["image_mb"], r["cold_start_ms"]), ("42.5", "18.2", "350"))
        self.assertEqual((r["loc"], r["deps"], r["vulns"]), ("321", "2", "0"))
        self.assertEqual((r["followup_pass"], r["followup_min"], r["followup_token_proxy"]), ("", "", ""))

    def test_column_order_matches_csv_columns(self):
        self.assertEqual(list(runs_row.build_row(FIXTURE, "EXP-901", "opus55-go-1")), CSV_COLUMNS)

    def test_failed_run_blanks_product_metrics(self):
        r = self.row("sonnet55-python-1")
        self.assertEqual(r["pass"], "0")
        for key in ("wall_min", "iterations", "rps", "idle_rss_mb", "loc", "deps", "vulns", "build_sec"):
            self.assertEqual(r[key], "", key)
        self.assertEqual(r["token_proxy"], "6.0")
        self.assertEqual(r["interventions"], "")

    def test_cost_blank_when_price_missing(self):
        base = self.base()
        specs = json.loads((base / "model-specs.json").read_text())
        specs["claude-opus-5-5"]["pricing"]["cache_write"] = None
        (base / "model-specs.json").write_text(json.dumps(specs))
        r = self.row("opus55-go-1", base)
        self.assertEqual(r["cost_usd"], "")
        self.assertEqual(r["token_proxy"], "6.0")

    def test_wall_min_sums_resumed_segments(self):
        r = self.row("sonnet55-go-2")
        self.assertEqual((r["pass"], r["wall_min"]), ("1", "12.5"))

    def test_subagent_tokens_included(self):
        # R21: 서브에이전트 세션 토큰(1000+2000+3000)이 token_proxy에 더해진다.
        base = self.base()
        sub = base / "sessions-opus55-go-1" / "sid" / "subagents"
        sub.mkdir(parents=True)
        (sub / "agent-1.jsonl").write_text(json.dumps({"type": "assistant", "message": {"id": "sub-a", "usage": {
            "input_tokens": 1000, "output_tokens": 2000, "cache_creation_input_tokens": 3000,
            "cache_read_input_tokens": 0}}}) + "\n")
        self.assertEqual(self.row("opus55-go-1", base)["token_proxy"], "12.0")

    def test_partial_load_blanks_latency(self):
        base = self.base()
        (base / "runtime-opus55-go-1" / "load-3.json").unlink()
        r = self.row("opus55-go-1", base)
        for key in ("rps", "p50_ms", "p95_ms", "p99_ms", "error_rate"):
            self.assertEqual(r[key], "", key)
        self.assertEqual(r["idle_rss_mb"], "12.5")

    def test_missing_scanner_report_blanks_vulns(self):
        base = self.base()
        (base / "runtime-opus55-go-1" / "go.json").unlink()
        r = self.row("opus55-go-1", base)
        self.assertEqual(r["vulns"], "")
        self.assertEqual(r["loc"], "321")

    def test_missing_runtime_files_are_blank_not_zero(self):
        base = self.base()
        shutil.rmtree(base / "runtime-opus55-go-1")
        r = self.row("opus55-go-1", base)
        self.assertEqual(r["pass"], "1")
        for key in ("rps", "idle_rss_mb", "soak_mem_growth_mb", "soak_error_rate", "loc", "build_sec", "vulns"):
            self.assertEqual(r[key], "", key)

    def test_followup_failure_recorded(self):
        r = self.row("opus55-rust-1")
        self.assertEqual(r["pass"], "1")
        self.assertEqual(r["followup_pass"], "0")
        self.assertEqual(r["followup_min"], "")
        self.assertEqual(r["followup_token_proxy"], "6.0")

    def test_followup_pass_time(self):
        base = self.base()
        (base / "metrics-opus55-rust-1-fu.csv").write_text("1,2026-10-17 13:08:00,0,14,14,14,14,1,pass\n")
        r = self.row("opus55-rust-1", base)
        self.assertEqual((r["followup_pass"], r["followup_min"]), ("1", "8.0"))

    def test_append_rejects_duplicate_run(self):
        base = self.base()
        out = base / "runs.csv"
        out.write_text(",".join(CSV_COLUMNS) + "\n")
        cmd = [sys.executable, str(SCRIPT), str(base), "EXP-901", "opus55-go-1", "--append", str(out)]
        first = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(first.returncode, 0, first.stderr)
        lines = out.read_text().splitlines()
        self.assertEqual(len(lines), 2)
        second = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(second.returncode, 1)
        self.assertEqual(out.read_text().splitlines(), lines)

    def test_stdout_without_append(self):
        res = subprocess.run([sys.executable, str(SCRIPT), str(FIXTURE), "EXP-901", "opus55-go-1"],
                             capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertEqual(as_dict(res.stdout)["run"], "opus55-go-1")

    def test_output_roundtrips_dashboard(self):
        rows = [self.row(r) for r in ("opus55-go-1", "sonnet55-python-1", "opus55-rust-1")]
        cells = build(rows)["cells"]
        self.assertEqual(cells["go"]["opus55"]["n"], 1)
        self.assertEqual(cells["python"]["sonnet55"]["n"], 1)
        self.assertEqual(cells["rust"]["opus55"]["n"], 1)


if __name__ == "__main__":
    unittest.main()
