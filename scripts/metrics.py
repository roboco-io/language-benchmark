"""언어 × 지표 대시보드의 지표 정의. analysis/metrics/SCHEMA.md와 같이 갱신한다."""

import sys
from pathlib import Path

# 언어 목록·표시 이름은 언어 레지스트리(languages/*.json)가 정본이다.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / ".claude" / "skills" / "langbench" / "scripts"))
import langreg  # noqa: E402

LANGUAGES = langreg.keys()
LANGUAGE_NAMES = {e["key"]: e["name"] for e in langreg.load_all()}
MODELS = ["sonnet55", "opus55"]
MODEL_NAMES = {"sonnet55": "Sonnet 5.5", "opus55": "Opus 5.5"}

# runs.csv 열 순서(고정)
CSV_COLUMNS = [
    "exp", "run", "language", "model", "rep", "pass",
    "wall_min", "iterations", "interventions", "token_proxy", "cost_usd",
    "rps", "p50_ms", "p95_ms", "p99_ms", "error_rate", "idle_rss_mb", "peak_rss_mb",
    "soak_error_rate", "soak_mem_growth_mb", "loc", "deps", "build_sec", "image_mb",
    "cold_start_ms", "vulns", "followup_pass", "followup_min", "followup_token_proxy",
]


def _m(key, group, better, unit, ko, en, ja, zh):
    return {"key": key, "group": group, "better": better, "unit": unit,
            "ko": ko, "en": en, "ja": ja, "zh": zh}


# 대시보드에 표시하는 지표. pass_rate·cost_per_pass는 runs.csv에서 파생한다.
METRICS = [
    _m("pass_rate", "process", "higher", "%", "구현 안정성(합격률)", "Pass rate", "合格率", "通过率"),
    _m("wall_min", "process", "lower", "min", "구현 시간", "Build time (agent)", "実装時間", "实现时间"),
    _m("cost_per_pass", "process", "lower", "USD", "합격당 비용", "Cost per pass", "合格あたりコスト", "每次通过成本"),
    _m("iterations", "process", "lower", "", "반복 횟수", "Iterations", "反復回数", "迭代次数"),
    _m("interventions", "process", "lower", "", "사람 개입", "Interventions", "人の介入", "人工介入"),
    _m("token_proxy", "process", "lower", "K", "토큰 대리지표", "Token proxy", "トークン代理指標", "Token 代理指标"),
    _m("rps", "product", "higher", "req/s", "처리량", "Throughput", "スループット", "吞吐量"),
    _m("p50_ms", "product", "lower", "ms", "지연 p50", "Latency p50", "レイテンシ p50", "延迟 p50"),
    _m("p95_ms", "product", "lower", "ms", "지연 p95", "Latency p95", "レイテンシ p95", "延迟 p95"),
    _m("p99_ms", "product", "lower", "ms", "지연 p99", "Latency p99", "レイテンシ p99", "延迟 p99"),
    _m("error_rate", "product", "lower", "%", "부하 에러율", "Error rate", "エラー率", "错误率"),
    _m("idle_rss_mb", "product", "lower", "MB", "유휴 메모리", "Idle RSS", "アイドルメモリ", "空闲内存"),
    _m("peak_rss_mb", "product", "lower", "MB", "최대 메모리", "Peak RSS", "最大メモリ", "峰值内存"),
    _m("soak_error_rate", "product", "lower", "%", "장시간 부하 에러율", "Soak error rate", "長時間負荷エラー率", "长时负载错误率"),
    _m("soak_mem_growth_mb", "product", "lower", "MB", "장시간 메모리 증가", "Soak memory growth", "長時間メモリ増加", "长时内存增长"),
    _m("loc", "product", "lower", "", "코드량(LOC)", "Lines of code", "コード量", "代码行数"),
    _m("deps", "product", "lower", "", "직접 의존성", "Direct deps", "直接依存数", "直接依赖数"),
    _m("build_sec", "product", "lower", "s", "Docker 빌드 시간", "Docker build", "Docker ビルド時間", "Docker 构建时间"),
    _m("image_mb", "product", "lower", "MB", "이미지 크기", "Image size", "イメージサイズ", "镜像大小"),
    _m("cold_start_ms", "product", "lower", "ms", "콜드 스타트", "Cold start", "コールドスタート", "冷启动"),
    _m("vulns", "product", "lower", "", "취약점", "Vulnerabilities", "脆弱性", "漏洞"),
    _m("followup_pass", "maintain", "higher", "%", "기능 추가 성공률", "Follow-up pass", "機能追加成功率", "功能追加通过率"),
    _m("followup_min", "maintain", "lower", "min", "기능 추가 시간", "Follow-up time", "機能追加時間", "功能追加时间"),
    _m("followup_token_proxy", "maintain", "lower", "K", "기능 추가 토큰", "Follow-up tokens", "機能追加トークン", "功能追加 Token"),
]
