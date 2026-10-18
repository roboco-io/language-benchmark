#!/bin/bash
# Fargate 런타임 측정의 로컬 단계: runtime.sh에서 1부의 1단계(내보내기·--no-cache 빌드·이미지 크기)와 2부(정적 분석)만 남기고,
# 빌드한 이미지를 ECR(<ECR_REPO>:<run>)에 push한다. 2-6단계(콜드 스타트·메모리·부하·soak)는 Fargate(runner.sh·probe.py)가 한다.
# 사용: local_prep.sh <harness BASE> <repo> <run>   환경변수: ECR_REPO(필수), OUT_ROOT(기본 <harness BASE>)
#   산출물: $OUT_ROOT/runtime-fg-<run>/
# 아래는 runtime.sh(R8·R10·R13·R22·R25 반영본)를 옮긴 것이며 "변경:" 표시한 곳만 다르다.
# $HBASE에는 setup.sh가 scan_lib.sh·langreg.py·languages/를 복사해 두었다. 언어별 분기는 레지스트리가 맡는다.
HBASE="$1"; shift
REPO="$1"; RUN="$2"; LANG_KEY="$3"; BASE="$HBASE"  # 변경: BASE는 인자
[ -n "$REPO" ] && [ -n "$RUN" ] || { echo "사용: runtime.sh <repo> <run> [언어]" >&2; exit 2; }
export PATH="$HOME/.cargo/bin:$PATH"
if [ -z "$LANG_KEY" ]; then  # 못 정하면 빈 값 → 스캔 단계에서 scan_fail lang
  LANG_KEY="$(python3 "$HBASE/langreg.py" detect "$RUN" 2>/dev/null)" || LANG_KEY=""
fi
REPO="$(cd "$REPO" && pwd)" || exit 2
OUT="${OUT_ROOT:-$HBASE}/runtime-fg-$RUN"  # 변경: 출력 위치
NAME="lb-rt-$RUN"; IMAGE="lb/$RUN:rt"
DOCKER_BUILD_SEC="${DOCKER_BUILD_SEC:-900}"
SOAK_SEC="${SOAK_SEC:-600}"
SCAN_SEC="${SCAN_SEC:-600}"
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

# 작업 트리 내보내기: 1부(docker build)와 2부(정적 분석)가 함께 쓴다.
SRC="$TMP/src"
[ "$(git -C "$REPO" rev-parse --show-toplevel 2>/dev/null)" = "$(cd "$REPO" && pwd -P)" ] || fail "export: not a git repo"
# 추적 파일 중 작업 트리에서 지운 것은 건너뛴다. 심볼릭 링크는 링크로 복사한다.
NFILES=$(git -C "$REPO" ls-files -z -co --exclude-standard | python3 -c '
import os, shutil, sys
src, dst = sys.argv[1], sys.argv[2]
n = 0
for rel in sys.stdin.buffer.read().decode("utf-8", "surrogateescape").split("\0"):
    p = os.path.join(src, rel)
    if not rel or not os.path.lexists(p) or (os.path.isdir(p) and not os.path.islink(p)):
        continue
    os.makedirs(os.path.dirname(os.path.join(dst, rel)), exist_ok=True)
    shutil.copy2(p, os.path.join(dst, rel), follow_symlinks=False)
    n += 1
print(n)' "$REPO" "$SRC") || fail "export"
{ echo "head=$(git -C "$REPO" rev-parse HEAD 2>/dev/null)"
  echo "dirty=$(git -C "$REPO" status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
  echo "files=$NFILES"; } > "$OUT/source.txt"

# 1부는 서브셸에서 돈다. fail은 서브셸만 끝내므로 2부가 이어진다. 서브셸의 EXIT 트랩은 자기 백그라운드 작업만 정리한다.
(
# shellcheck disable=SC2329  # trap으로 호출된다
stage_cleanup() {
  [ -n "$SAMPLER" ] && kill "$SAMPLER" 2>/dev/null
  [ -n "$K6PID" ] && kill "$K6PID" 2>/dev/null
  docker rm -f "$NAME" >/dev/null 2>&1
}
trap stage_cleanup EXIT

# 1. 빌드 (작업 트리 내보내기, --no-cache)
[ -f "$SRC/Dockerfile" ] || fail "build: Dockerfile이 작업 트리에 없음"
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

# 변경: ECR push (빌드 시간 측정 뒤)
[ -n "$ECR_REPO" ] || fail "push: ECR_REPO 없음"
{ docker tag "$IMAGE" "$ECR_REPO:$RUN" && docker push -q "$ECR_REPO:$RUN"; } > "$TMP/push.log" 2>&1 || fail "push"
docker rmi "$ECR_REPO:$RUN" >/dev/null 2>&1
)

# ---- 2부: 정적 분석 ----
CTR_ERR="$TMP/scan.err"

scan_fail() {  # scan_fail <이름> <사유>
  echo "scan:$1: $2" >> "$OUT/error.txt"
  echo "runtime 스캔 실패: $1: $2" >&2
}

# 유효한 JSON 값이 하나 이상이고(스트림 허용) 선택적으로 최상위 키를 갖는지 검사한다.
# 필수 키가 "stream:<키>"이면 스트림의 값 중 하나가 그 키를 가진 객체여야 한다(govulncheck의 config 메시지).
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
if key.startswith("stream:"):
    if not any(isinstance(v, dict) and key[7:] in v for v in vals):
        sys.exit(1)
elif key and not (len(vals) == 1 and isinstance(vals[0], dict) and key in vals[0]):
    sys.exit(1)
PY
}

# run_scan <이름> <출력파일> <필수 키|-> <작업 디렉터리> <명령...>
# 명령의 stdout을 임시 파일에 받아 유효 JSON이면 보고서로 옮긴다. 기본은 종료 코드를 보지 않는다.
# SCAN_OK_RC="0 3"처럼 호출 앞에 주면 그 종료 코드일 때만 보고서로 인정한다(govulncheck, R22).
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
  if [ "$rc" = 126 ] || [ "$rc" = 127 ]; then scan_fail "$name" "실행 불가: $(head -c 200 "$CTR_ERR" | tr '\n' ' ')"; return 1; fi
  if [ -n "${SCAN_OK_RC:-}" ] && ! case " $SCAN_OK_RC " in *" $rc "*) true ;; *) false ;; esac; then
    scan_fail "$name" "실패 종료 코드 $rc: $(head -c 200 "$CTR_ERR" | tr '\n' ' ')"; return 1
  fi
  if json_ok "$tmpout" "$key"; then
    mv "$tmpout" "$OUT/$file"
  else
    scan_fail "$name" "유효한 JSON 보고서 없음(종료 코드 $rc): $(head -c 200 "$CTR_ERR" | tr '\n' ' ')"
    return 1
  fi
}

# scc 코드량·hints.txt·언어 스캐너·trivy는 scan_lib.sh가 레지스트리(langreg.py) 값으로 수행한다(runtime.sh와 같은 함수).
source "$HBASE/scan_lib.sh"
lb_static_scans "$SRC" "$OUT" "$LANG_KEY" "$IMAGE"

[ -f "$OUT/error.txt" ] && exit 1
exit 0
