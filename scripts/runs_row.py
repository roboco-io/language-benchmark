#!/usr/bin/env python3
"""하네스 산출물(<BASE>)에서 analysis/metrics/runs.csv 행 1개를 만든다.

사용: python3 scripts/runs_row.py <BASE> <EXP-NNN> <run> [--append analysis/metrics/runs.csv]
run은 원 run 이름(<조건>-<언어>-<n>)이다. 후속 과제 산출물은 <run>-fu 이름에서 읽는다.
미측정 값은 빈 칸이다. 0으로 채우지 않는다(analysis/metrics/SCHEMA.md).
"""
import argparse
import csv
import io
import json
import re
import statistics
import sys
from datetime import datetime
from pathlib import Path

import runtime_parse
from aggregate_tokens import aggregate
from metrics import CSV_COLUMNS, LANGUAGES, MODELS

TIME_FORMAT = "%Y-%m-%d %H:%M:%S"
PRODUCT_COLUMNS = CSV_COLUMNS[CSV_COLUMNS.index("rps"):CSV_COLUMNS.index("vulns") + 1]
# R4: 취약점 수에 필요한 보고서. 하나라도 없으면 부분 합계를 내지 않고 빈 칸으로 둔다.
SCANNERS = {"typescript": "npm", "python": "pip", "go": "go", "rust": "cargo", "cpp": None}


def parse_run(run):
    """<조건>-<언어>-<n> -> (조건, 언어, n)."""
    mt = re.fullmatch(r"([^-]+)-([^-]+)-(\d+)", run)
    if not mt or mt.group(1) not in MODELS or mt.group(2) not in LANGUAGES:
        raise ValueError("run 이름 형식 오류: %r (<조건>-<언어>-<n>)" % run)
    return mt.group(1), mt.group(2), mt.group(3)


def read_text(path):
    try:
        return Path(path).read_text(encoding="utf-8")
    except FileNotFoundError:
        return None


def read_number(path):
    """파일 하나에 든 숫자 하나(MiB, ms 등). 파일이 없거나 비어 있으면 None."""
    text = read_text(path)
    if text is None or not text.strip():
        return None
    return float(text.strip())


def read_json(path):
    text = read_text(path)
    return None if text is None else json.loads(text)


def last_gate(met_path):
    """metrics CSV의 마지막 숫자 행 -> (iter, 종료시각, 게이트). 숫자 행이 없거나 파일이 없으면 None."""
    text = read_text(met_path)
    if text is None:
        return None
    last = None
    for row in csv.reader(io.StringIO(text)):
        if row and row[0].isdigit() and len(row) >= 9:
            last = row
    if last is None:
        return None
    return int(last[0]), datetime.strptime(last[1], TIME_FORMAT), last[8]


def run_minutes(driver_log, run, ended):
    """driver.log의 run 구간(`=== RUN <run> (…) start:` ~ `=== RUN <run> finished:`)을 모두 합친 분.
    driver는 DONE_ITER+1부터 재개하므로 iterations가 모든 구간에 걸치듯 시간도 모든 구간을 합친다(R16).
    마지막 구간은 finished 줄 대신 pass 행의 종료 시각까지 센다. 시작 줄이 없거나 앞 구간의 finished 줄이 없으면 None.
    `=== RUN <run> held:` 줄(구독 한도 보류, R18)은 그때까지의 구간을 모두 버린다. 보류 run은 held/로 옮기고 처음부터 다시 돌리기 때문이다."""
    text = read_text(driver_log)
    if text is None:
        return None
    name = re.escape(run)
    start_re = re.compile(r"^=== RUN " + name + r" \(.*\) start: (\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) ===")
    end_re = re.compile(r"^=== RUN " + name + r" finished: (\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) ")
    held_re = re.compile(r"^=== RUN " + name + r" held: ")
    segments = []  # [시작, 종료 또는 None]
    for line in text.splitlines():
        if held_re.match(line):
            segments = []
            continue
        mt = start_re.match(line)
        if mt:
            segments.append([datetime.strptime(mt.group(1), TIME_FORMAT), None])
            continue
        mt = end_re.match(line)
        if mt and segments and segments[-1][1] is None:
            segments[-1][1] = datetime.strptime(mt.group(1), TIME_FORMAT)
    if not segments:
        return None
    segments[-1][1] = ended
    if any(end is None for _, end in segments):
        return None
    return round(sum((end - start).total_seconds() for start, end in segments) / 60, 1)


def tokens(base, run):
    """sessions-<run>의 집계. 서브에이전트 세션(<세션>/subagents/*.jsonl)까지 재귀로 포함한다(R21, cost_guard.py와 같은 범위).
    message.id 중복 제거는 aggregate가 한다. 세션이 없으면 None."""
    d = Path(base) / ("sessions-%s" % run)
    if not d.is_dir() or not list(d.rglob("*.jsonl")):
        return None
    return aggregate(d, recursive=True)


def token_proxy_k(totals):
    if totals is None or totals["token_proxy"] is None:
        return None
    return round(totals["token_proxy"] / 1000, 1)


def pricing(base, cond):
    """bench.env의 CONDITIONS로 조건 -> 모델 ID를 찾고 model-specs.json의 단가를 돌려준다. 못 찾으면 None."""
    env = read_text(Path(base) / "bench.env")
    specs = read_json(Path(base) / "model-specs.json")
    if env is None or specs is None:
        return None
    mt = re.search(r'^CONDITIONS="([^"]*)"', env, re.M)
    if not mt:
        return None
    models = dict(c.split(":", 1) for c in mt.group(1).split() if ":" in c)
    return (specs.get(models.get(cond)) or {}).get("pricing")


def cost_usd(totals, price):
    if totals is None or not price:
        return None
    fields = [(totals["input_tokens"], price.get("input")),
              (totals["output_tokens"], price.get("output")),
              (totals["cache_creation_input_tokens"], price.get("cache_write")),
              (totals["cache_read_input_tokens"], price.get("cache_read"))]
    if any(n is None or p is None for n, p in fields):
        return None
    return round(sum(n * p for n, p in fields) / 1e6, 4)


def interventions(base, run):
    text = read_text(Path(base) / "interventions.csv")
    if text is None:
        return None
    for row in csv.DictReader(io.StringIO(text)):
        if row.get("run") == run and (row.get("count") or "").strip():
            return int(row["count"])
    return None


def load_metrics(rt):
    """load-1..3.json 각각 k6_summary 후 지표별 중앙값. 3회 중앙값이 지표 정의이므로 하나라도 없으면 빈 dict(R16)."""
    paths = [rt / ("load-%d.json" % i) for i in (1, 2, 3)]
    if not all(p.is_file() for p in paths):
        return {}
    runs = [runtime_parse.k6_summary(p) for p in paths]
    return {k: statistics.median(r[k] for r in runs) for k in runs[0]}


def product_metrics(base, run, language):
    rt = Path(base) / ("runtime-%s" % run)
    out = load_metrics(rt)
    out["idle_rss_mb"] = read_number(rt / "idle_mem.txt")
    out["peak_rss_mb"] = read_number(rt / "peak_mem.txt")
    start, end = read_number(rt / "soak_mem_start.txt"), read_number(rt / "soak_mem_end.txt")
    if start is not None and end is not None:
        out["soak_mem_growth_mb"] = round(end - start, 4)
    if (rt / "soak.json").is_file():
        out["soak_error_rate"] = runtime_parse.k6_summary(rt / "soak.json")["error_rate"]
    build = read_json(rt / "build.json")
    if build is not None:
        out["build_sec"], out["image_mb"] = build["build_sec"], build["image_mb"]
    ms = read_number(rt / "cold_start_ms.txt")
    out["cold_start_ms"] = None if ms is None else int(ms)
    scc = read_json(rt / "scc.json")
    if scc is not None:
        out["loc"] = runtime_parse.loc(scc, language)
    out["deps"] = runtime_parse.deps(Path(base) / ("app-%s" % run), language)
    reports = {}
    for key, name in (("trivy", "trivy.json"), (SCANNERS[language], "%s.json" % SCANNERS[language])):
        if key is None:
            continue
        if not (rt / name).is_file():
            reports = None
            break
        reports[key] = runtime_parse.load_report(rt / name)
    if reports is not None:
        out["vulns"] = runtime_parse.vulns(reports)
    return out


def build_row(base, exp, run):
    """CSV_COLUMNS 순서의 dict. 값이 None이면 미측정이다."""
    base = Path(base)
    cond, language, rep = parse_run(run)
    gate = last_gate(base / ("metrics-%s.csv" % run))
    if gate is None:
        raise ValueError("metrics-%s.csv에 숫자 행이 없음" % run)
    iteration, ended, verdict = gate
    passed = verdict == "pass"
    totals = tokens(base, run)
    row = dict.fromkeys(CSV_COLUMNS)
    row.update(exp=exp, run=run, language=language, model=cond, rep=rep)
    row["pass"] = 1 if passed else 0
    if passed:
        row["wall_min"] = run_minutes(base / "driver.log", run, ended)
        row["iterations"] = iteration
    row["interventions"] = interventions(base, run)
    row["token_proxy"] = token_proxy_k(totals)
    row["cost_usd"] = cost_usd(totals, pricing(base, cond))
    if passed:
        for key, value in product_metrics(base, run, language).items():
            row[key] = value
    fu = run + "-fu"
    fu_gate = last_gate(base / ("metrics-%s.csv" % fu))
    if fu_gate is not None:
        fu_ok = fu_gate[2] == "pass"
        row["followup_pass"] = 1 if fu_ok else 0
        if fu_ok:
            row["followup_min"] = run_minutes(base / "driver.log", fu, fu_gate[1])
        row["followup_token_proxy"] = token_proxy_k(tokens(base, fu))
    return row


def format_row(row):
    buf = io.StringIO()
    csv.writer(buf, lineterminator="").writerow("" if row[c] is None else row[c] for c in CSV_COLUMNS)
    return buf.getvalue()


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("base")
    ap.add_argument("exp")
    ap.add_argument("run")
    ap.add_argument("--append", metavar="RUNS_CSV")
    args = ap.parse_args()
    line = format_row(build_row(args.base, args.exp, args.run))
    if not args.append:
        print(line)
        return 0
    path = Path(args.append)
    existing = path.read_text(encoding="utf-8") if path.exists() else ""
    if any(r.get("run") == args.run for r in csv.DictReader(io.StringIO(existing))):
        print("이미 있는 run: %s (%s). append 거부" % (args.run, path), file=sys.stderr)
        return 1
    with path.open("a", encoding="utf-8", newline="") as f:
        if existing and not existing.endswith("\n"):
            f.write("\n")
        f.write(line + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
