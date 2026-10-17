#!/bin/bash
# 런타임 측정 1부: 빌드 → 콜드 스타트 → 유휴 메모리 → 시드 → 부하 3회(+워밍업) → soak
# 런타임 측정 2부(정적 분석): scc 코드량, hints.txt, 언어별 취약점 스캐너, 이미지 trivy 스캔
# 사용: runtime.sh <repo> <run> [언어]   (언어를 생략하면 run 이름 "<조건>-<언어>-<n>"에서 찾는다)
# 산출물: $BASE/runtime-<run>/ 아래
#   build.json {build_sec,image_mb}, cold_start_ms.txt(ms), idle_mem.txt(MiB), load-{1,2,3}.json(k6 --summary-export),
#   peak_mem.txt(MiB, 측정 3회 동안 1초 샘플 최대), soak.json, soak_mem_start.txt·soak_mem_end.txt(MiB), error.txt(실패 시)
# 실패하면 1부의 이후 단계를 건너뛰고 실패 단계 이름을 error.txt에 쓴다(뒤 단계 파일은 없다 = 빈 칸). 2부는 그래도 실행한다.
# 2부 산출물: scc.json, hints.txt, trivy.json(빌드된 이미지 lb/<run>:rt를 trivy image로 스캔),
#   언어 스캐너 보고서(typescript npm.json, python pip.json, go go.json, rust cargo.json, cpp는 trivy만).
#   2부는 임시 clean clone에서 정적으로 돌므로 컨테이너가 실패해도 코드량·의존성·스캐너는 의미가 있다.
#   스캐너 하나가 실패(실행 불가·유효 JSON 없음)해도 다른 스캐너는 계속한다. 실패한 스캐너는 보고서를 만들지 않고
#   error.txt에 "scan:<이름>: <사유>" 줄을 덧붙인다. 취약점을 찾으면 비0으로 끝나는 스캐너(npm audit, cargo audit,
#   pip-audit)가 있으므로 종료 코드가 아니라 "유효한 JSON 보고서가 나왔는가"로 성공을 판정한다.
# error.txt가 있으면 종료 코드 1이다.
# 환경변수: SOAK_SEC(soak 길이, 기본 600), DOCKER_BUILD_SEC(빌드 한도, 기본 900), SCAN_SEC(스캐너 하나의 한도, 기본 600),
#   PIP_AUDIT_BIN(기본 $HOME/.local/share/langbench-tools/venv/bin/pip-audit), GOVULNCHECK_BIN(기본 $HOME/go/bin/govulncheck)
#   cargo audit는 $HOME/.cargo/bin을 PATH에 넣어 호출한다. trivy·scc·npm은 PATH에서 찾는다.
# 스캐너는 취약점 DB를 받으려고 네트워크를 쓴다.
# 빌드는 repo의 git HEAD를 임시 디렉터리에 새로 clone해서 한다(작업 트리의 node_modules·target·DB 같은 미추적 파일이 build_sec·image_mb에 섞이지 않게).
# 컨테이너는 --cpus=2 --memory=1g (모든 언어 동일). 포트는 40000-49999의 빈 포트를 PORT로 주입한다.
BASE="$(cd "$(dirname "$0")" && pwd)"
REPO="$1"; RUN="$2"; LANG_KEY="$3"
[ -n "$REPO" ] && [ -n "$RUN" ] || { echo "사용: runtime.sh <repo> <run> [언어]" >&2; exit 2; }
export PATH="$HOME/.cargo/bin:$PATH"
if [ -z "$LANG_KEY" ]; then
  for l in typescript python go rust cpp; do
    case "-$RUN-" in *"-$l-"*) LANG_KEY="$l" ;; esac
  done
fi
REPO="$(cd "$REPO" && pwd)" || exit 2
OUT="$BASE/runtime-$RUN"
NAME="lb-rt-$RUN"; IMAGE="lb/$RUN:rt"
DOCKER_BUILD_SEC="${DOCKER_BUILD_SEC:-900}"
SOAK_SEC="${SOAK_SEC:-600}"
SCAN_SEC="${SCAN_SEC:-600}"
PIP_AUDIT_BIN="${PIP_AUDIT_BIN:-$HOME/.local/share/langbench-tools/venv/bin/pip-audit}"
GOVULNCHECK_BIN="${GOVULNCHECK_BIN:-$HOME/go/bin/govulncheck}"
VUS=32
TMP="$(mktemp -d "${TMPDIR:-/tmp}/lb-rt.XXXXXX")"
SAMPLER=""; K6PID=""
LOADJS="$BASE/k6/load.js"

# shellcheck disable=SC2329  # trap으로 호출된다
cleanup() {
  [ -n "$SAMPLER" ] && kill "$SAMPLER" 2>/dev/null
  [ -n "$K6PID" ] && kill "$K6PID" 2>/dev/null
  docker rm -f "$NAME" >/dev/null 2>&1
  docker rmi -f "$IMAGE" >/dev/null 2>&1
  rm -rf "$TMP"
}
trap cleanup EXIT

rm -rf "$OUT"; mkdir -p "$OUT"
fail() { echo "$1" > "$OUT/error.txt"; echo "runtime 실패: $1" >&2; exit 1; }

now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

free_port() {
  local p
  for _ in $(seq 1 50); do
    p=$(( 40000 + RANDOM % 10000 ))
    lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1 || { echo "$p"; return; }
  done
}

# 컨테이너 메모리(MiB). docker stats MemUsage 앞쪽 값을 환산한다. 실패하면 빈 문자열.
mem_now() {
  docker stats --no-stream --format '{{.MemUsage}}' "$NAME" 2>/dev/null | awk '
    { v=$1; u=v; sub(/^[0-9.]+/, "", u); sub(/[A-Za-z]+$/, "", v)
      if (u=="KiB") v/=1024; else if (u=="GiB") v*=1024; else if (u=="TiB") v*=1048576; else if (u=="B") v/=1048576
      printf "%.1f\n", v; exit }'
}

# 임시 clean clone: 1부(docker build)와 2부(정적 분석)가 함께 쓴다.
SRC="$TMP/src"
git clone -q --no-hardlinks "$REPO" "$SRC" 2>"$TMP/clone.log" || fail "clone"

# 1부는 서브셸에서 돈다. fail은 서브셸만 끝내므로 2부가 이어진다. 서브셸의 EXIT 트랩은 자기 백그라운드 작업만 정리한다.
(
# shellcheck disable=SC2329  # trap으로 호출된다
stage_cleanup() {
  [ -n "$SAMPLER" ] && kill "$SAMPLER" 2>/dev/null
  [ -n "$K6PID" ] && kill "$K6PID" 2>/dev/null
  docker rm -f "$NAME" >/dev/null 2>&1
}
trap stage_cleanup EXIT

# 1. 빌드 (fresh clone, --no-cache)
[ -f "$SRC/Dockerfile" ] || fail "build: Dockerfile이 git HEAD에 없음"
T0=$(now_ms)
docker build --no-cache -t "$IMAGE" "$SRC" > "$TMP/build.log" 2>&1 < /dev/null &
BP=$!
for _ in $(seq 1 "$DOCKER_BUILD_SEC"); do kill -0 "$BP" 2>/dev/null || break; sleep 1; done
if kill -0 "$BP" 2>/dev/null; then
  pkill -9 -P "$BP" 2>/dev/null; kill -9 "$BP" 2>/dev/null
  fail "build: ${DOCKER_BUILD_SEC}s 초과"
fi
wait "$BP" || fail "build"
T1=$(now_ms)
SIZE=$(docker image inspect -f '{{.Size}}' "$IMAGE" 2>/dev/null) || fail "build: image inspect"
awk -v ms=$(( T1 - T0 )) -v sz="$SIZE" 'BEGIN { printf "{\"build_sec\": %.1f, \"image_mb\": %.1f}\n", ms/1000, sz/1e6 }' > "$OUT/build.json"

# 2. 콜드 스타트: docker run 직전부터 GET /api/tags 200까지 (50ms 간격 폴링, 최대 60초)
P="$(free_port)"; [ -n "$P" ] || fail "port"
URL="http://127.0.0.1:$P"
docker rm -f "$NAME" >/dev/null 2>&1
T0=$(now_ms)
docker run -d --name "$NAME" --cpus=2 --memory=1g -e PORT="$P" -p 127.0.0.1:"$P":"$P" "$IMAGE" >/dev/null 2>&1 || fail "run"
UP=0
while [ $(( $(now_ms) - T0 )) -lt 60000 ]; do
  [ "$(curl -s -o /dev/null -m 1 -w '%{http_code}' "$URL/api/tags")" = 200 ] && { UP=1; break; }
  sleep 0.05
done
T1=$(now_ms)
[ "$UP" = 1 ] || fail "cold_start: 60초 안에 GET /api/tags 200 없음"
echo $(( T1 - T0 )) > "$OUT/cold_start_ms.txt"

# 3. 유휴 메모리: 기동 10초 뒤
sleep 10
M=$(mem_now); [ -n "$M" ] || fail "idle_mem"
echo "$M" > "$OUT/idle_mem.txt"

# 4. 시드
python3 "$BASE/seed.py" "$URL" "$TMP/seed.json" || fail "seed"

alive() { [ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" = true ]; }
k6run() {  # k6run <duration> <summary-export>
  # load.js가 읽는 BASE는 대상 URL이다(하네스 BASE와 다름). env로 넘겨 이 스크립트의 $BASE와 섞이지 않게 한다.
  env SEED="$TMP/seed.json" BASE="$URL" VUS="$VUS" DURATION="$1" \
    k6 run --quiet --no-color --summary-export "$2" "$LOADJS" >> "$TMP/k6.log" 2>&1
}

# 5. 워밍업(결과 버림) → 측정 3회. 측정 중 1초 간격 메모리 샘플의 최대값을 peak_mem.txt에 쓴다.
k6run 15s "$TMP/warmup.json" || fail "warmup"
alive || fail "warmup: 컨테이너 종료됨"
: > "$TMP/mem_samples.txt"
( while :; do mem_now >> "$TMP/mem_samples.txt"; sleep 1; done ) &
SAMPLER=$!
disown "$SAMPLER" 2>/dev/null  # 종료 시 "Terminated" 알림이 stderr에 섞이지 않게
for i in 1 2 3; do
  k6run 30s "$OUT/load-$i.json" || { kill "$SAMPLER" 2>/dev/null; SAMPLER=""; fail "load-$i"; }
  alive || { kill "$SAMPLER" 2>/dev/null; SAMPLER=""; fail "load-$i: 컨테이너 종료됨"; }
done
kill "$SAMPLER" 2>/dev/null; SAMPLER=""
PEAK=$(awk 'NF && $1+0 > m { m=$1+0 } END { if (m > 0) printf "%.1f\n", m }' "$TMP/mem_samples.txt")
[ -n "$PEAK" ] || fail "peak_mem: 샘플 없음"
echo "$PEAK" > "$OUT/peak_mem.txt"

# 6. soak: 시작 30초 뒤와 종료 5초 전 메모리 (SOAK_SEC가 짧으면 구간 비율로 줄인다)
S_AT=30; E_AT=$(( SOAK_SEC - 5 ))
[ "$SOAK_SEC" -le 40 ] && { S_AT=$(( SOAK_SEC / 4 )); E_AT=$(( SOAK_SEC - 1 )); }
k6run "${SOAK_SEC}s" "$OUT/soak.json" &
K6PID=$!
sleep "$S_AT"
M=$(mem_now); [ -n "$M" ] && echo "$M" > "$OUT/soak_mem_start.txt"
sleep $(( E_AT - S_AT ))
M=$(mem_now); [ -n "$M" ] && echo "$M" > "$OUT/soak_mem_end.txt"
wait "$K6PID" || { K6PID=""; fail "soak"; }
K6PID=""
alive || fail "soak: 컨테이너 종료됨"
[ -s "$OUT/soak_mem_start.txt" ] && [ -s "$OUT/soak_mem_end.txt" ] || fail "soak_mem"

# 1부 끝. 컨테이너는 서브셸 트랩이 지운다.
)

# ---- 2부: 정적 분석 ----
CTR_ERR="$TMP/scan.err"

scan_fail() {  # scan_fail <이름> <사유>
  echo "scan:$1: $2" >> "$OUT/error.txt"
  echo "runtime 스캔 실패: $1: $2" >&2
}

# 유효한 JSON 값이 하나 이상이고(스트림 허용) 선택적으로 최상위 키를 갖는지 검사한다.
json_ok() {  # json_ok <파일> [필수 키]
  python3 - "$1" "${2:-}" <<'PY'
import json, sys
text = open(sys.argv[1]).read()
dec, i, vals = json.JSONDecoder(), 0, []
try:
    while True:
        while i < len(text) and text[i].isspace():
            i += 1
        if i >= len(text):
            break
        v, i = dec.raw_decode(text, i)
        vals.append(v)
except ValueError:
    sys.exit(1)
if not vals:
    sys.exit(1)
key = sys.argv[2]
if key and not (len(vals) == 1 and isinstance(vals[0], dict) and key in vals[0]):
    sys.exit(1)
PY
}

# run_scan <이름> <출력파일> <필수 키|-> <작업 디렉터리> <명령...>
# 명령의 stdout을 임시 파일에 받아 유효 JSON이면 보고서로 옮긴다. 종료 코드는 보지 않는다.
run_scan() {
  local name="$1" file="$2" key="$3" dir="$4"; shift 4
  local tmpout="$TMP/scan-$name.json" pid
  [ "$key" = "-" ] && key=""
  rm -f "$OUT/$file"
  ( cd "$dir" && exec "$@" ) > "$tmpout" 2> "$CTR_ERR" < /dev/null &
  pid=$!
  for _ in $(seq 1 "$SCAN_SEC"); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  if kill -0 "$pid" 2>/dev/null; then
    pkill -9 -P "$pid" 2>/dev/null; kill -9 "$pid" 2>/dev/null
    wait "$pid" 2>/dev/null
    scan_fail "$name" "${SCAN_SEC}s 초과"; return 1
  fi
  wait "$pid"; local rc=$?
  if [ "$rc" = 127 ]; then scan_fail "$name" "실행 불가: $(head -c 200 "$CTR_ERR" | tr '\n' ' ')"; return 1; fi
  if json_ok "$tmpout" "$key"; then
    mv "$tmpout" "$OUT/$file"
  else
    scan_fail "$name" "유효한 JSON 보고서 없음(종료 코드 $rc): $(head -c 200 "$CTR_ERR" | tr '\n' ' ')"
    return 1
  fi
}

# scc: 주 언어 코드량. 테스트·산출물 디렉터리와 테스트 파일은 뺀다.
run_scan scc scc.json - "$SRC" scc --format json \
  --exclude-dir test,tests,__tests__,hurl,node_modules,target,build,.venv,vendor \
  --not-match '(_test\.go|test_.*\.py|.*_test\.py|\.(test|spec)\.[jt]s|_test\.(cc|cpp))$' .

# hints.txt: 빌드 모드·워커 설정 단서. 최종 판정은 실험자가 이 파일을 보고 report에 적는다(반자동).
( cd "$SRC" && grep -rnE --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=target --exclude-dir=.venv --exclude-dir=vendor \
    -e '--release|CMAKE_BUILD_TYPE|--workers|cluster|GOMAXPROCS|worker_threads|setThreadNum|journal_mode|busy_timeout' . ) \
  > "$OUT/hints.txt" 2>/dev/null < /dev/null  # 일치가 없으면 grep이 1을 돌려주지만 빈 파일이 맞다

# 언어 스캐너 (R4): 보고서 이름은 typescript npm.json, python pip.json, go go.json, rust cargo.json. cpp는 없다.
case "$LANG_KEY" in
  typescript) run_scan npm npm.json vulnerabilities "$SRC" npm audit --json ;;
  python)
    if [ -f "$SRC/requirements.txt" ]; then
      run_scan pip pip.json dependencies "$SRC" "$PIP_AUDIT_BIN" -f json -r requirements.txt
    else
      run_scan pip pip.json dependencies "$SRC" "$PIP_AUDIT_BIN" -f json .
    fi ;;
  go) run_scan govulncheck go.json - "$SRC" "$GOVULNCHECK_BIN" -json ./... ;;
  rust) run_scan cargo-audit cargo.json vulnerabilities "$SRC" cargo audit --json ;;
  cpp) ;;
  *) scan_fail lang "언어를 알 수 없음: run=$RUN 언어=$LANG_KEY" ;;
esac

# trivy: 빌드된 이미지를 스캔한다. 이미지가 없으면(1부 build 실패) 보고서도 없다. 정리는 trap(cleanup)이 한다.
if docker image inspect "$IMAGE" >/dev/null 2>&1; then
  run_scan trivy trivy.json SchemaVersion "$TMP" trivy image --quiet --format json --scanners vuln "$IMAGE"
fi

[ -f "$OUT/error.txt" ] && exit 1
exit 0
