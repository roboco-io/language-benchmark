"""runtime.sh 산출물 파서: k6 --summary-export JSON과 docker stats MemUsage."""
import json
import re

_UNITS = {"B": 1 / 1048576, "KiB": 1 / 1024, "MiB": 1.0, "GiB": 1024.0, "TiB": 1048576.0,
          "kB": 1000 / 1048576, "MB": 1e6 / 1048576, "GB": 1e9 / 1048576}


def k6_summary(path):
    """k6 summary JSON -> rps, 지연(ms), error_rate(%). 2xx만 성공이므로 http_req_failed가 오류율이다."""
    with open(path) as f:
        m = json.load(f)["metrics"]
    dur = m["http_req_duration"]
    return {
        "rps": float(m["http_reqs"]["rate"]),
        "p50_ms": float(dur["p(50)"]),
        "p95_ms": float(dur["p(95)"]),
        "p99_ms": float(dur["p(99)"]),
        "error_rate": round(float(m["http_req_failed"]["value"]) * 100, 6),
    }


def mem_mb(text):
    """docker stats MemUsage의 앞쪽 값("123.4MiB / 1GiB")을 MiB 단위 float로 환산한다."""
    mt = re.match(r"\s*([0-9.]+)\s*([A-Za-z]+)", text)
    if not mt or mt.group(2) not in _UNITS:
        raise ValueError("MemUsage 형식 아님: %r" % text)
    return float(mt.group(1)) * _UNITS[mt.group(2)]
