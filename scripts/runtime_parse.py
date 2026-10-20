"""runtime.sh 산출물 파서: k6 --summary-export JSON, docker stats MemUsage, 코드량·직접 의존성·취약점."""
import json
import re
import tomllib
import xml.etree.ElementTree as ET

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


def _local(tag):
    """XML 태그에서 {네임스페이스}를 떼어 낸다(pom.xml은 네임스페이스가 있고 csproj는 보통 없다)."""
    return tag.rsplit("}", 1)[-1]


def _kids(el, name):
    return [c for c in el if _local(c.tag) == name]


def _deps_maven_pom(repo):
    """<project><dependencies> 바로 아래 <dependency> 중 scope가 test·provided가 아닌 것.
    <dependencyManagement>·<profiles> 안의 dependencies는 project의 직계 자식이 아니므로 세지 않는다."""
    text = _read(repo / "pom.xml")
    if text is None:
        return None
    n = 0
    for block in _kids(ET.fromstring(text), "dependencies"):
        for dep in _kids(block, "dependency"):
            scope = "".join((c.text or "").strip() for c in _kids(dep, "scope"))
            if scope not in ("test", "provided"):
                n += 1
    return n


def _deps_composer_json(repo):
    text = _read(repo / "composer.json")
    if text is None:
        return None
    req = json.loads(text).get("require", {})
    return sum(1 for k in req if k != "php" and not k.startswith("ext-"))


def _deps_csproj(repo):
    """저장소 안 모든 *.csproj(테스트 프로젝트 *Test*.csproj와 bin·obj 아래는 제외)의 PackageReference Include 이름 고유 수.
    PrivateAssets="all"(속성 또는 자식 요소)은 개발 전용이라 뺀다. csproj가 하나도 없으면 None."""
    files = [p for p in sorted(repo.rglob("*.csproj"))
             if "Test" not in p.name and not {"bin", "obj"} & set(p.relative_to(repo).parts)]
    if not files:
        return None
    names = set()
    for p in files:
        for el in ET.fromstring(p.read_text(encoding="utf-8-sig")).iter():
            if _local(el.tag) != "PackageReference" or not el.get("Include"):
                continue
            private = el.get("PrivateAssets") or "".join((c.text or "").strip() for c in _kids(el, "PrivateAssets"))
            if private.lower() != "all":
                names.add(el.get("Include"))
    return len(names)


_GRADLE_DEP = re.compile(r"""^\s*(?:implementation|api|runtimeOnly)\s*[( ]\s*(?:kotlin\(\s*"([^"]+)"|["']([^"']+)["'])""", re.M)


def _deps_gradle_kts(repo):
    """저장소 안 모든 build.gradle.kts·build.gradle(build·.gradle 아래 제외)에서 implementation·api·runtimeOnly로
    선언한 좌표의 group:artifact 고유 수. kotlin("x")는 org.jetbrains.kotlin:kotlin-x로 본다.
    platform(...) BOM, compileOnly, developmentOnly, annotationProcessor, test* 구성은 세지 않는다. 빌드 파일이 없으면 None."""
    files = [p for p in sorted(repo.rglob("build.gradle*"))
             if p.name in ("build.gradle.kts", "build.gradle")
             and not {"build", ".gradle"} & set(p.relative_to(repo).parts[:-1])]
    if not files:
        return None
    names = set()
    for p in files:
        for kt, coord in _GRADLE_DEP.findall(p.read_text()):
            names.add("org.jetbrains.kotlin:kotlin-" + kt if kt else ":".join(coord.split(":")[:2]))
    return len(names)


_SBT_DEP = re.compile(r'"([^"]+)"\s*%%?%?\s*"([^"]+)"\s*%\s*(?:"[^"]*"|[\w.]+)(\s*%\s*("?)(\w+)\4)?')
# Play sbt 플러그인이 주는 모듈 단축 이름(libraryDependencies에 그대로 쓴다)
_PLAY_SHORTHANDS = r"guice|jdbc|javaJdbc|ws|javaWs|ehcache|caffeine|filters|evolutions|cacheApi|openId|javaForms"
_SBT_SHORTHAND = re.compile(r"(?:\+=|[(,])\s*(%s)\s*(?=[,)\n])" % _PLAY_SHORTHANDS)


def _deps_sbt(repo):
    """build.sbt와 project/*.scala의 "group" %(%) "artifact" % 버전 좌표 중 구성이 Test·IntegrationTest가 아닌 것의
    group:artifact 고유 수 + build.sbt의 libraryDependencies에 쓴 Play 단축 모듈(guice, jdbc 등) 수.
    project/plugins.sbt(sbt 플러그인)는 세지 않는다. build.sbt가 없으면 None."""
    text = _read(repo / "build.sbt")
    if text is None:
        return None
    names = set(_SBT_SHORTHAND.findall(text))
    sources = [text] + [p.read_text() for p in sorted((repo / "project").glob("*.scala"))]
    for src in sources:
        for group, artifact, _, _, conf in _SBT_DEP.findall(src):
            if conf.lower() not in ("test", "it", "integrationtest"):
                names.add(group + ":" + artifact)
    return len(names)


# 레지스트리의 deps.kind -> 의존성 수 함수
DEPS_BY_KIND = {
    "package-json": _deps_package_json,
    "pyproject-or-requirements": _deps_pyproject_or_requirements,
    "go-mod": _deps_go_mod,
    "cargo-toml": _deps_cargo_toml,
    "cmake": _deps_cmake,
    "maven-pom": _deps_maven_pom,
    "composer-json": _deps_composer_json,
    "csproj": _deps_csproj,
    "gradle-kts": _deps_gradle_kts,
    "sbt": _deps_sbt,
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


def _ids_osv(r):
    for res in r.get("results") or []:
        for pkg in res.get("packages") or []:
            for v in pkg.get("vulnerabilities") or []:
                yield [v["id"]] + list(v.get("aliases") or [])


def _ids_composer(r):
    # 취약점이 없으면 PHP가 빈 배열([])로 내보내므로 dict일 때만 읽는다.
    adv = r.get("advisories")
    for items in (adv.values() if isinstance(adv, dict) else []):
        for a in items:
            # 같은 취약점을 trivy 등과 묶으려고 cve와 출처(GitHub 등)의 GHSA remoteId도 alias로 단다.
            ghsa = [g for s in a.get("sources") or [] for g in _ghsa(s.get("remoteId"))]
            yield [a["advisoryId"]] + ([a["cve"]] if a.get("cve") else []) + ghsa


def _ids_dotnet(r):
    for proj in r.get("projects") or []:
        for fw in proj.get("frameworks") or []:
            for pkg in (fw.get("topLevelPackages") or []) + (fw.get("transitivePackages") or []):
                for v in pkg.get("vulnerabilities") or []:
                    tail = (v.get("advisoryurl") or "").rstrip("/").rsplit("/", 1)[-1]
                    yield _ghsa(tail) or [tail]


_EXTRACT = {"trivy": _ids_trivy, "npm": _ids_npm, "pip": _ids_pip, "go": _ids_go, "cargo": _ids_cargo,
            "osv": _ids_osv, "composer": _ids_composer, "dotnet": _ids_dotnet}


def vulns(reports):
    """보고서(키 trivy·npm·pip·go·cargo·osv·composer·dotnet)의 취약점을 alias로 묶어 서로 다른 묶음 수를 센다.
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
