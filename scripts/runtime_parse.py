"""runtime.sh 산출물 파서: k6 --summary-export JSON, docker stats MemUsage, 코드량·직접 의존성·취약점."""
import json
import re
import tomllib

import metrics  # noqa: F401  (langreg 경로를 sys.path에 올린다)
import langreg

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


# --- 코드량 ---
def loc(scc_json, language):
    """scc --format json 결과(언어별 dict 목록)에서 주 언어의 Code 줄만 합산한다."""
    names = set(langreg.get(language)["loc"]["scc_names"])
    return sum(int(x["Code"]) for x in scc_json if x["Name"] in names)


# --- 직접 런타임 의존성 ---
def _read(path):
    try:
        return path.read_text()
    except FileNotFoundError:
        return None


def _deps_go(text):
    n, in_block = 0, False
    for raw in text.splitlines():
        line = raw.strip()
        if in_block:
            if line.startswith(")"):
                in_block = False
            elif line and not line.startswith("//") and "// indirect" not in line:
                n += 1
        elif re.match(r"require\s*\(", line):
            in_block = True
        elif line.startswith("require ") and "// indirect" not in line:
            n += 1
    return n


def _deps_cpp(text):
    names = set(re.findall(r"find_package\s*\(\s*([A-Za-z0-9_+.-]+)", text))
    names |= set(re.findall(r"FetchContent_Declare\s*\(\s*([A-Za-z0-9_+.-]+)", text))
    for args in re.findall(r"pkg_check_modules\s*\(([^)]*)\)", text):
        toks = [t for t in args.split() if t not in ("REQUIRED", "QUIET", "IMPORTED_TARGET", "NO_CMAKE_PATH")]
        names |= set(toks[1:])  # 첫 토큰은 접두 변수명이다
    names.discard("Threads")
    return len(names)


def _deps_package_json(repo):
    text = _read(repo / "package.json")
    return None if text is None else len(json.loads(text).get("dependencies", {}))


def _deps_pyproject_or_requirements(repo):
    text = _read(repo / "pyproject.toml")
    if text is not None:
        proj = tomllib.loads(text).get("project", {})
        if "dependencies" in proj:
            return len(proj["dependencies"])
    text = _read(repo / "requirements.txt")
    if text is None:
        return None
    return sum(1 for ln in text.splitlines()
               if ln.strip() and not ln.strip().startswith(("#", "-r")))


def _deps_go_mod(repo):
    text = _read(repo / "go.mod")
    return None if text is None else _deps_go(text)


def _deps_cargo_toml(repo):
    text = _read(repo / "Cargo.toml")
    return None if text is None else len(tomllib.loads(text).get("dependencies", {}))


def _deps_cmake(repo):
    text = _read(repo / "CMakeLists.txt")
    return None if text is None else _deps_cpp(text)


# 레지스트리의 deps.kind -> 의존성 수 함수
DEPS_BY_KIND = {
    "package-json": _deps_package_json,
    "pyproject-or-requirements": _deps_pyproject_or_requirements,
    "go-mod": _deps_go_mod,
    "cargo-toml": _deps_cargo_toml,
    "cmake": _deps_cmake,
}


def deps(repo, language):
    """직접 런타임 의존성 수. 매니페스트가 없으면 None(미측정). 0은 측정한 0이다."""
    try:
        kind = langreg.get(language)["deps"]["kind"]
    except KeyError:
        raise ValueError("알 수 없는 언어: %r" % language) from None
    return DEPS_BY_KIND[kind](repo)


# --- 취약점 ---
def load_report(path):
    """JSON 파일을 읽는다. 값이 여러 개 이어진 스트림(govulncheck -json)이면 목록으로 돌려준다."""
    with open(path) as f:
        text = f.read()
    dec, i, out = json.JSONDecoder(), 0, []
    while True:
        while i < len(text) and text[i].isspace():
            i += 1
        if i >= len(text):
            break
        obj, i = dec.raw_decode(text, i)
        out.append(obj)
    return out[0] if len(out) == 1 else out


_GHSA = re.compile(r"GHSA(?:-[23456789cfghjmpqrvwx]{4}){3}", re.IGNORECASE)


def _ghsa(text):
    """문자열 속 GHSA ID를 'GHSA-' 대문자 + 소문자 본문으로 정규화해 모두 돌려준다."""
    return ["GHSA" + m[4:].lower() for m in _GHSA.findall(text or "")]


def _ids_trivy(r):
    for res in r.get("Results") or []:
        for v in res.get("Vulnerabilities") or []:
            # npm audit은 GHSA로 보고하므로, 참조 URL의 GHSA를 alias로 달아 같은 묶음으로 만든다.
            urls = list(v.get("References") or []) + [v.get("PrimaryURL")]
            aliases = [g for u in urls for g in _ghsa(u)]
            yield [v["VulnerabilityID"]] + aliases


def _ids_npm(r):
    for ent in (r.get("vulnerabilities") or {}).values():
        for via in ent.get("via") or []:
            if isinstance(via, dict) and via.get("url"):
                tail = via["url"].rstrip("/").rsplit("/", 1)[-1]
                yield _ghsa(tail) or [tail]


def _ids_pip(r):
    for dep in r.get("dependencies") or []:
        for v in dep.get("vulns") or []:
            yield [v["id"]] + list(v.get("aliases") or [])


def _ids_go(r):
    msgs = r if isinstance(r, list) else [r]
    osvs = {m["osv"]["id"]: m["osv"] for m in msgs if "osv" in m}
    called = set()
    for m in msgs:
        f = m.get("finding")
        # 호출 경로가 있는 finding만 센다(함수 프레임이 있는 trace). 모듈·패키지 수준 finding은 제외한다.
        if f and any("function" in fr for fr in f.get("trace") or []):
            called.add(f["osv"])
    for oid in called:
        osv = osvs.get(oid, {"id": oid})
        yield [oid] + list(osv.get("aliases") or [])


def _ids_cargo(r):
    for v in (r.get("vulnerabilities") or {}).get("list") or []:
        a = v["advisory"]
        yield [a["id"]] + list(a.get("aliases") or [])


_EXTRACT = {"trivy": _ids_trivy, "npm": _ids_npm, "pip": _ids_pip, "go": _ids_go, "cargo": _ids_cargo}


def vulns(reports):
    """보고서(키 trivy·npm·pip·go·cargo)의 취약점을 alias로 묶어 서로 다른 묶음 수를 센다.
    넘겨받은 보고서만 센다. 필요한 보고서가 빠졌는지는 호출자가 판단한다. go는 스트림 목록도 받는다."""
    parent = {}

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for key, report in reports.items():
        for group in _EXTRACT[key](report):
            for x in group:
                parent.setdefault(x, x)
            root = find(group[0])
            for x in group[1:]:
                parent[find(x)] = root
    return len({find(x) for x in parent})
