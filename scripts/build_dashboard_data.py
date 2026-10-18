#!/usr/bin/env python3
"""analysis/metrics/runs.csv를 언어 × 모델로 집계해 dashboard/data.js(window.BENCH)를 생성한다.

집계 규칙은 analysis/metrics/SCHEMA.md 참조. 빈 칸은 미측정(None)이며 0으로 바꾸지 않는다.
실행: python3 scripts/build_dashboard_data.py
"""
import csv
import json
import statistics
import sys
from datetime import datetime, timezone
from pathlib import Path

from metrics import CSV_COLUMNS, LANGUAGE_NAMES, LANGUAGES, METRICS, MODEL_NAMES, MODELS
import langreg  # metrics를 import하면 sys.path에 레지스트리 스크립트 경로가 추가된다

ROOT = Path(__file__).resolve().parent.parent
CSV_PATH = ROOT / "analysis" / "metrics" / "runs.csv"
OUT_PATH = ROOT / "dashboard" / "data.js"

# 합격 run 중앙값으로 집계하는 지표(pass_rate·cost_per_pass·followup_pass 제외)
MEDIAN_KEYS = [m["key"] for m in METRICS if m["key"] not in ("pass_rate", "cost_per_pass", "followup_pass")]


def _num(value):
    if value is None or str(value).strip() == "":
        return None
    return float(value)


def _cell(rows):
    passed = [r for r in rows if _num(r.get("pass")) == 1]
    cell = {"n": len(rows), "passed": len(passed)}
    cell["pass_rate"] = len(passed) / len(rows) if rows else None
    costs = [_num(r.get("cost_usd")) for r in rows]
    if passed and costs and all(c is not None for c in costs):
        cell["cost_per_pass"] = sum(costs) / len(passed)
    else:
        cell["cost_per_pass"] = None
    for key in MEDIAN_KEYS + ["followup_pass"]:
        values = [v for v in (_num(r.get(key)) for r in passed) if v is not None]
        if key == "followup_pass":
            cell[key] = sum(values) / len(values) if values else None
        else:
            cell[key] = statistics.median(values) if values else None
        cell[f"{key}_n"] = len(values)
    return cell


def build(rows: list[dict], experiments: dict | None = None) -> dict:
    for r in rows:
        if r.get("language") not in LANGUAGES:
            raise ValueError(f"알 수 없는 언어: {r.get('language')!r} (run {r.get('run')})")
        if str(r.get("pass", "")).strip() not in ("0", "1"):
            raise ValueError(f"pass는 0 또는 1이어야 함: {r.get('pass')!r} (run {r.get('run')})")
        if r.get("model") not in MODELS:
            raise ValueError(f"알 수 없는 모델: {r.get('model')!r} (run {r.get('run')})")
    cells = {}
    lang_exp = {}
    # 행이 하나라도 있는 언어만 내보낸다(레지스트리에만 있고 아직 실행 전인 언어가 빈 칸으로 공개되지 않게).
    # 행이 전혀 없으면 빈 상태 화면을 위해 등록된 언어를 모두 내보낸다.
    exported = [k for k in LANGUAGES if any(r["language"] == k for r in rows)] or list(LANGUAGES)
    for lang in exported:
        exps = sorted({r.get("exp") for r in rows if r["language"] == lang and r.get("exp")})
        if len(exps) > 1:
            raise ValueError(f"{lang}: 실험이 섞임 {', '.join(exps)}")
        lang_exp[lang] = exps[0] if exps else None
    for lang in exported:
        lang_rows = [r for r in rows if r["language"] == lang]
        cells[lang] = {model: _cell([r for r in lang_rows if r["model"] == model]) for model in MODELS}
        cells[lang]["all"] = _cell(lang_rows)
    return {
        "generated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "experiments": experiments or {},
        "languages": [{"key": k, "name": LANGUAGE_NAMES[k], "stack": langreg.get(k)["stack"],
                       "exp": lang_exp[k]} for k in exported],
        "models": [{"key": k, "name": MODEL_NAMES[k]} for k in MODELS],
        "metrics": METRICS,
        "cells": cells,
        "runs": rows,
    }


def read_rows(path: Path = CSV_PATH) -> list[dict]:
    with path.open(encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        if reader.fieldnames != CSV_COLUMNS:
            raise ValueError(f"{path} 열이 스키마와 다름: {reader.fieldnames}")
        return list(reader)


def read_experiments(root: Path = ROOT) -> dict[str, dict]:
    """experiments/*/experiment.json을 EXP 코드 기준으로 읽는다."""
    result = {}
    for path in sorted((root / "experiments").glob("*/experiment.json")):
        meta = json.loads(path.read_text(encoding="utf-8"))
        result[meta["exp"]] = meta
    return result


def main() -> int:
    data = build(read_rows(), read_experiments(ROOT))
    OUT_PATH.write_text(
        "// scripts/build_dashboard_data.py가 analysis/metrics/runs.csv에서 생성한다. 직접 수정 금지.\n"
        f"window.BENCH = {json.dumps(data, ensure_ascii=False, indent=1)};\n",
        encoding="utf-8",
    )
    print(f"{OUT_PATH.relative_to(ROOT)} 생성: run {len(data['runs'])}건")
    return 0


if __name__ == "__main__":
    sys.exit(main())
