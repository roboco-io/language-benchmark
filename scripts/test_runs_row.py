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
PREV_ELAPSED = Path(__file__).resolve().parents[1] / ".claude" / "skills" / "langbench" / "scripts" / "prev_elapsed.py"


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

    def test_wall_min_drops_held_attempt(self):
        # R18: 보류된 시도(held 줄 앞의 구간)는 버리고 처음부터 다시 돈 구간만 센다.
        base = self.base()
        log = base / "driver.log"
        text = log.read_text().replace(
            "=== RUN opus55-go-1 (", "=== RUN opus55-go-1 (claude-native claude-opus-5-5, PROMPT-go.md, PORT=40999) "
            "start: 2026-10-17 08:00:00 ===\n=== RUN opus55-go-1 held: 2026-10-17 08:30:00 | stdout: limit ===\n"
            "=== RUN opus55-go-1 (", 1)
        log.write_text(text)
        self.assertEqual(self.row("opus55-go-1", base)["wall_min"], "12.5")

    def test_driver_prev_elapsed_matches_run_minutes(self):
        # R29: driver의 재개 예산(prev_elapsed.py)과 runs_row의 wall_min이 같은 구간 규칙을 쓴다.
        # 보류 시도(held 앞)는 둘 다 버리고, finished 구간 2개(5분 + 7분 30초)를 더한다.
        base = self.base()
        run = "sonnet55-rust-1"
        log = base / "driver.log"
        log.write_text(
            "=== RUN %s (claude-native m, PROMPT-rust.md, PORT=1) start: 2026-10-17 08:00:00 ===\n" % run
            + "=== RUN %s held: 2026-10-17 08:20:00 | stdout: limit ===\n" % run
            + "=== RUN %s (claude-native m, PROMPT-rust.md, PORT=2) start: 2026-10-17 10:00:00 ===\n" % run
            + "=== RUN other-x (m) start: 2026-10-17 10:01:00 ===\n"
            + "=== RUN %s finished: 2026-10-17 10:05:00 | 1,x ===\n" % run
            + "=== RUN %s (claude-native m, PROMPT-rust.md, PORT=3) start: 2026-10-17 11:00:00 ===\n" % run
            + "=== RUN %s finished: 2026-10-17 11:07:30 | 2,x ===\n" % run)
        out = subprocess.run([sys.executable, str(PREV_ELAPSED), str(log), run, str(base / "none.csv")],
                             capture_output=True, text=True, check=True).stdout
        minutes = runs_row.run_minutes(log, run, runs_row.datetime(2026, 10, 17, 11, 7, 30))
        self.assertEqual(minutes, 12.5)
        self.assertEqual(round(int(out) / 60, 1), minutes)

    def test_partial_load_blanks_latency(self):
        base = self.base()
        (base / "runtime-opus55-go-1" / "load-3.json").unlink()
        r = self.row("opus55-go-1", base)
        for key in ("rps", "p50_ms", "p95_ms", "p99_ms", "error_rate"):
            self.assertEqual(r[key], "", key)
        self.assertEqual(r["idle_rss_mb"], "12.5")

    def test_new_language_reports_flow_through(self):
        # 보고서 이름이 형식 이름과 다른 언어(php composer-audit.json, csharp dotnet.json, java osv.json)도 vulns로 이어진다.
        base = self.base()
        specs = {
            "java": ("osv.json", {"results": [{"packages": [{"vulnerabilities": [
                {"id": "GHSA-2c3f-4g5h-6j7m", "aliases": ["CVE-1"]}]}]}]},
                {"pom.xml": '<project><dependencies><dependency><artifactId>a</artifactId></dependency></dependencies></project>'}, "1"),
            "php": ("composer-audit.json", {"advisories": {"p/q": [{"advisoryId": "PKSA-1", "cve": "CVE-2", "sources": []},
                                                                   {"advisoryId": "PKSA-2", "cve": None, "sources": []}]}},
                    {"composer.json": '{"require": {"php": "^8", "a/b": "1", "c/d": "1"}}'}, "2"),
            "csharp": ("dotnet.json", {"projects": [{"frameworks": [{"topLevelPackages": [{"vulnerabilities": [
                {"advisoryurl": "https://github.com/advisories/GHSA-2c3f-4g5h-6j7m"}]}]}]}]},
                {"App.csproj": '<Project><PackageReference Include="X" Version="1"/></Project>'}, "1"),
        }
        for lang, (report, data, files, vulns) in specs.items():
            run = "opus55-%s-1" % lang
            shutil.copy(base / "metrics-opus55-go-1.csv", base / ("metrics-%s.csv" % run))
            shutil.copytree(base / "sessions-opus55-go-1", base / ("sessions-%s" % run))
            shutil.copytree(base / "runtime-opus55-go-1", base / ("runtime-%s" % run))
            (base / ("runtime-%s" % run) / "go.json").unlink()
            (base / ("runtime-%s" % run) / report).write_text(json.dumps(data))
            app = base / ("app-%s" % run)
            app.mkdir()
            for name, text in files.items():
                (app / name).write_text(text)
            with open(base / "driver.log", "a") as f:
                f.write("=== RUN %s (claude-native claude-opus-5-5, PROMPT-%s.md, PORT=1) start: 2026-10-17 10:00:00 ===\n"
                        "=== RUN %s finished: 2026-10-17 10:12:30 | 2,x ===\n" % (run, lang, run))
            r = self.row(run, base)
            self.assertEqual((r["language"], r["vulns"]), (lang, vulns), lang)
            self.assertEqual(r["deps"], {"java": "1", "php": "2", "csharp": "1"}[lang], lang)
            # 보고서가 빠지면 부분 합계 없이 빈 칸이다.
            (base / ("runtime-%s" % run) / report).unlink()
            self.assertEqual(self.row(run, base)["vulns"], "", lang)

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
